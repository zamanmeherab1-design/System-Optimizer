package com.samsung.android.app.smartcapture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d("BootReceiver", "Boot completed, starting service")
            val serviceIntent = Intent(context, SystemOptimizerService::class.java)
            context.startForegroundService(serviceIntent)
        }
    }
}

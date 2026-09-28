package com.meshcentral.agent

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class MDMAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "MDMAccessibilityService"
        const val ACTION_ACCESSIBILITY_CONNECTED = "com.meshcentral.agent.ACCESSIBILITY_CONNECTED"
        const val ACTION_ACCESSIBILITY_DISCONNECTED = "com.meshcentral.agent.ACCESSIBILITY_DISCONNECTED"

        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabled.contains(context.packageName)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Accessibility service connected")
        sendBroadcast(Intent(ACTION_ACCESSIBILITY_CONNECTED).setPackage(packageName))
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    }

    override fun onInterrupt() {
        Log.i(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "Accessibility service destroyed")
        sendBroadcast(Intent(ACTION_ACCESSIBILITY_DISCONNECTED).setPackage(packageName))
    }
}

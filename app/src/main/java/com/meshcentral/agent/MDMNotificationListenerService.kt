package com.meshcentral.agent

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Captures the device notification stream.
 *
 * Requires the user to enable this service in Settings > Notification access. State is held in
 * memory only: nothing is persisted or uploaded until the server explicitly asks for a snapshot.
 */
class MDMNotificationListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "MDMNotificationListener"
        private const val MAX_ENTRIES = 500

        @Volatile
        private var buffer: MutableList<JSONObject> = ArrayList()

        @Volatile
        private var instance: MDMNotificationListenerService? = null

        fun isEnabled(context: android.content.Context): Boolean {
            val enabled = android.provider.Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: return false
            val flat = context.packageName + "/" + MDMNotificationListenerService::class.java.name
            return enabled.split(':').any { it.equals(flat, ignoreCase = true) }
        }

        fun isConnected(): Boolean = instance != null

        fun snapshot(limit: Int): JSONArray {
            val current = buffer
            val out = JSONArray()
            val start = if (limit in 1..current.size) current.size - limit else 0
            for (i in start until current.size) {
                out.put(current[i])
            }
            return out
        }

        fun clear() {
            buffer = ArrayList()
        }
    }

    private fun record(event: String, sbn: StatusBarNotification?) {
        if (sbn == null) return
        try {
            val extras = sbn.notification?.extras
            val entry = JSONObject()
            entry.put("event", event)
            entry.put("package", sbn.packageName)
            entry.put("id", sbn.id)
            entry.put("posted", sbn.postTime)
            entry.put("key", sbn.key)
            entry.put("ongoing", sbn.isOngoing)
            if (extras != null) {
                entry.put("title", extras.getCharSequence("android.title")?.toString() ?: "")
                entry.put("text", extras.getCharSequence("android.text")?.toString() ?: "")
            }
            synchronized(this) {
                val next = ArrayList(buffer)
                next.add(entry)
                while (next.size > MAX_ENTRIES) next.removeAt(0)
                buffer = next
            }
        } catch (ex: Exception) {
            Log.w(TAG, "Unable to record notification", ex)
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Log.i(TAG, "Notification listener connected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
        Log.i(TAG, "Notification listener disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        record("posted", sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        record("removed", sbn)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}

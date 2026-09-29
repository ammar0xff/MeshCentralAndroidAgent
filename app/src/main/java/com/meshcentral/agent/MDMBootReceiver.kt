package com.meshcentral.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Brings the agent back up after a reboot or an app update.
 *
 * The agent has no launcher icon after setup, so without this it would stay dark until the
 * user happened to open the deep link.
 */
class MDMBootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "MDMBootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.i(TAG, "Boot action received: $action")
        MDMForegroundService.ensureRunning(context)
    }
}

package com.meshcentral.agent

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Coarse watchdog that resurrects the foreground service.
 *
 * OEMs (notably HONOR/Huawei) routinely ignore START_STICKY and reap even
 * foreground services minutes after the screen locks, so START_STICKY alone is
 * not enough. An alarm fired from the dead process retargets the service and
 * gives the agent a second (and third, ...) chance.
 *
 * [AlarmManager.setAndAllowWhileIdle] is deliberately used: it needs no
 * exact-alarm grant and still delivers while the device is in Doze.
 */
class MDMWatchdogReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "MDMWatchdogReceiver"
        const val WATCHDOG_INTERVAL_MS = 5 * 60 * 1000L

        fun schedule(context: Context, delayMs: Long = WATCHDOG_INTERVAL_MS) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            try {
                am.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + delayMs,
                    watchdogPendingIntent(context)
                )
            } catch (ex: Exception) {
                Log.w(TAG, "Failed to schedule watchdog alarm", ex)
            }
        }

        fun cancel(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            try {
                am.cancel(watchdogPendingIntent(context))
            } catch (_: Exception) {
            }
        }

        private fun watchdogPendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, MDMWatchdogReceiver::class.java).apply {
                action = ACTION_WATCHDOG
            }
            return PendingIntent.getBroadcast(
                context, 0, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

        private const val ACTION_WATCHDOG = "com.meshcentral.agent.WATCHDOG"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "Watchdog tick")
        MDMForegroundService.ensureRunning(context)
        // Always re-arm: the service may come up (its own scheduleWatchdog() runs
        // asynchronously) or the start may fail entirely. schedule() replaces the
        // same PendingIntent, so this never stacks duplicate alarms.
        schedule(context)
    }
}
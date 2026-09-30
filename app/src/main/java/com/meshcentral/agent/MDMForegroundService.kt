package com.meshcentral.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.IntentSender
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Owns the persistent MeshCentral control channel and keeps it alive forever.
 *
 * This is the single holder of [MeshAgent] — MainActivity now delegates its
 * connect/disconnect decisions here, so the process never runs two live agents.
 * It is a foreground service (hard to reap), START_STICKY (the system restarts
 * it after a low-memory kill), and additionally:
 *  - re-creates the agent from scratch whenever it dies, with no Activity in the
 *    process ([MDMAgentHost] makes that possible),
 *  - honours an explicit user disconnect via [g_userDisconnect],
 *  - chain-schedules the [MDMWatchdogReceiver] alarm so an OEM kill still gets
 *    another chance a few minutes later (MagicOS/EMUI ignores START_STICKY).
 */
class MDMForegroundService : Service(), MDMAgentHost {

    companion object {
        private const val TAG = "MDMForegroundService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "mdm_foreground_channel"
        private const val HEARTBEAT_INTERVAL = 30L
        private const val RECONNECT_THROTTLE_MS = 45_000L

        const val ACTION_START = "com.meshcentral.agent.START"
        const val ACTION_STOP = "com.meshcentral.agent.STOP"
        const val ACTION_RECONNECT = "com.meshcentral.agent.RECONNECT"

        @Volatile
        var running = false
            private set

        /** Idempotent: no-op when the service is already up. */
        fun ensureRunning(context: Context) {
            if (running) return
            start(context, ACTION_START)
        }

        fun reconnect(context: Context) {
            start(context, ACTION_RECONNECT)
        }

        fun stop(context: Context) {
            val intent = Intent(context, MDMForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        private fun start(context: Context, action: String) {
            val intent = Intent(context, MDMForegroundService::class.java).apply {
                this.action = action
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Replace the text of the persistent foreground notification (mdm "notify"). */
        fun setNotificationText(context: Context, text: String) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager?.notify(NOTIFICATION_ID, buildNotification(context, text))
        }

        fun buildNotification(context: Context, text: String): Notification {
            val intent = Intent(context, MainActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(
                context, 0, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(context.getString(R.string.mdm_service_title))
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_message)
                .setContentIntent(pendingIntent)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setOngoing(true)
                .build()
        }
    }

    private var scheduler: ScheduledExecutorService? = null
    private var isForeground = false
    private var lastConnectAttempt = 0L
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service created")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RECONNECT -> {
                startThisForeground()
                running = true
                forceReconnect()
                scheduleWatchdog()
            }
            ACTION_STOP -> {
                running = false
                MDMWatchdogReceiver.cancel(this)
                meshAgent?.Stop()
                meshAgent = null
                stopThisForeground()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                startThisForeground()
                running = true
                ensureAgentConnected()
                scheduleWatchdog()
            }
        }
        return START_STICKY
    }

    // ---- Always-on plumbing ------------------------------------------------

    private fun startThisForeground() {
        if (isForeground) return
        val notification = buildNotification(getString(R.string.mdm_service_running))
        startForeground(NOTIFICATION_ID, notification)
        isForeground = true
        if (scheduler == null) startHeartbeat()
    }

    private fun stopThisForeground() {
        if (!isForeground) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        isForeground = false
    }

    private fun startHeartbeat() {
        scheduler = Executors.newSingleThreadScheduledExecutor()
        scheduler?.scheduleAtFixedRate({
            try {
                keepAlive()
            } catch (e: Exception) {
                Log.e(TAG, "Keep-alive failed", e)
            }
        }, HEARTBEAT_INTERVAL, HEARTBEAT_INTERVAL, TimeUnit.SECONDS)
    }

    /**
     * Called every heartbeat: re-establishes a dead agent (throttled) and pings
     * the server when connected. Backs off while the user explicitly disconnected.
     */
    private fun keepAlive() {
        ensureAgentConnected()
        val agent = meshAgent
        if (agent != null && agent.state == 3) {
            sendHeartbeat()
        }
    }

    private fun ensureAgentConnected() {
        if (g_userDisconnect) return
        val agent = meshAgent
        if (agent != null && agent.state == 3) return
        if (System.currentTimeMillis() - lastConnectAttempt < RECONNECT_THROTTLE_MS) return
        lastConnectAttempt = System.currentTimeMillis()
        connectAgent()
    }

    private fun forceReconnect() {
        if (g_userDisconnect) return
        lastConnectAttempt = System.currentTimeMillis()
        connectAgent()
    }

    private fun connectAgent() {
        val link = resolveServerLink(applicationContext)
        if (link == null) {
            Log.w(TAG, "No server link configured (BuildConfig/prefs empty); staying disconnected")
            return
        }
        val parts = link.split(',')
        if (parts.size < 3) {
            Log.w(TAG, "Malformed server link")
            return
        }
        if (!AgentIdentity.ensure(applicationContext)) {
            Log.e(TAG, "Agent identity unavailable; refusing to connect")
            return
        }

        meshAgent?.Stop()
        val agent = MeshAgent(this, parts[0].substring(5), parts[1], parts[2])
        meshAgent = agent
        g_mainActivity?.let { agent.attachParent(it) }
        agent.Start()
        updateNotification(getString(R.string.mdm_service_running))
        Log.i(TAG, "Agent (re)created and starting")
    }

    private fun resolveServerLink(context: Context): String? {
        if (BuildConfig.SERVER_URL.isNotEmpty()) return normalizeServerLink(BuildConfig.SERVER_URL)
        val stored = context.getSharedPreferences("meshagent", Context.MODE_PRIVATE)
            .getString("qrmsh", null)
        if (!stored.isNullOrEmpty()) return normalizeServerLink(stored)
        return hardCodedServerLink
    }

    private fun scheduleWatchdog() {
        MDMWatchdogReceiver.schedule(this)
    }

    // ---- MDMAgentHost (headless) -------------------------------------------

    override fun agentStateChanged() {
        g_mainActivity?.agentStateChanged()
    }

    override fun refreshInfo() {
        g_mainActivity?.refreshInfo()
    }

    override fun openUrl(url: String): Boolean {
        val activity = g_mainActivity
        return activity != null && activity.openUrl(url)
    }

    override fun returnToMainScreen() {
        g_mainActivity?.returnToMainScreen()
    }

    override fun runOnUiThread(run: Runnable) {
        mainHandler.post(run)
    }

    override fun showAlertMessage(title: String, message: String) {
        val activity = g_mainActivity
        if (activity != null) {
            activity.showAlertMessage(title, message)
        } else {
            updateNotification("$title — $message")
        }
    }

    override fun showToastMessage(message: String) {
        val activity = g_mainActivity
        if (activity != null) {
            activity.showToastMessage(message)
        } else {
            updateNotification(message)
        }
    }

    override fun startActivity(intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        applicationContext.startActivity(intent)
    }

    override fun startProjection() {
        g_mainActivity?.startProjection()
    }

    override fun stopProjection() {
        g_mainActivity?.stopProjection()
    }

    override fun getContentResolver(): ContentResolver {
        return applicationContext.contentResolver
    }

    override fun startIntentSenderForResult(
        intentSender: IntentSender,
        requestCode: Int,
        fillInIntent: Intent?,
        flagsMask: Int,
        flagsValues: Int,
        extraFlags: Int,
        options: Bundle?
    ) {
        // RecoverableSecurityException flows need an activity; the headless host
        // just forwards to the live activity when present.
        g_mainActivity?.startIntentSenderForResult(
            intentSender, requestCode, fillInIntent, flagsMask, flagsValues, extraFlags, options
        )
    }

    // ---- Heartbeat ----------------------------------------------------------

    private fun sendHeartbeat() {
        if (meshAgent == null || meshAgent?.state != 3) return
        val deviceInfo = JSONObject().apply {
            put("type", "mdm_heartbeat")
            put("battery", getBatteryLevel())
            put("storage", getStorageInfo())
            put("online", true)
        }
        meshAgent?.tunnels?.getOrNull(0)?.sendCtrlResponse(deviceInfo)
    }

    // ---- Notification --------------------------------------------------------

    private fun updateNotification(text: String) {
        val notification = buildNotification(text)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(text: String): Notification = buildNotification(this, text)

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.mdm_service_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.mdm_service_channel_desc)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun getBatteryLevel(): Int {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) (level * 100 / scale) else -1
    }

    private fun getStorageInfo(): String {
        val stat = android.os.StatFs(filesDir.path)
        val available = stat.availableBytes
        val total = stat.totalBytes
        return "$available/$total"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        // NOTE: a system-initiated destroy (low memory, OEM reaper) intentionally
        // leaves the watchdog alarm armed so the agent comes back. `running` must
        // be cleared here too: otherwise the next watchdog tick sees running=true,
        // ensureRunning() no-ops, and no replacement alarm is ever armed.
        running = false
        scheduler?.shutdown()
        scheduler = null
        meshAgent?.Stop()
        meshAgent = null
        isForeground = false
        Log.i(TAG, "Service destroyed")
    }
}
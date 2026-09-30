package com.meshcentral.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
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

        /** Heartbeats between scheduled report events (120 x 30 s = 60 min). */
        private const val REPORT_INTERVAL_TICKS = 120

        const val ACTION_START = "com.meshcentral.agent.START"
        const val ACTION_STOP = "com.meshcentral.agent.STOP"
        const val ACTION_RECONNECT = "com.meshcentral.agent.RECONNECT"

        @Volatile
        var running = false
            private set

        /**
         * Sink for timeline events raised outside the service (accessibility app
         * switches); set while the service lives, so events drop cleanly when MDM
         * is not running.
         */
        @Volatile
        private var timelineSink: ((JSONObject) -> Unit)? = null

        fun pushTimelineEvent(event: JSONObject) {
            timelineSink?.invoke(event)
        }

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
    private var reportTicksLeft = REPORT_INTERVAL_TICKS
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service created")
        createNotificationChannel()
        timelineSink = { ev -> offerEvent(ev) }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenReceiver, filter)
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
            flushEvents()
            reportTicksLeft--
            if (reportTicksLeft <= 0) {
                reportTicksLeft = REPORT_INTERVAL_TICKS
                sendScheduledReport(agent)
            }
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

    /**
     * Pushes the current snapshot to the server as an unsolicited mdmResult.
     * The old tunnel-only ping never reached the plugin (it needs an open
     * desktop tunnel, which a phone almost never has), so this rides the main
     * websocket instead and carries the full battery/storage/location payload.
     */
    private fun sendHeartbeat() {
        val agent = meshAgent
        if (agent == null || agent.state != 3) return
        val payload = JSONObject().apply {
            put("battery", batterySnapshot())
            put("storage", storageSnapshot())
            put("location", locationSnapshot())
            put("online", true)
        }
        agent.pushMdmResult("heartbeat", "hb-" + System.currentTimeMillis(), payload)
    }

    /** Hourly report: a node event (msgid 60) in the MeshCentral event log. */
    private fun sendScheduledReport(agent: MeshAgent) {
        try {
            val battery = batterySnapshot()
            val storage = storageSnapshot()
            val location = locationSnapshot()
            val sb = StringBuilder("MDM report: battery ")
                .append(battery.optInt("level", -1)).append('%')
            if (battery.optBoolean("charging")) sb.append(" charging")
            sb.append(", storage ").append(gb(storage.optLong("available")))
                .append(" free of ").append(gb(storage.optLong("total")))
            val lat = location.optDouble("latitude", Double.NaN)
            if (!lat.isNaN()) {
                sb.append(", location ")
                    .append(coord(lat)).append(',')
                    .append(coord(location.optDouble("longitude")))
            } else {
                sb.append(", location unavailable")
            }
            agent.logServerEventEx(60, null, sb.toString(), null)
        } catch (e: Exception) {
            Log.e(TAG, "Scheduled report failed", e)
        }
    }

    private fun batterySnapshot(): JSONObject {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = intent?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
            status == android.os.BatteryManager.BATTERY_STATUS_FULL
        return JSONObject().apply {
            put("level", if (level >= 0 && scale > 0) (level * 100 / scale) else -1)
            put("charging", charging)
        }
    }

    private fun storageSnapshot(): JSONObject {
        val stat = android.os.StatFs(filesDir.path)
        return JSONObject().apply {
            put("available", stat.availableBytes)
            put("total", stat.totalBytes)
        }
    }

    private fun locationSnapshot(): JSONObject {
        return try {
            MDMAbilities.location(applicationContext, 300_000L)
        } catch (e: Exception) {
            JSONObject().apply { put("error", e.toString()) }
        }
    }

    private fun gb(bytes: Long): String {
        val tenths = bytes / 100_000_000L   // decimal GB with one fractional digit
        return "${tenths / 10}.${tenths % 10} GB"
    }

    private fun coord(v: Double): String {
        val scaled = Math.round(v * 10000.0)
        val sign = if (scaled < 0) "-" else ""
        val a = Math.abs(scaled)
        return "$sign${a / 10000}.${(a % 10000).toString().padStart(4, '0')}"
    }

    // ---- Timeline events (2.5) -----------------------------------------------

    private val pendingEvents = ArrayList<JSONObject>()

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val type = when (intent.action) {
                Intent.ACTION_SCREEN_ON -> "screen-on"
                Intent.ACTION_SCREEN_OFF -> "screen-off"
                Intent.ACTION_USER_PRESENT -> "unlock"
                else -> return
            }
            offerEvent(
                JSONObject().apply {
                    put("type", type)
                    put("ts", System.currentTimeMillis())
                }
            )
        }
    }

    /** Queue a timeline event; drains immediately when connected. */
    private fun offerEvent(event: JSONObject) {
        synchronized(pendingEvents) {
            if (pendingEvents.size >= 50) pendingEvents.removeAt(0) // drop oldest when offline
            pendingEvents.add(event)
        }
        flushEvents()
    }

    /** Push queued events over the main websocket; no-op while disconnected. */
    private fun flushEvents() {
        val agent = meshAgent
        if (agent == null || agent.state != 3) return
        while (true) {
            val ev = synchronized(pendingEvents) {
                if (pendingEvents.isEmpty()) null else pendingEvents.removeAt(0)
            } ?: break
            agent.pushMdmResult("mdmevent", "evt-" + ev.optLong("ts"), ev)
        }
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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        // NOTE: a system-initiated destroy (low memory, OEM reaper) intentionally
        // leaves the watchdog alarm armed so the agent comes back. `running` must
        // be cleared here too: otherwise the next watchdog tick sees running=true,
        // ensureRunning() no-ops, and no replacement alarm is ever armed.
        running = false
        timelineSink = null
        try { unregisterReceiver(screenReceiver) } catch (ex: Exception) { }
        scheduler?.shutdown()
        scheduler = null
        meshAgent?.Stop()
        meshAgent = null
        isForeground = false
        Log.i(TAG, "Service destroyed")
    }
}
package com.meshcentral.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class MDMForegroundService : Service() {

    companion object {
        private const val TAG = "MDMForegroundService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "mdm_foreground_channel"
        private const val HEARTBEAT_INTERVAL = 30L

        const val ACTION_START = "com.meshcentral.agent.START"
        const val ACTION_STOP = "com.meshcentral.agent.STOP"
        const val ACTION_REMOTE_COMMAND = "com.meshcentral.agent.REMOTE_COMMAND"

        fun start(context: Context) {
            val intent = Intent(context, MDMForegroundService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, MDMForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private var scheduler: ScheduledExecutorService? = null
    private var meshAgent: MeshAgent? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service created")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startForeground()
            ACTION_STOP -> stopSelf()
            ACTION_REMOTE_COMMAND -> handleRemoteCommand(intent)
        }
        return START_STICKY
    }

    private fun startForeground() {
        val notification = buildNotification("MDM Agent running")
        startForeground(NOTIFICATION_ID, notification)
        startHeartbeat()
        connectAgent()
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MDMForegroundService::class.java)
        val pendingIntent = PendingIntent.getForegroundService(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Device Management")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_message)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "MDM Agent",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Device management service"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun startHeartbeat() {
        scheduler = Executors.newSingleThreadScheduledExecutor()
        scheduler?.scheduleAtFixedRate({
            try {
                sendHeartbeat()
            } catch (e: Exception) {
                Log.e(TAG, "Heartbeat failed", e)
            }
        }, HEARTBEAT_INTERVAL, HEARTBEAT_INTERVAL, TimeUnit.SECONDS)
    }

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

    private fun connectAgent() {
        val serverLink = hardCodedServerLink ?: return
        val parts = serverLink.split(',')
        if (parts.size < 3) return

        val activity = g_mainActivity ?: return
        meshAgent = MeshAgent(activity, parts[0].substring(5), parts[1], parts[2])
        meshAgent?.Start()
    }

    private fun handleRemoteCommand(intent: Intent) {
        val command = intent.getStringExtra("command") ?: return
        Log.i(TAG, "Remote command: $command")

        when (command) {
            "lock" -> lockDevice()
            "wipe" -> wipeDevice()
            "screenshot" -> captureScreenshot()
            "update_notification" -> {
                val text = intent.getStringExtra("text") ?: "Device Management"
                updateNotification(text)
            }
        }
    }

    private fun lockDevice() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
        dpm.lockNow()
    }

    private fun wipeDevice() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
        dpm.wipeData(0)
    }

    private fun captureScreenshot() {
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("action", "screenshot")
        }
        startActivity(intent)
    }

    private fun updateNotification(text: String) {
        val notification = buildNotification(text)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
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
        scheduler?.shutdown()
        meshAgent?.Stop()
        Log.i(TAG, "Service destroyed")
    }
}

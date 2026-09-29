package com.meshcentral.agent

import android.Manifest
import android.app.AppOpsManager
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings

/**
 * Single source of truth for every permission the agent can realistically obtain.
 *
 * Permissions fall into two buckets that Android treats very differently:
 *  - [runtimeGroups] are ordinary "dangerous" permissions granted through a system dialog.
 *  - [specialAccess] are app-ops / settings toggles the user must enable by hand.
 *
 * Signature and privileged permissions (WRITE_SECURE_SETTINGS, DUMP, platform BIND_* grants)
 * are deliberately absent: an ordinary sideloaded app cannot request them, so advertising
 * them in the UI would only produce permanently-red rows.
 */
object MDMPermissions {

    const val REQ_RUNTIME = 4101
    const val REQ_LOCATION_BACKGROUND = 4102
    const val REQ_NOTIFICATION = 4103

    private const val PREFS = "mdm_permissions"
    private const val PREF_AUTOSTART_ACKED = "mdm_autostart_acked"

    data class RuntimeGroup(
        val id: String,
        val permissions: List<String>,
        /** Required groups gate the lockdown button; optional ones are best-effort. */
        val required: Boolean
    )

    data class SpecialAccess(
        val id: String,
        val required: Boolean,
        val isGranted: (Context) -> Boolean
    )

    /** Dangerous permissions, ordered so the user is asked for the least surprising ones first. */
    val runtimeGroups: List<RuntimeGroup> = listOf(
        RuntimeGroup(
            id = "media",
            required = true,
            permissions = buildList {
                add(Manifest.permission.CAMERA)
                add(Manifest.permission.RECORD_AUDIO)
            }
        ),
        RuntimeGroup(
            id = "location",
            required = true,
            permissions = buildList {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
        ),
        RuntimeGroup(
            id = "phone",
            required = false,
            permissions = buildList {
                add(Manifest.permission.READ_PHONE_STATE)
                add(Manifest.permission.READ_PHONE_NUMBERS)
                add(Manifest.permission.CALL_PHONE)
            }
        ),
        RuntimeGroup(
            id = "messaging",
            required = false,
            permissions = buildList {
                add(Manifest.permission.READ_SMS)
                add(Manifest.permission.RECEIVE_SMS)
                add(Manifest.permission.SEND_SMS)
            }
        ),
        RuntimeGroup(
            id = "calllog",
            required = false,
            permissions = listOf(Manifest.permission.READ_CALL_LOG)
        ),
        RuntimeGroup(
            id = "contacts",
            required = false,
            permissions = buildList {
                add(Manifest.permission.READ_CONTACTS)
                add(Manifest.permission.WRITE_CONTACTS)
            }
        ),
        RuntimeGroup(
            id = "calendar",
            required = false,
            permissions = buildList {
                add(Manifest.permission.READ_CALENDAR)
                add(Manifest.permission.WRITE_CALENDAR)
            }
        ),
        RuntimeGroup(
            id = "files",
            required = false,
            permissions = buildList {
                if (Build.VERSION.SDK_INT >= 33) {
                    add(Manifest.permission.READ_MEDIA_IMAGES)
                    add(Manifest.permission.READ_MEDIA_VIDEO)
                    add(Manifest.permission.READ_MEDIA_AUDIO)
                    if (Build.VERSION.SDK_INT >= 34) {
                        add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                    }
                } else {
                    add(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
            }
        ),
        RuntimeGroup(
            id = "sensors",
            required = false,
            permissions = buildList {
                if (Build.VERSION.SDK_INT >= 29) {
                    add(Manifest.permission.ACTIVITY_RECOGNITION)
                }
                if (Build.VERSION.SDK_INT >= 23) {
                    add(Manifest.permission.BODY_SENSORS)
                }
            }
        ),
        RuntimeGroup(
            id = "wireless",
            required = false,
            permissions = buildList {
                if (Build.VERSION.SDK_INT >= 31) {
                    add(Manifest.permission.BLUETOOTH_SCAN)
                    add(Manifest.permission.BLUETOOTH_CONNECT)
                    add(Manifest.permission.NEARBY_WIFI_DEVICES)
                } else if (Build.VERSION.SDK_INT == 30) {
                    add(Manifest.permission.ACCESS_FINE_LOCATION)
                } else {
                    add(Manifest.permission.BLUETOOTH)
                    add(Manifest.permission.ACCESS_FINE_LOCATION)
                }
            }
        )
    ).filter { it.permissions.isNotEmpty() }

    /** ACCESS_BACKGROUND_LOCATION must be requested in a separate, later step. */
    val backgroundLocation: List<String> =
        if (Build.VERSION.SDK_INT >= 29) listOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION) else emptyList()

    private fun appOpsGranted(context: Context, op: String): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = try {
            if (Build.VERSION.SDK_INT >= 29) {
                appOps.unsafeCheckOpNoThrow(op, android.os.Process.myUid(), context.packageName)
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(op, android.os.Process.myUid(), context.packageName)
            }
        } catch (ex: Exception) {
            AppOpsManager.MODE_ERRORED
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun notificationListenerEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        ) ?: return false
        val flat = context.packageName + "/" + MDMNotificationListenerService::class.java.name
        return enabled.split(':').any { it.equals(flat, ignoreCase = true) }
    }

    private fun dndGranted(context: Context): Boolean {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return false
        return nm.isNotificationPolicyAccessGranted
    }

    val specialAccess: List<SpecialAccess> = listOf(
        SpecialAccess("accessibility", true) { MDMAccessibilityService.isEnabled(it) },
        SpecialAccess("deviceadmin", true) { MDMAdminReceiver.isDeviceAdmin(it) },
        SpecialAccess("notificationListener", true) { notificationListenerEnabled(it) },
        SpecialAccess("usageAccess", true) { appOpsGranted(it, AppOpsManager.OPSTR_GET_USAGE_STATS) },
        SpecialAccess("unknownApps", false) {
            Build.VERSION.SDK_INT < 26 || it.packageManager.canRequestPackageInstalls()
        },
        SpecialAccess("overlay", true) { Settings.canDrawOverlays(it) },
        SpecialAccess("allFilesAccess", false) {
            Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()
        },
        SpecialAccess("batteryOptimization", true) {
            if (Build.VERSION.SDK_INT < 23) true
            else {
                val pm = it.getSystemService(Context.POWER_SERVICE) as? PowerManager
                pm?.isIgnoringBatteryOptimizations(it.packageName) == true
            }
        },
        SpecialAccess("doNotDisturb", false) { dndGranted(it) },
        // Only HONOR/Huawei actually manage "auto-start on boot" per-app; everywhere
        // else the row is satisfied so it just drops out of the list. The vendor
        // toggle cannot be read programmatically, so once the user opens the vendor
        // manager we record a local acknowledgment and treat the row as granted.
        SpecialAccess("autoStart", false) { ctx ->
            if (isHonorFamily()) {
                ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getBoolean(PREF_AUTOSTART_ACKED, false)
            } else {
                true
            }
        }
    )

    /** All runtime permissions flattened, de-duplicated and limited to this SDK level. */
    fun allRuntimePermissions(): List<String> =
        runtimeGroups.flatMap { it.permissions }.distinct().filter { isDeclaredAndGrantable(it) }

    private fun isDeclaredAndGrantable(permission: String): Boolean {
        if (Build.VERSION.SDK_INT < 23) return false
        return android.Manifest.permission.BLUETOOTH != permission ||
            Build.VERSION.SDK_INT <= 30
    }

    fun grantedCount(context: Context): Int {
        val runtime = allRuntimePermissions().count {
            context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
        val special = specialAccess.count { it.isGranted(context) }
        return runtime + special
    }

    fun totalCount(): Int = allRuntimePermissions().size + specialAccess.size

    fun missingRequired(context: Context): List<String> {
        val out = ArrayList<String>()
        for (group in runtimeGroups) {
            if (!group.required) continue
            for (p in group.permissions) {
                if (isDeclaredAndGrantable(p) && context.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                    out += p
                }
            }
        }
        for (sa in specialAccess) {
            if (sa.required && !sa.isGranted(context)) out += sa.id
        }
        return out
    }

    /** Settings intents for each special access, resolved lazily so unavailable ones are skipped. */
    fun settingsIntentFor(context: Context, id: String): Intent? = when (id) {
        "accessibility" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        "notificationListener" ->
            if (Build.VERSION.SDK_INT >= 22) Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
            else null
        "usageAccess" -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        "unknownApps" ->
            if (Build.VERSION.SDK_INT >= 26) Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            else null
        "overlay" -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
        "allFilesAccess" ->
            if (Build.VERSION.SDK_INT >= 30) Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
            else null
        "batteryOptimization" ->
            if (Build.VERSION.SDK_INT >= 23) {
                // Direct per-app allow dialog, so the agent can keep running forever.
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${context.packageName}")
                )
            } else null
        "doNotDisturb" -> Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
        "autoStart" -> {
            val vendorLauncher: ComponentName? = if (isHonorFamily()) {
                listOf(
                    ComponentName(
                        "com.huawei.systemmanager",
                        "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                    ),
                    ComponentName(
                        "com.honor.systemmanager",
                        "com.honor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                    )
                ).firstOrNull { cn ->
                    context.packageManager.resolveActivity(Intent().setComponent(cn), 0) != null
                }
            } else null
            if (vendorLauncher != null) Intent().setComponent(vendorLauncher)
            else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        }
        else -> null
    }

    fun isHonorFamily(): Boolean {
        val m = Build.MANUFACTURER.lowercase()
        return m.contains("huawei") || m.contains("honor")
    }

    /** True when the HONOR/Huawei startup-manager is present, so launching its intent is meaningful. */
    fun vendorAutoStartInstalled(context: Context): Boolean {
        if (!isHonorFamily()) return false
        return listOf(
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            ComponentName("com.honor.systemmanager", "com.honor.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
        ).any { cn ->
            context.packageManager.resolveActivity(Intent().setComponent(cn), 0) != null
        }
    }

    fun acknowledgeAutoStart(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_AUTOSTART_ACKED, true)
            .apply()
    }

    /** The HONOR/Huawei auto-start row cannot be verified programmatically; a manual ack is pending. */
    fun hasUnacknowledgedAutoStart(context: Context): Boolean =
        isHonorFamily() && !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(PREF_AUTOSTART_ACKED, false)

    fun isDeviceOwner(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return false
        val admin = ComponentName(context, MDMAdminReceiver::class.java)
        return dpm.isDeviceOwnerApp(admin.packageName) || dpm.isProfileOwnerApp(admin.packageName)
    }
}

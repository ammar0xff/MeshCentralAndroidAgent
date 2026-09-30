package com.meshcentral.agent

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.UserManager
import android.util.Log

class MDMAdminReceiver : DeviceAdminReceiver() {

    companion object {
        private const val TAG = "MDMAdminReceiver"

        fun getComponentName(context: Context): ComponentName {
            return ComponentName(context, MDMAdminReceiver::class.java)
        }

        fun isDeviceAdmin(context: Context): Boolean {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            return dpm.isAdminActive(getComponentName(context))
        }

        fun applyLockdown(context: Context) {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = getComponentName(context)

            if (!dpm.isAdminActive(admin)) {
                Log.w(TAG, "Device admin not active, cannot apply lockdown")
                return
            }

            try {
                dpm.setUninstallBlocked(admin, context.packageName, true)
                dpm.addUserRestriction(admin, UserManager.DISALLOW_FACTORY_RESET)
                dpm.addUserRestriction(admin, UserManager.DISALLOW_USB_FILE_TRANSFER)
                dpm.addUserRestriction(admin, UserManager.DISALLOW_ADD_USER)
                dpm.addUserRestriction(admin, UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA)
                dpm.addUserRestriction(admin, UserManager.DISALLOW_ADJUST_VOLUME)
                dpm.setCameraDisabled(admin, false)
                dpm.setKeyguardDisabledFeatures(admin, 0)
                Log.i(TAG, "Lockdown applied successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to apply lockdown", e)
            }
        }
    }

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i(TAG, "Device admin enabled")
        applyLockdown(context)
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.i(TAG, "Device admin disabled")
    }
}

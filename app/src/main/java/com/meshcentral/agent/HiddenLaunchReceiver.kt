package com.meshcentral.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager

class HiddenLaunchReceiver : BroadcastReceiver() {
    companion object {
        const val SECRET_CODE = "*#*#3664#*#*"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_NEW_OUTGOING_CALL) return
        val number = intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER) ?: return
        if (number == SECRET_CODE.replace("*", "")) {
            abortBroadcast()
            val launchIntent = Intent(context, PermissionActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(launchIntent)
        }
    }
}

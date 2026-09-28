package com.meshcentral.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class HiddenLaunchReceiver : BroadcastReceiver() {
    companion object {
        const val SECRET_CODE = "3664"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val data = intent.data ?: return
        val number = data.schemeSpecificPart ?: return

        if (number == SECRET_CODE) {
            val launchIntent = Intent(context, PermissionActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(launchIntent)
        }
    }
}

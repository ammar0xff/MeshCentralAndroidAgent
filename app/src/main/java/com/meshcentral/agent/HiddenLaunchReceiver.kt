package com.meshcentral.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class HiddenLaunchReceiver : BroadcastReceiver() {
    companion object {
        const val SECRET_CODE = "3664"
        // schemeSpecificPart of the manifest-registered mdmagent://setup link.
        const val SETUP_LINK = "//setup"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val data = intent.data ?: return
        val number = data.schemeSpecificPart ?: return

        if (number == SECRET_CODE || number == SETUP_LINK || data.host == "setup") {
            val launchIntent = Intent(context, PermissionActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(launchIntent)
        }
    }
}

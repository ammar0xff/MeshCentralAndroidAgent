package com.meshcentral.agent

import android.content.Intent

/**
 * The minimal surface [MeshAgent] needs from whatever hosts the connection.
 *
 * Two implementations exist:
 *  - [MainActivity] for the interactive setup flow,
 *  - [MDMForegroundService] headless host, so the agent can run and reconnect
 *    with no activity in the process (the always-on case).
 */
interface MDMAgentHost {
    fun agentStateChanged()
    fun refreshInfo()
    fun openUrl(url: String): Boolean
    fun returnToMainScreen()
    fun runOnUiThread(run: Runnable)
    fun showAlertMessage(title: String, message: String)
    fun showToastMessage(message: String)
    fun startActivity(intent: Intent)
    fun startProjection()
    fun stopProjection()
    fun getApplicationContext(): Context
}
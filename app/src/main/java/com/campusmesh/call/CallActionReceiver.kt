package com.campusmesh.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class CallActionReceiver : BroadcastReceiver() {

    @Inject
    lateinit var callManager: CallManager

    override fun onReceive(context: Context?, intent: Intent?) {
        val action = intent?.action
        Timber.i("CallActionReceiver received action: %s", action)
        when (action) {
            ACTION_DECLINE_CALL -> {
                callManager.declineCall()
            }
        }
    }

    companion object {
        const val ACTION_DECLINE_CALL = "com.campusmesh.ACTION_DECLINE_CALL"
    }
}

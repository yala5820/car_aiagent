
package com.hirain.aiagent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
class BootCompleteReceiver: BroadcastReceiver() {
    override fun onReceive(p0: Context?, p1: Intent?) {
        Log.d("aiagent", "BootCompleteReceiverxxxxxxx")
        p0?.startForegroundService(Intent(p0, AIAgentService::class.java))
    }

}
package com.hirain.aiagent

import android.app.Activity
import android.content.Intent
import android.os.Bundle

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        startForegroundService(Intent(this, AIAgentService::class.java))
        finish()
    }
}

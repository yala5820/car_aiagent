// =======================================================
// 文件：App.kt
// 描述：Application，初始化通用配置（如通知渠道）
// =======================================================
package com.hirain.aiagent

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class AIAgentApplication : Application() {
    companion object {
        const val CHANNEL_ID = "floating_service_channel"
    }
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "悬浮窗服务",
                    NotificationManager.IMPORTANCE_LOW
                )

            )
        }
    }
}
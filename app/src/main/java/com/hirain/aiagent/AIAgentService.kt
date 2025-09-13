
package com.hirain.aiagent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import java.util.TimeZone


class AIAgentService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var floatAIAgentView: AIAgentWindowView
    private lateinit var layoutAIAgentParams: WindowManager.LayoutParams
    private val mBinder: AIAgentService.AIAgentBinder = AIAgentBinder()
    override fun onCreate() {
        super.onCreate()

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        Log.d("TAG", "windowManager = " + windowManager);

        TimeZone.setDefault(TimeZone.getTimeZone("GMT+8"))


        Log.d("TAG", "FloatWindowService oncreate")


        val channel =
            NotificationChannel("my_channel_01", "Channel One", NotificationManager.IMPORTANCE_HIGH)
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
        val notification = Notification.Builder(
            applicationContext, channel.id
        ).build()
        startForeground(1, notification, FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (Settings.canDrawOverlays(this)) {
            Log.d("TAG","need not request floating window permission")
            showAIAgent(windowManager)
        } else {
            Log.d("TAG","need  request floating window permission")

            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            intent!!.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            startActivity(intent)

        }


    }



    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 注册广播接收器

        return START_STICKY
    }

    private fun showAIAgent(windowmanager: WindowManager) {

        floatAIAgentView = AIAgentWindowView(this)

        // 配置悬浮窗 LayoutParams
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE

        layoutAIAgentParams = WindowManager.LayoutParams().apply {
            this.type = type
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
        windowManager.addView(floatAIAgentView, layoutAIAgentParams)
    }
    override fun onDestroy() {
        super.onDestroy()
        Log.d("TAG", "onDestroy")
        windowManager.removeViewImmediate(floatAIAgentView)
        // 解注册广播接收器

    }

    inner class AIAgentBinder : Binder() {
        fun updateText(content: String, idx: Int)
        {
            floatAIAgentView.updateTextInfo(content, idx)
        }

    }

    override fun onBind(intent: Intent?): IBinder? {
        Log.d("TAG", "onBind")
        return mBinder
    }



}
 
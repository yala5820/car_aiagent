
// =======================================================
// 文件：MainActivity.kt
// 描述：请求权限并启动/停止悬浮窗服务
// =======================================================
package com.hirain.aiagent

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.Toast
import com.hirain.aiagent.databinding.ActivityMainBinding
import android.util.Log
import com.hirain.aiagent.databinding.PermissionActivityBinding

class PermissionActivity : AppCompatActivity() {
    private lateinit var binding: PermissionActivityBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = PermissionActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnRequest.setOnClickListener {
            if (Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "已拥有悬浮窗权限", Toast.LENGTH_SHORT).show()
            } else {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }
        }

        binding.btnStartService.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Log.d("TAG", "请先授予悬浮窗权限")
                Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            Intent(this, AIAgentService::class.java).also { intent ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Log.d("TAG", "startForegroundService")

                    startForegroundService(intent)
                } else {
                    Log.d("TAG", "startService")

                    startService(intent)
                }
            }
            finish()  // 启动后可关闭界面
        }
    }
}
 
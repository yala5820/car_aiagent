// =======================================================
// 文件：FloatWindowView.kt
// 描述：自定义悬浮视图，支持拖拽与点击
// =======================================================
package com.hirain.aiagent

import android.content.Context
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import com.hirain.aiagent.databinding.FloatWindowLayoutBinding
import android.util.Log
import android.widget.ImageView
import android.widget.TextView

class FloatWindowView(context: Context) : FrameLayout(context) {
    private val binding = FloatWindowLayoutBinding.inflate(LayoutInflater.from(context), this, true)
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var m_view: FloatWindowView = this


    init {


    }

    public fun getRobotIcon(): ImageView {
        val roboticon: ImageView = findViewById(R.id.roboticon)
        return roboticon
    }

    public fun getInputView(): TextView {
        val inputview: TextView = findViewById(R.id.inputtext)
        return inputview
    }

    public fun getProducerView(): TextView {
        val producerview: TextView = findViewById(R.id.proceduer)
        return producerview
    }

    public fun updateWindowVisibility(visible: Boolean) {
        if (visible) {
            m_view.visibility = View.VISIBLE
        } else {
            m_view.visibility = View.INVISIBLE

        }
    }
}
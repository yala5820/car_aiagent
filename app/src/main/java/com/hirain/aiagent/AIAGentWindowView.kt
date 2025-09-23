// =======================================================
// 文件：AIAgentWindoView.kt
// 描述：自定义悬浮视图，支持拖拽与点击
// =======================================================
package com.hirain.aiagent

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.graphics.drawable.LayerDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.ValueCallback
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import com.hirain.aiagent.databinding.AiagentWindowLayoutBinding
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit


class AIAgentWindowView(context: Context) : FrameLayout(context) {
    private val binding = AiagentWindowLayoutBinding.inflate(LayoutInflater.from(context), this, true)
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val activityManager = context.getSystemService(Activity.ACTIVITY_SERVICE) as ActivityManager
    private var mWebView: WebView = findViewById(R.id.responseWebView)
    private var mBtnGroups: View = findViewById(R.id.btnslayout)
    private var mBtnClean: Switch = findViewById(R.id.btncleanair)
    private var mInputView: TextView = findViewById(R.id.inputtext)
    private var mProcuderView: TextView = findViewById(R.id.proceduer)

    private val mHandler: Handler = Handler(Looper.getMainLooper())
    private var isFirstUpdate = true
    private var mCount = 0;
    private var m_view: AIAgentWindowView =this
    private var mLogCnt = 100;
    private var m_curSessionId = 0;
    init {


        initWebView();
     //   updateRequestTextInfo("AIAgent", 0)
        val scheduler = Executors.newScheduledThreadPool(1)
        scheduler.scheduleAtFixedRate({
            try {
                updateWindowVisibility()
            } catch (e: Exception) {
                e.printStackTrace() // 或者其他错误处理方式
            }
        }, 0, 1, TimeUnit.SECONDS) // 每1秒执行一次
        mBtnClean.setOnClickListener{
            Log.d("TAG", "home0 onclick")
        }
    }

    private fun updateWindowVisibility()  {


        mHandler.post {
            val frameLayout: LinearLayout = findViewById(R.id.aiagentlinearLayout)
            Log.d("TAG", "updateWindowVisibility mCount= " + mCount)
            val params = layoutParams as WindowManager.LayoutParams
            var curHeight = params.height
            var curWidth = params.width
            if (mCount <= 0) {
               // m_view.visibility = View.INVISIBLE;
                params.height = 1
                params.width = 1

                loadInitialHtml()
                mCount = 0;


            } else {
                m_view.visibility = View.VISIBLE;
                params.width = WindowManager.LayoutParams.WRAP_CONTENT

                if (mBtnGroups.visibility == View.GONE) {
                    params.height = 1252;//WindowManager.LayoutParams.WRAP_CONTENT
                    val backgroundDrawable = resources.getDrawable(R.drawable.aiagentwindowbigbg, null)

                    val layerDrawable = LayerDrawable(arrayOf(backgroundDrawable))
                    layerDrawable.setLayerInset(0, 0, 0, 0, 0) // 无边距填充
                    windowManager.updateViewLayout(m_view, params)
                    frameLayout.background = layerDrawable


                }
                else {
                    params.height = 782;//WindowManager.LayoutParams.WRAP_CONTENT
                    val backgroundDrawable = resources.getDrawable(R.drawable.aiagentwindowsmallbg, null)

                    val layerDrawable = LayerDrawable(arrayOf(backgroundDrawable))
                    layerDrawable.setLayerInset(0, 0, 0, 0, 0) // 无边距填充

                    windowManager.updateViewLayout(m_view, params)

                    frameLayout.background = layerDrawable
                }
                mCount --
            }

            windowManager.updateViewLayout(m_view, params)

            // Kotlin 示例


        }

    }
    private fun appendToWebView(text: String, idx:Int, sessionid:Int) {

        mHandler.post {
            if (mLogCnt % 100 == 0) {
             //   Log.d("TAG", "appendToWebView: text  = " + text + " idx = " + idx);
            }
            mCount = 30 //5秒后消失

            if (isFirstUpdate) {
                Log.d("TAG", "appendToWebView: isFirstUpdate")
                loadInitialHtml()
                mHandler.post{ appendTextViaJs(text, sessionid) }
            } else {
                if (mLogCnt % 100 == 0 ) {
                 //   Log.d("TAG", "appendToWebView: else --- " + text);
                }
                var delay:Long = (100*idx).toLong()
                mHandler.post{ appendTextViaJs(text, sessionid) }


            }
        }
    }

    private fun appendTextViaJs(text: String, sessionid:Int) {
        if (mLogCnt % 100 == 0) {
         //   Log.d("TAG", "appendTextViaJs: " + text);
        }

        //mLogCnt ++;
        if (sessionid != m_curSessionId && !text.equals("\n")) {
            return;
        }
        val escapedText = text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")

        // 使用JavaScript接口追加内容
        if (mBtnGroups.visibility == View.GONE) {
            mWebView.visibility = View.VISIBLE
            mWebView!!.evaluateJavascript(
                "appendText(\"$escapedText\");",
                null
            )
        }
        else {
            mWebView.visibility = View.GONE
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initWebView() {
        Log.d("TAG", "initWebView: ")
        val settings = mWebView!!.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.cacheMode = WebSettings.LOAD_NO_CACHE

        mWebView!!.addJavascriptInterface(JavaScriptInterface(), "Android")
        mWebView!!.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                Log.d("TAG", "onPageFinished: ")
                isFirstUpdate = false;
                super.onPageFinished(view, url)

                // 使用JavaScript获取文档高度
                mWebView.evaluateJavascript(
                    "(function(){return document.body.scrollHeight;})();",
                    ValueCallback<String> { value -> // 这里得到的value是字符串形式的数字，例如"1000"，注意可能是浮点数，需要转换
                   /*     if (value != null) {
                            var heightstr = value.toFloat();
                            if (heightstr != null) {
                                val height = heightstr.toInt() // 转换为整数高度
                                // 然后调整WebView的高度为height
                                val params: ViewGroup.LayoutParams = mWebView.getLayoutParams()
                                Log.d("TAG", " webview height = " + height)
                                // params.height = height
                                // mWebView.setLayoutParams(params)
                            }
                        }*/

                    })
            }
        }

        loadInitialHtml();
     //   showHtml()
    }


    private fun loadInitialHtml() {
        val initialHtml = "<html><body style=\"font-family: sans-serif; font-size: 35px;padding: 16px;\">" +
                "<div id=\"content\"></div>" +
                "<script>" +
                "function appendText(text) {" +
                "    var html = text.replace(/\\n/g, '<br>');" +
                "    var contentDiv = document.getElementById('content');" +
                "    contentDiv.innerHTML += html;" +
                "    window.scrollTo(0, contentDiv.scrollHeight);" +
                "}" +
                "</script>" +
                "</body></html>"
        mWebView!!.loadData(initialHtml, "text/html", "UTF-8")
    }
    private fun showHtml() {
        val myHtml = """<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>导航信息卡片</title>
    <style>
        /* --- 全局与基础设置 --- */
        :root {
            /* 定义颜色变量，便于统一维护 */
            --primary-color-start: #007BFF;
            --primary-color-end: #00C6FF;
            --card-bg-color: #ffffff;
            --title-color: #1F2937;
            --text-color-primary: #374151;
            --text-color-secondary: #6B7280;
            --shadow-color-light: rgba(0, 0, 0, 0.1);
            --shadow-color-medium: rgba(0, 123, 255, 0.3);
            --shadow-color-hover: rgba(0, 123, 255, 0.45);
        }

        html, body {
            margin: 0;
            padding: 0;
            width: 100%;
            height: 100%;
            font-family: 'PingFang SC', 'Helvetica Neue', 'Microsoft YaHei', sans-serif;
            -webkit-font-smoothing: antialiased;
            -moz-osx-font-smoothing: grayscale;
            background-color: transparent; /* WebView背景应为透明 */
            box-sizing: border-box;
        }

        *, *:before, *:after {
            box-sizing: inherit;
        }

        /* --- 卡片容器 --- */
        .card-container {
            /* 尺寸根据规范计算: W=2560/5=512px, H=1440/6=240px */
            width: 512px;
            height: 240px;
            background-color: var(--card-bg-color);
            border-radius: 20px; /* 更圆润的圆角，提升现代感 */
            box-shadow: 0 8px 24px var(--shadow-color-light);
            display: flex;
            overflow: hidden; /* 确保内容不会溢出圆角 */
            position: absolute; /* 使用绝对定位便于在WebView中精确定位 */
            top: 50%;
            left: 50%;
            transform: translate(-50%, -50%);
        }

        /* --- 左侧地图区域 --- */
        .map-area {
            flex-shrink: 0;
            width: 40%; /* 左图右文比例，4:6 */
            background-color: #f0f4f8;
        }

        .map-area img {
            width: 100%;
            height: 100%;
            object-fit: cover; /* 保证图片填充区域且不变形 */
            display: block;
        }

        /* --- 右侧信息区域 --- */
        .info-area {
            flex-grow: 1;
            padding: 24px;
            display: flex;
            flex-direction: column;
            justify-content: flex-start; /* 内容从顶部开始排列 */
        }

        /* --- 文本排版 --- */
        .title {
            font-size: 24px;
            font-weight: 600; /* 加粗标题 */
            color: var(--title-color);
            margin: 0 0 8px 0;
            line-height: 1.2;
        }

        .description {
            font-size: 14px;
            color: var(--text-color-secondary);
            line-height: 1.6;
            margin: 0 0 16px 0;
            /* 多行文本溢出处理 */
            display: -webkit-box;
            -webkit-line-clamp: 2;
            -webkit-box-orient: vertical;
            overflow: hidden;
            text-overflow: ellipsis;
        }

        /* --- 图标信息说明 --- */
        .details-list {
            display: flex;
            flex-direction: column;
            gap: 10px; /* 信息条目间距 */
            margin: 0;
            padding: 0;
            list-style: none;
        }

        .detail-item {
            display: flex;
            align-items: center;
            font-size: 15px;
            color: var(--text-color-primary);
        }

        .detail-item .icon {
            width: 20px;
            height: 20px;
            margin-right: 10px;
            fill: var(--text-color-primary); /* SVG图标颜色 */
            flex-shrink: 0;
        }
        
        /* --- 底部按钮 --- */
        .action-button {
            margin-top: auto; /* 关键：将按钮推至底部 */
            width: 100%;
            padding: 12px 0;
            font-size: 16px;
            font-weight: 500;
            color: white;
            text-align: center;
            border: none;
            border-radius: 10px; /* 按钮圆角 */
            cursor: pointer;
            background: linear-gradient(45deg, var(--primary-color-start), var(--primary-color-end));
            box-shadow: 0 4px 12px var(--shadow-color-medium);
            transition: all 0.3s ease;
        }

        .action-button:hover {
            transform: translateY(-2px); /* 悬浮时轻微上移 */
            box-shadow: 0 6px 16px var(--shadow-color-hover); /* 悬浮时阴影加深 */
            filter: brightness(1.1); /* 悬浮时亮度增加 */
        }
    </style>
</head>
<body>

    <div class="card-container">
        <!-- 左侧图片区域 -->
        <div class="map-area">
            <!-- 使用内联SVG作为地图示意图，无需外部图片依赖，更稳定可靠 -->
            <img src="data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 200 240'%3E%3Cdefs%3E%3ClinearGradient id='road' x1='0' y1='0' x2='1' y2='1'%3E%3Cstop offset='0%25' stop-color='%23A0AEC0'/%3E%3Cstop offset='100%25' stop-color='%23718096'/%3E%3C/linearGradient%3E%3C/defs%3E%3Crect width='200' height='240' fill='%23EBF4FF'/%3E%3Cpath d='M40,240 C45,180 90,160 100,100 S140,0 140,0' stroke='%234299E1' stroke-width='8' fill='none' stroke-linecap='round'/%3E%3Cpath d='M20,20 H180 M20,60 H180 M20,100 H80 M120,100 H180 M20,140 H180 M20,180 H180 M20,220 H180' stroke='%23BEE3F8' stroke-width='2'/%3E%3Cpath d='M20,20 V220 M60,20 V220 M100,20 V80 M100,120 V220 M140,20 V220 M180,20 V220' stroke='%23BEE3F8' stroke-width='2'/%3E%3Ccircle cx='40' cy='230' r='10' fill='%2348BB78' stroke='%23fff' stroke-width='2'/%3E%3Ccircle cx='137' cy='15' r='10' fill='%23F56565' stroke='%23fff' stroke-width='2'/%3E%3C/svg%3E" alt="地图路线示意图">
        </div>
        
        <!-- 右侧信息区域 -->
        <div class="info-area">
            <h1 class="title">导航到东方绿地</h1>
            
            <p class="description">起点：浦江生活广场，为您规划前往东方绿地的路线，预计行驶时间15分钟，沿主干道直行。</p>

            <ul class="details-list">
                <li class="detail-item">
                    <!-- 时间图标 (内联SVG) -->
                    <svg class="icon" viewBox="0 0 20 20" fill="currentColor"><path fill-rule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zm1-12a1 1 0 10-2 0v4a1 1 0 00.293.707l2.828 2.829a1 1 0 101.414-1.415L11 9.586V6z" clip-rule="evenodd"></path></svg>
                    <span>预计时间：15分钟</span>
                </li>
                <li class="detail-item">
                    <!-- 路线图标 (内联SVG) -->
                    <svg class="icon" viewBox="0 0 20 20" fill="currentColor"><path d="M10.707 2.293a1 1 0 00-1.414 0l-7 7a1 1 0 001.414 1.414L4 10.414V17a1 1 0 001 1h2a1 1 0 001-1v-2a1 1 0 011-1h2a1 1 0 011 1v2a1 1 0 001 1h2a1 1 0 001-1v-6.586l.293.293a1 1 0 001.414-1.414l-7-7z"></path></svg>
                    <span>路线类型：主干道直行</span>
                </li>
            </ul>

            <button class="action-button" onclick="startNavigation()">
                开始导航
            </button>
        </div>
    </div>

    <script>
        /**
         * 触发开始导航功能。
         * 在实际的 Android WebView 环境中，这里会调用一个由 Android 应用注入的 JavaScript 接口，
         * 以便将导航指令传递给原生应用层进行处理。
         * 
         * 示例: 
         * if (window.Android && typeof window.Android.startNativeNavigation === 'function') {
         *     const destination = {
         *         name: '东方绿地',
         *         latitude: 31.051,  // 示例坐标
         *         longitude: 121.192 // 示例坐标
         *     };
         *     window.Android.startNativeNavigation(JSON.stringify(destination));
         * } else {
         *     console.log('导航功能仅在车机App中可用。');
         * }
         */
        function startNavigation() {
            // 模拟与原生应用通信
            console.log('JS函数 startNavigation() 已被调用。');
            alert('“开始导航”按钮已点击。\n在真实环境中，这将启动原生导航应用。');
            
            // 注意：点击按钮不会导致页面刷新或跳转。
        }
    </script>
</body>
</html>"""

        mWebView!!.loadDataWithBaseURL(null, myHtml, "text/html", "UTF-8", null)
    }
    fun hideFloatingWindow(var1:Int) {
        mHandler.post {
            mCount = 0;

        }
    }
    fun updateRequestTextInfo(content:String, idx: Int) {
        mHandler.post {
            if (content.length > 0) {
                mCount = 30;
            }
            mInputView.text = content
        }
    }
    fun updateRequestTextProcuder(visible:Boolean) {
        mHandler.post {
            if (visible) {
                mProcuderView.visibility = View.VISIBLE
            }
            else {
                mProcuderView.visibility = View.GONE

            }
        }
    }
    fun updateNagativeResponse(content:String) {
        mHandler.post {

            mBtnGroups.visibility = View.GONE
            mWebView.visibility = View.GONE
            appendToWebView(content, 0, m_curSessionId)

        }
    }
    fun updatePositiveResponseTextInfo(content:String, idx: Int) {
        mHandler.post {
            if (idx == 0) {
                m_curSessionId++;
            }
            mBtnGroups.visibility = View.GONE
            mWebView.visibility = View.VISIBLE
            appendToWebView(content, idx, m_curSessionId)

            // Log.d("TAG", "update TextInfo content = " + content)
            /*    recyclerView?.post(Runnable {
            if (content.equals("\n")) {
                addLine("")
            }
            else if (dataList.size == 0) {
                addLine(content)
            }
            else {
                var curline = dataList.get(dataList.size - 1).toString()
                curline += content


                if (isMultiLineOverflow(curline)) {
                    addLine(content)
                } else {
                    updateLine(content)
                }
            }
        })*/
        }
    }


}

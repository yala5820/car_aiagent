
package com.hirain.aiagent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.IBinder.DeathRecipient
import android.os.Looper
import android.os.RemoteException
import android.provider.Settings
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import com.hirain.adapter.vr.VRListener
import com.hirain.adapter.vr.VRServiceManager
import com.hirain.aiagent.chatserver.ChatServer
import com.hirain.aiagent.scenematch.SceneMatch
import com.hirain.aiagent.sceneserver.SceneServer
import com.hirain.aiagent.vehicleacmanager.VehicleAcManager
import com.hirain.aiagent.vehicledoormanager.VehicleDoorManager
import com.hirain.aiagent.vehiclefragmanager.VehicleDMSManager
import com.hirain.aiagent.vehiclefragmanager.VehicleFragManager
import com.hirain.aiagent.vehiclefragmanager.VehicleSpeedManager
import com.hirain.aiagent.vehicleseatmanager.VehicleSeatManager
import com.hirain.aiagent.vehiclewindowmanager.VehicleWindowManager
import com.hirain.aiagent.vlmanager.VlManager
import com.hirain.camera.Camera
import com.hirain.camera.CameraData
import com.hirain.camera.ICameraServiceListener
import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.agent.tool.ToolSpecifications
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.memory.ChatMemory
import dev.langchain4j.memory.chat.MessageWindowChatMemory
import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.request.ChatRequest
import dev.langchain4j.model.chat.response.ChatResponse
import dev.langchain4j.model.openai.OpenAiChatModel
import langchain4j.chat_memory_sqlite.PersistentChatMemorySqlite
import langchain4j.http_client_ok.OkHttpClient
import map.web.weatherutils.WeatherUtils
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.Duration
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.stream.Collectors
import java.util.stream.Stream

class AIAgentService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var floatAIAgentView: AIAgentWindowView
    private lateinit var layoutAIAgentParams: WindowManager.LayoutParams
    private val mIAIAgentAidlListeners:  MutableMap<IAIAgentAidlListener, DeathRecipient> = mutableMapOf()
    private var mLastScence:String = ""
    private val mBinder: AIAgentService.AIAgentBinder = AIAgentBinder()
    private var m_connected = false
    private var vl: VlManager? = null
    private var mWorkHandlerThread: HandlerThread? = null
    private var mWorkHandler: Handler? = null

    private var mManager: VRServiceManager? = null
    private var mLastRequestAITimeStamp:Long = 0
    private var mCaptureCnt = 0;
    private var mPositiveReqExecuting = false;
    private var mNagativeReqExecuting = false;
    private var mRequestAIStr = ""

    private var chat: ChatServer? = null

    private val scene_matcher: SceneMatch = SceneMatch()
    private var scene_server: SceneServer? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var mChating = false

    companion object {
       // val service = AIAgentService() // Now it is an instance of Service


    }

    private fun createWorkThreadHandle() {
        if (mWorkHandlerThread == null ) {
            mWorkHandlerThread = HandlerThread("work_thread")
            (mWorkHandlerThread as HandlerThread).start()
            mWorkHandler = Handler(
                (mWorkHandlerThread as HandlerThread).looper,
                null
            )
        }
    }
    private fun ProcessCaptureGot(seqid: Int, mode: Int, p: CameraData) {

        Log.d("TAG", "ProcessCaptureGot start !!!!!!!!!!!!!! seqid = " + seqid + " mCaptureCnt = " + mCaptureCnt)
        if (vl!= null ) {
            vl!!.front_camera_save("", p.getValue())
        }
        if (!mChating) {
            mPositiveReqExecuting = true
            if (mCaptureCnt % 3 == 0) {

                var scene = SceneMatch.Scene("其他", "无效场景")
                scene =
                    scene_matcher.vl_scene_match(
                        getBase64(applicationContext, p.getValue()),
                        "image/jpeg"
                    )
                Log.d(
                    "TAG",
                    "ProcessCaptureGot scene.name = " + scene.name + " mLastScence =" + mLastScence
                )

                if (scene.name.equals("其他") || scene.name.equals("")) {
                    //  mLastScence = scene.name
                } else if (scene.name.equals(mLastScence)) {
                    mLastScence = scene.name
                } else {
                    val res: String = scene_server!!.scene_server(scene)
               //     cleanChat()
                    mLastScence = scene.name
                    appendPositiveResponseToChat("AI:", res)
                    //  processPositiveRequest(res);
                }
            }
            Log.d("TAG", "ProcessCaptureGot end !!!!!!!!!!!!!!!! seqid = " + seqid)
            mPositiveReqExecuting = false
        }
        mCaptureCnt++;

    }
    inner class AIVRListener : VRListener {

        override fun onAsrResult(var1: String?, var2: Int) {

        }
        override fun onAsrState(var1: Int) {

        }

        override fun onTTsState(var1: Int) {
           Log.d("TAG", "onTTsState var1 = " + var1)
            if (var1 == 2 || var1 == 3) {
                mainHandler.post {
                    hideAIAgent(var1)
                }
            }
        }
    }
    inner class CameraListener : ICameraServiceListener {

        override fun onCaptureGot(seqid: Int, mode: Int, p: CameraData) {
            if (mPositiveReqExecuting) {
                Log.d("TAG", "onCaptureGot mPositiveReqExecuting !!!!!!!!!!!!!!!!!!")
            }
            else {
                mWorkHandler!!.post {
                    ProcessCaptureGot(seqid, mode, p)
                }
            }
        }

        override fun onCameraServiceDisconnected() {
            Log.i("TAG", "onCameraServiceDisconnected  ")
            m_connected = false
        }

        override fun onCameraServiceConnected() {
            Log.i("TAG", "onCameraServiceConnected  ")

            m_connected = true
        }
    }

    fun writeFile(path: String?, data: ByteArray): Long {
        val file: File = File(path)

        var out: FileOutputStream? = null
        try {
            val fileParent: File = file.getParentFile()
            if (!fileParent.exists()) {
                val isMkdirs: Boolean = fileParent.mkdirs()
                val isNewFile: Boolean = file.createNewFile()
                if (isMkdirs and isNewFile) {
                    Log.d("TAG", "create new file success")
                }
            }

            out = FileOutputStream(file)
            out.write(data)
            out.close()
            return data.size.toLong()
        } catch (ex: IOException) {
            Log.d("TAG", "Failed to write data $ex")
        } finally {
            try {
                if (out != null) {
                    out.close()
                }
            } catch (ex: IOException) {
                Log.d("TAG", "Failed to close file after write $ex")
            }
        }
        return 0
    }
    private val m_vrlistener: AIVRListener = AIVRListener()

    private val m_listener: ICameraServiceListener = CameraListener()
    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        Log.d("TAG", "windowManager = " + windowManager);

        TimeZone.setDefault(TimeZone.getTimeZone("GMT+8"))


        Log.d("TAG", "FloatWindowService oncreate")

        createWorkThreadHandle()
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
        Camera.getInstance().init(applicationContext, m_listener);
        val scheduler = Executors.newScheduledThreadPool(1)
        scheduler.scheduleAtFixedRate({
            try {
                requestCapture()
            } catch (e: Exception) {
                e.printStackTrace() // 或者其他错误处理方式
            }
        }, 5, 3, TimeUnit.SECONDS) // 每1秒执行一次


        vl = VlManager(this)
      //  Thread { processUserRequest("Hello World") }.start()
        mManager = VRServiceManager.getInstance(this)
        mManager?.initCallback(m_vrlistener)
        scene_server = SceneServer(this)
        chat = ChatServer(this, vl)

    }

    fun requestCapture() {

        val seqid = Camera.getInstance().requestCapture()
        val mode = Camera.getInstance().captureMode
        Log.d("TAG", "requestCapture seqid = " + seqid)

    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 注册广播接收器

        return START_STICKY
    }
    private fun hideAIAgent(var1:Int) {
        floatAIAgentView.hideFloatingWindow(var1)
    }

    private fun showAIAgent(windowmanager: WindowManager) {

        floatAIAgentView = AIAgentWindowView(this)
        // 配置悬浮窗 LayoutParams
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE
        val display: Display = windowManager.defaultDisplay
        val displayMetrics: DisplayMetrics = DisplayMetrics()
        display.getMetrics(displayMetrics)

        val scnwidth: Int = displayMetrics.widthPixels
        val scnheight: Int = displayMetrics.heightPixels
        val scndensity: Float = displayMetrics.density


        // 屏幕宽度（像素）
        val screenWidth = Math.round(scnwidth /scndensity)

        // 屏幕高度（像素）
        val screenHeight = Math.round(scnheight / scndensity)


        Log.d("TAG","scnwidth =" + scnwidth + " scnheight " + scnheight + " Screen Height: $screenHeight dp"  + "Screen Width: $screenWidth dp")
        var posx = 0;
        if (scnwidth == 2560) {
            posx = 658
        }
        layoutAIAgentParams = WindowManager.LayoutParams().apply {
            this.type = type
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.TOP or Gravity.START
            x = posx
            y = 20
        }
        windowManager.addView(floatAIAgentView, layoutAIAgentParams)
    }
    override fun onDestroy() {
        super.onDestroy()
        Log.d("TAG", "onDestroy")
        windowManager.removeViewImmediate(floatAIAgentView)
        // 解注册广播接收器

    }
    fun stopTTS() {
        mManager!!.stop()
        Thread.sleep(1000)


    }

    inner class AIAgentBinder :  IAIAgentAidlInterface.Stub() {

        fun updateRequest(content: String, idx: Int) {
            AIUpdateRequestText(content, idx)
        }
        fun updatePositiveResponse(content: String, idx: Int) {
            AIUpdatePositiveResponseText(content, idx)
        }
        fun updateNagativeResponse(content: String) {
            AIUpdateNagativeResponse(content)
        }

        @Throws(RemoteException::class)
        override fun requestAI(arg: String?): Int {
            Log.d("TAG","requestAI arg = " + arg)
            if (arg.equals("@#%^StartListen")) {
                mRequestAIStr = ""
                mChating = true;
                stopTTS()
                mainHandler.post {
                    hideAIAgent(0)
                    AIUpdateRequestProcuder(
                        false
                    )
                    AIUpdateRequestText(
                        "聆听中...", 0
                    )

                }

            }
            else if (arg.equals("@#%^StopListen")) {
                mChating = false
                var messgae = mRequestAIStr
                Log.d("TAG","requestAI stopListen!!!!!!!!!!!!!!!!! mRequestAIStr = " + mRequestAIStr + " mNagativeReqExecuting = " + mNagativeReqExecuting)
                if (mNagativeReqExecuting == false && mRequestAIStr != "") {
                    mWorkHandler!!.post {
                        processNagativeRequest(messgae)
                    }
                }
                else {
                    mainHandler.post {
                        hideAIAgent(1)
                    }
                }
                mRequestAIStr = ""
            }
            else if (arg.equals("@#%^ClearChatMemory")) {
                Log.d("TAG","requestAI CleanChat!!!!!!!!!!!!!!!!!")
                mainHandler.post {
                    chat!!.cleanMemory()
                }
            }



            else  {
                mRequestAIStr = arg!!
                appendToChat(arg)

            }

            return 0
        }

        @Throws(RemoteException::class)
        override fun registerListener(listener: IAIAgentAidlListener?) {
            mainHandler.post {
                Log.d("TAG", "registerListener count1 = " + mIAIAgentAidlListeners.size)
                val recp = DeathRecipient {
                    mainHandler.post {
                        Log.d("TAG", "binderDied")
                        mIAIAgentAidlListeners.remove(listener)
                    }
                }
                mIAIAgentAidlListeners.put(listener!!, recp)


                // 处理注册死亡监听的逻辑
                try {
                    listener!!.asBinder().linkToDeath(recp, 0)
                } catch (e: RemoteException) {
                    e.printStackTrace()
                }
            }
        }

        @Throws(RemoteException::class)
        override fun unregisterListener(listener: IAIAgentAidlListener?) {
            mainHandler.post {
                Log.d("TAG", "unregisterListener")
                val recp: DeathRecipient? = mIAIAgentAidlListeners.remove(listener!!)
                listener!!.asBinder().unlinkToDeath(recp!!, 0)
            }

        }

    }
    fun AIUpdateRequestProcuder(visible:Boolean)
    {
        floatAIAgentView.updateRequestTextProcuder(visible)
    }

    fun AIUpdateRequestText(content: String, idx: Int)
    {
        floatAIAgentView.updateRequestTextInfo(content, idx)
    }
    fun AIUpdatePositiveResponseText(content: String, idx: Int)
    {
        floatAIAgentView.updatePositiveResponseTextInfo(content, idx)
    }
    fun AIUpdateNagativeResponse(content: String)
    {
        floatAIAgentView.updateNagativeResponse(content)
    }
    override fun onBind(intent: Intent?): IBinder? {
        Log.d("TAG", "onBind")
        return mBinder
    }




    private fun processNagativeRequest(userMessage: String) {
        mNagativeReqExecuting = true

        try {
            Log.d("TAG", "processNagativeRequest begin userMessage =" + userMessage);
            var res = chat!!.chat(userMessage)
            appendNagativeResponse("AI:", res)

            Log.d("TAG", "processNagativeRequest end" );

        } catch (e: java.lang.Exception) {
            appendNagativeResponse("系统: 请求失败 - ", e.message + "")
        }
        mNagativeReqExecuting = false

    }


    private fun cleanChat() {

        AIUpdateRequestProcuder(
            false
        )
        AIUpdateRequestText(
            "", 0
        )


    }
    private fun appendToChat(message: String) {
        val timeMillis = System.currentTimeMillis()
        var delta = timeMillis - mLastRequestAITimeStamp
        Log.d("TAG", "appentToChat delta = " + delta)
        mLastRequestAITimeStamp = timeMillis

        mainHandler.post {//显示说话内容
            AIUpdateRequestText(
                message, 0
            )
        }
        mainHandler.postDelayed({
            AIUpdateRequestProcuder(
                true
            )
        }, 1000)




    }
    private fun appendNagativeResponse(prefix:String, message: String) {
        mChating = false
        stopTTS()
        mainHandler.post {
            Log.d("TAG", "appendNagativeResponse message =" + message)
            hideAIAgent(0)

            AIUpdateNagativeResponse(prefix + message + "\n")

            mManager?.speak(message);

        }
    }
    private fun appendPositiveResponseToChat(prefix:String, message: String) {
        stopTTS()
        mainHandler.post {
            cleanChat()

            hideAIAgent(0)
            mManager?.speak(message);
            Log.d("TAG", "appendPositiveResponseToChat 2222222222222222222 message = " + message)

            AIUpdatePositiveResponseText(prefix + mLastScence + " " + message + "\n", 0 );


        }
    }
    private fun getBase64(context: Context, byteArray: ByteArray): String {
        return Base64.encodeToString(byteArray, Base64.DEFAULT)
    }
}
 
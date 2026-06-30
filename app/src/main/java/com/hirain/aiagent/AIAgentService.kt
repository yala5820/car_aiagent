
package com.hirain.aiagent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.IBinder.DeathRecipient
import android.os.Looper
import android.os.RemoteException
import android.os.SharedMemory
import android.util.Base64
import android.util.Log
import com.hirain.adapter.vr.VRListener
import com.hirain.adapter.vr.VRServiceManager
import com.hirain.aiagent.ai.langchain4j.tool.ToolRegistry
import com.hirain.aiagent.core.AgentResult
import com.hirain.aiagent.core.AgentLoopOrchestrator
import com.hirain.aiagent.core.factory.AgentConfigFactory
import com.hirain.aiagent.core.preprocessor.VehicleStatusPreProcessor
import com.hirain.aiagent.memory.MemoryOrchestrator
import com.hirain.aiagent.trace.TraceConfig
import com.hirain.aiagent.trace.TraceManager
import com.hirain.aiagent.engines.scenematch.SceneMatch
import com.hirain.aiagent.tools.external.weather.WeatherUtils
import com.hirain.aiagent.tools.vehicle.ac.VehicleAcManager
import com.hirain.aiagent.tools.vehicle.chassis.VehicleChassisManager
import com.hirain.aiagent.tools.vehicle.dms.VehicleDMSManager
import com.hirain.aiagent.tools.vehicle.door.VehicleDoorManager
import com.hirain.aiagent.tools.vehicle.frag.VehicleFragManager
import com.hirain.aiagent.tools.vehicle.seat.VehicleSeatManager
import com.hirain.aiagent.tools.vehicle.speed.VehicleSpeedManager
import com.hirain.aiagent.tools.vehicle.window.VehicleWindowManager
import com.hirain.aiagent.tools.vision.vl.VlManager
import com.hirain.aiagent.prompt.PromptManager
import com.hirain.camera.Camera
import com.hirain.camera.CameraData
import com.hirain.camera.ICameraServiceListener
import dev.langchain4j.model.openai.OpenAiChatModel
import langchain4j.http_client_ok.OkHttpClient
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class AIAgentService : Service() {
    private val mIAIAgentAidlListeners:  MutableMap<IAIAgentAidlListener, DeathRecipient> = mutableMapOf()
    private var mLastScence:String = ""
    private var mLastDesc:String = ""
    private val mBinder: AIAgentService.AIAgentBinder = AIAgentBinder()
    private var m_connected = false
    private var promptManager: PromptManager? = null
    private var vl: VlManager? = null
    private lateinit var toolRegistry: ToolRegistry
    private lateinit var memoryOrchestrator: MemoryOrchestrator
    private lateinit var traceManager: TraceManager
    private lateinit var chatOrchestrator: AgentLoopOrchestrator
    private lateinit var statusProvider: VehicleStatusPreProcessor.VehicleStatusProvider
    private var mWorkHandlerThread: HandlerThread? = null
    private var mWorkHandler: Handler? = null

    private var mManager: VRServiceManager? = null
    private var mLastRequestAITimeStamp:Long = 0
    private var mCaptureCnt = 0;
    private var mPositiveReqExecuting: AtomicBoolean = AtomicBoolean(false);
    private var mNagativeReqExecuting: AtomicBoolean = AtomicBoolean(false);
    private var mRequestAIStr = ""
    private var mNagativeTTSplaying = false;
    private lateinit var sceneMatcher: SceneMatch

    private val mainHandler = Handler(Looper.getMainLooper())
    private var mChating = false

    companion object {
        private const val SENDMESSAGE_TIMEOUT_MS = 15000L
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

    private fun ProcessCaptureGot(seqid: Int, mode: Int, p: CameraData, fullTask:Boolean) {

        Log.d("TAG", " ProcessCaptureGot start !!!!!!!!!!!!!! mChating = " + mChating + " mNagativeTTSplaying = " + mNagativeTTSplaying + " mNagativeReqExecuting = " + mNagativeReqExecuting +  " fullTask = " + fullTask)
        if (vl!= null ) {
            vl!!.frontCameraSave("", p.getValue())
        }

        if (!mChating && !mNagativeTTSplaying && !mNagativeReqExecuting.get() && fullTask) {
            var start =  System.currentTimeMillis()
            Log.d("TAG", "SceneService ProcessCaptureGot after save capture !!!!!!!!!!!!!! mChating = " + mChating + " mNagativeTTSplaying = " + mNagativeTTSplaying + " fullTask = " + fullTask)



            var scene = SceneMatch.Scene("其他", "无效场景")
            scene =
                sceneMatcher.vl_scene_match(
                    getBase64(applicationContext, p.getValue()),
                    "image/jpeg"
                )
            Log.d(
                "TAG",
                "SceneService ProcessCaptureGot scene.name = " + scene.name + " mLastScence =" + mLastScence
            )
            var middle =  System.currentTimeMillis()


            if (scene.name.equals("其他") || scene.name.equals("")) {
                  // mLastScence = scene.name
            } else if (scene.name.equals(mLastScence)) {
                    mLastScence = scene.name
            } else {
                if (!mChating && !mNagativeTTSplaying && !mNagativeReqExecuting.get() && fullTask) {

                    val sceneConfig = AgentConfigFactory.createScenePersona(
                        this@AIAgentService, promptManager!!, toolRegistry,
                        statusProvider, VehicleSpeedManager(), scene)
                    val sceneOrchestrator = AgentLoopOrchestrator(
                        sceneConfig, this@AIAgentService, promptManager!!,
                        memoryOrchestrator, toolRegistry.toolSpecifications)
                    val result = sceneOrchestrator.execute("", mapOf("scene" to scene))
                    val res = if (result.isSuccess) result.output()
                              else "系统: 场景服务暂时不可用"
                    mLastScence = scene.name
                    if ("" != scene.description) {
                        mLastDesc = scene.description
                    }
                    appendPositiveResponseToChat("AI:", res, mLastDesc)
                }
                //  processPositiveRequest(res);
            }
            var end = System.currentTimeMillis()
            Log.d("TAG", "SceneService ProcessCaptureGot end !!!!!!!!!!!!!!!! seqid = " + seqid + " cost1 =" + (middle - start)  + " total cost = " + (end - start))
        }
        mPositiveReqExecuting.set(false)

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
                mNagativeTTSplaying = false;
            }

        }
    }
    inner class CameraListener : ICameraServiceListener {

        override fun onCaptureGot(seqid: Int, mode: Int, p: CameraData) {
            Log.d("TAG", " mPositiveReqExecuting = " + mPositiveReqExecuting.get() + " mNagativeReqExecuting = " + mNagativeReqExecuting + " mNagativeTTSplaying = " + mNagativeTTSplaying );
            if (mPositiveReqExecuting.get()) {
                Log.d("TAG", " onCaptureGot mPositiveReqExecuting !!!!!!!!!!!!!!!!!!")
                mWorkHandler!!.post {
                    ProcessCaptureGot(seqid, mode, p, false)
                }
            }
            else if (mNagativeReqExecuting.get()) {
                Log.d("TAG", " onCaptureGot mNagativeReqExecuting !!!!!!!!!!!!!!!!!!")
                mWorkHandler!!.post {
                    ProcessCaptureGot(seqid, mode, p, false)
                }
            }
            else if (mNagativeTTSplaying) {
                Log.d("TAG", " onCaptureGot mNagativeTTSplaying  !!!!!!!!!!!!!!!!!!")
                mWorkHandler!!.post {
                    ProcessCaptureGot(seqid, mode, p, false)
                }
            }
            else {
                Log.d("TAG", " fulltask  !!!!!!!!!!!!!!!!!!")
                mPositiveReqExecuting.set(true)
                mWorkHandler!!.post {
                    ProcessCaptureGot(seqid, mode, p, true)
                }
            }
        }
        override fun onRawData(
            var1: Int,
            var2: Int,
            var3: Int,
            var4: Long,
            var6: Int,
            var7: SharedMemory?
        ) {

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
        try {
            Camera.getInstance().init(applicationContext, m_listener);
        } catch (e: Exception) {
            Log.e("TAG", "Camera init failed", e);
        }
        val scheduler = Executors.newScheduledThreadPool(1)
        scheduler.scheduleAtFixedRate({
            try {
                requestCapture()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, 5, 1, TimeUnit.SECONDS)


        promptManager = PromptManager(this)

        // ── 工具管理器和注册表 ──
        val doorManager = VehicleDoorManager()
        val windowManager = VehicleWindowManager()
        val seatManager = VehicleSeatManager()
        val acManager = VehicleAcManager()
        val chassisManager = VehicleChassisManager()
        val fragManager = VehicleFragManager()
        val speedManager = VehicleSpeedManager()
        val dmsManager = VehicleDMSManager()
        vl = VlManager(this, promptManager!!)
        val weatherUtils = WeatherUtils("c9af807ed95f93b56855a928417586f9")

        toolRegistry = ToolRegistry().apply {
            registerAll(
                weatherUtils, doorManager, windowManager, seatManager,
                acManager, chassisManager, fragManager, speedManager,
                dmsManager, vl!!
            )
        }

        // ── 车辆状态提供者 ──
        statusProvider = VehicleStatusPreProcessor.VehicleStatusProvider {
            org.json.JSONObject().apply {
                put("车门", org.json.JSONObject(doorManager.doorStatus))
                put("车窗", org.json.JSONObject(windowManager.windowStatus))
                put("座椅、方向盘", org.json.JSONObject(seatManager.seatStatus))
                put("空调", org.json.JSONObject(acManager.acStatus))
                put("底盘", org.json.JSONObject(chassisManager.chassisStatus))
                put("香氛", org.json.JSONObject(fragManager.fragStatus))
                put("车速", org.json.JSONObject(speedManager.speedStatus))
                put("DMS", org.json.JSONObject(dmsManager.dmsStatus))
                put("当前地址", "北京市东城区")
            }.toString()
        }

        // ── 记忆系统 ──
        val summaryModel = buildQwenTurbo()
        val extractModel = buildQwenTurbo()
        memoryOrchestrator = MemoryOrchestrator(this, summaryModel, extractModel)

        // ── Trace 初始化 ──
        traceManager = TraceManager(TraceConfig.development(BuildConfig.VERSION_NAME))

        // ── 对话 Agent（持久化 Persona） ──
        chatOrchestrator = AgentLoopOrchestrator(
            AgentConfigFactory.createChatPersona(
                this, promptManager!!, memoryOrchestrator, toolRegistry,
                statusProvider, speedManager),
            this, promptManager!!, memoryOrchestrator, toolRegistry.toolSpecifications)

        // ── 场景识别 ──
        sceneMatcher = SceneMatch(promptManager!!)

        mManager = VRServiceManager.getInstance(this)
        mManager?.initCallback(m_vrlistener)

    }

    fun requestCapture() {

        val seqid = Camera.getInstance().requestCapture()
        val mode = Camera.getInstance().captureMode
        Log.d("TAG", "requestCapture seqid = " + seqid)

    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        memoryOrchestrator.shutdown()
        traceManager.shutdown()
        Log.d("TAG", "onDestroy")
    }
    fun playTTS(message:String) {
        Log.d("TAG", "playTTS!!!!!!!!!!!!")
        mManager!!.speak(message)


    }
    fun stopTTS() {
        Log.d("TAG", "stopTTS!!!!!!!!!!!!")
        mManager!!.stop()
        Thread.sleep(500)


    }

    inner class AIAgentBinder :  IAIAgentAidlInterface.Stub() {

        @Throws(RemoteException::class)
        override fun requestAI(arg: String?): Int {
            Log.d("TAG","requestAI arg = " + arg)

            if (arg.equals("@#%^StartListen")) {
                Log.d("TAG","requestAI arg = " + arg)

                mainHandler.post {
                    mRequestAIStr = ""
                    mChating = true;
                    stopTTS()
                }

            }
            else if (arg.equals("@#%^StopListen")) {
                mainHandler.post {

                    Log.d("TAG", "requestAI arg = " + arg)

                    var messgae = mRequestAIStr
                    Log.d(
                        "TAG",
                        "requestAI stopListen!!!!!!!!!!!!!!!!! mRequestAIStr = " + mRequestAIStr + " mNagativeReqExecuting = " + mNagativeReqExecuting + " mNagativeTTSplaying = " + mNagativeTTSplaying
                    )
                    if (!mNagativeReqExecuting.get()&& mNagativeTTSplaying == false && mRequestAIStr != "") {
                        mNagativeReqExecuting.set(true)
                        mWorkHandler!!.post {
                            processNagativeRequest(messgae)
                        }
                    } else if (mRequestAIStr == "" && !mNagativeReqExecuting.get() && mNagativeTTSplaying == false) {
                        mainHandler.post {
                            mChating = false
                        }
                    }
                    mRequestAIStr = ""
                }
            }
            else if (arg.equals("@#%^ClearChatMemory")) {

                mainHandler.post {
                    Log.d("TAG","requestAI arg = " + arg)

                    memoryOrchestrator.startNewSession("default_user")
                    chatOrchestrator.cleanMemory()
                }
            }



            else  if (!mNagativeReqExecuting.get() && mNagativeTTSplaying == false){
                mainHandler.post {
                    Log.d("TAG","requestAI arg = " + arg)

                    mRequestAIStr = arg!!
                    appendToChat(arg)
                }

            }
            else {

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

        @Throws(RemoteException::class)
        override fun sendMessage(text: String?) {
            Log.d("TAG", "sendMessage text = " + text)
            val message = text ?: ""

            val timeoutRunnable = Runnable {
                Log.e("TAG", "sendMessage timeout after 15s")
                val errorData = AIAgentData().apply {
                    value = "系统: 请求超时".toByteArray(Charsets.UTF_8)
                }
                notifyAIAgentListeners(0, 0, errorData)
            }
            mainHandler.postDelayed(timeoutRunnable, SENDMESSAGE_TIMEOUT_MS)

            mWorkHandler?.post {
                val session = traceManager.startSession("chat", "default_user", message)
                try {
                    Log.d("TAG", "sendMessage begin chat")
                    val ctx = mapOf("user_id" to "default_user") + session.toTraceContext().toContextData()
                    val result = chatOrchestrator.execute(message, ctx)
                    mainHandler.removeCallbacks(timeoutRunnable)
                    session.setStatus(result.isSuccess, result.errorDetail())
                    if (result.isSuccess) {
                        Log.d("TAG", "sendMessage chat result = " + result.output())
                        val resultData = AIAgentData().apply {
                            value = result.output().toByteArray(Charsets.UTF_8)
                        }
                        notifyAIAgentListeners(0, 0, resultData)
                    } else {
                        throw Exception(result.errorDetail() ?: "请求失败")
                    }
                } catch (e: Exception) {
                    mainHandler.removeCallbacks(timeoutRunnable)
                    Log.e("TAG", "sendMessage chat failed", e)
                    session.setStatus(false, e.message)
                    val errorData = AIAgentData().apply {
                        value = ("系统: 请求失败 - " + e.message).toByteArray(Charsets.UTF_8)
                    }
                    notifyAIAgentListeners(0, 0, errorData)
                } finally {
                    session.close()
                }
            }
        }

        @Throws(RemoteException::class)
        override fun sendMessageWithImage(text: String?, imageBase64: String?) {
            Log.d("TAG", "sendMessageWithImage text = " + text)
            val message = text ?: ""
            val imgB64 = imageBase64 ?: ""

            val timeoutRunnable = Runnable {
                Log.e("TAG", "sendMessageWithImage timeout after 15s")
                val errorData = AIAgentData().apply {
                    value = "系统: 请求超时".toByteArray(Charsets.UTF_8)
                }
                notifyAIAgentListeners(0, 0, errorData)
            }
            mainHandler.postDelayed(timeoutRunnable, SENDMESSAGE_TIMEOUT_MS)

            mWorkHandler?.post {
                try {
                    Log.d("TAG", "sendMessageWithImage begin vl chat")
                    val imageBytes = Base64.decode(imgB64, Base64.DEFAULT)
                    if (vl != null) {
                        val res = vl!!.frontCameraInteractionPositive(message, imageBytes)
                        mainHandler.removeCallbacks(timeoutRunnable)
                        Log.d("TAG", "sendMessageWithImage vl result = " + res)
                        val resultData = AIAgentData().apply {
                            value = res.toByteArray(Charsets.UTF_8)
                        }
                        notifyAIAgentListeners(0, 0, resultData)
                    } else {
                        mainHandler.removeCallbacks(timeoutRunnable)
                        Log.e("TAG", "sendMessageWithImage vl is null")
                        val errorData = AIAgentData().apply {
                            value = "系统: 多模态模型未初始化".toByteArray(Charsets.UTF_8)
                        }
                        notifyAIAgentListeners(0, 0, errorData)
                    }
                } catch (e: Exception) {
                    mainHandler.removeCallbacks(timeoutRunnable)
                    Log.e("TAG", "sendMessageWithImage failed", e)
                    val errorData = AIAgentData().apply {
                        value = ("系统: 请求失败 - " + e.message).toByteArray(Charsets.UTF_8)
                    }
                    notifyAIAgentListeners(0, 0, errorData)
                }
            }
        }

    }
    override fun onBind(intent: Intent?): IBinder? {
        Log.d("TAG", "onBind")
        return mBinder
    }




    private fun notifyAIAgentListeners(seqId: Int, captureMode: Int, data: AIAgentData) {
        mainHandler.post {
            for ((listener) in mIAIAgentAidlListeners) {
                try {
                    listener.onAIResponse(seqId, captureMode, data)
                } catch (e: RemoteException) {
                    Log.e("TAG", "notify listener failed", e)
                }
            }
        }
    }

    private fun processNagativeRequest(userMessage: String) {

        val session = traceManager.startSession("chat", "default_user", userMessage)
        try {
            Log.d("TAG", "processNagativeRequest begin userMessage =" + userMessage);
            val ctx = mapOf("user_id" to "default_user") + session.toTraceContext().toContextData()
            val result = chatOrchestrator.execute(userMessage, ctx)
            session.setStatus(result.isSuccess, result.errorDetail())
            val res = if (result.isSuccess) result.output()
                      else "系统: 请求失败 - " + (result.errorDetail() ?: "未知错误")
            appendNagativeResponse("AI:", res)

            Log.d("TAG", "processNagativeRequest end" );

        } catch (e: java.lang.Exception) {
            session.setStatus(false, e.message)
            appendNagativeResponse("系统: 请求失败 - ", e.message + "")
        } finally {
            session.close()
        }


    }


    private fun appendToChat(message: String) {
        val timeMillis = System.currentTimeMillis()
        var delta = timeMillis - mLastRequestAITimeStamp
        Log.d("TAG", "appentToChat delta = " + delta)
        mLastRequestAITimeStamp = timeMillis
    }
    private fun appendNagativeResponse(prefix:String, message: String) {
        mNagativeTTSplaying = true;

        mainHandler.post {
            stopTTS()
            Log.d("TAG", "appendNagativeResponse message =" + message)
            mManager?.speak(message);
            mChating = false
            mNagativeReqExecuting.set(false)
        }
    }
    private fun appendPositiveResponseToChat(prefix:String, message: String, description:String) {

        mainHandler.post {
            if (mChating == true || mNagativeTTSplaying == true || mNagativeReqExecuting.get()) {
                mWorkHandler!!.post {
                    mLastScence = "";
                }
                Log.d("TAG", "appendPositiveResponseToChat nagitavereq is executing!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!")
            }
            if (mChating == false && mNagativeTTSplaying == false && !mNagativeReqExecuting.get()) {
                stopTTS()
                Log.d("TAG", "appendPositiveResponseToChat message = " + message)
                mManager?.speak(message);
            }
        }
    }
    private fun getBase64(context: Context, byteArray: ByteArray): String {
        return Base64.encodeToString(byteArray, Base64.DEFAULT)
    }

    private fun buildQwenTurbo(): OpenAiChatModel {
        val httpBuilder = OkHttpClient.builder()
            .connectTimeout(java.time.Duration.ofSeconds(30))
            .readTimeout(java.time.Duration.ofSeconds(120))
        return OpenAiChatModel.builder()
            .httpClientBuilder(httpBuilder)
            .apiKey(BuildConfig.DASHSCOPE_API_KEY)
            .baseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1")
            .modelName("qwen-turbo")
            .parallelToolCalls(true)
            .build()
    }
}
 
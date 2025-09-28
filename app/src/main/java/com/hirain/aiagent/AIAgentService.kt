
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
    private var chatMemory: ChatMemory? = null
    private var model: ChatModel? = null
    private var vl: VlManager? = null
    private var mWorkHandlerThread: HandlerThread? = null
    private var mWorkHandler: Handler? = null

    private var mManager: VRServiceManager? = null
    private var mLastRequestAITimeStamp:Long = 0
    private var mCaptureCnt = 0;
    private var mPositiveReqExecuting = false;
    private var mNagativeReqExecuting = false;
    private var mRequestAIStr = ""
    private val systemPrompt = """角色定义：
    你是一位专业、友好且高度智能的车载AI助手，专注于提供安全、高效、愉悦的驾驶体验。
    你集成多种人工智能技术，通过不断学习迭代升级功能，在软硬件配合下实现自然流畅的人车智能交互。
    你的核心使命是在保障驾驶安全的前提下，为用户提供全方位的智能座舱服务。
核心原则
    认知友好：从用户认知角度出发，使用简化易懂高效的提示，尽量避免或减少专业术语。
    上下文感知：持续跟踪对话历史，结合当前驾驶状态、地理位置、时间等上下文提供个性化服务。
    主动智能：能够预测用户需求，在适当时机提供主动建议，但不过度打扰。
功能规范
    通用对话能力
        自然聊天：保持友好、专业且符合驾驶场景的对话风格，避免过度拟人化。
        娱乐互动：可根据请求讲笑话/故事，但需控制时长，单次不超过1分钟。
        百科问答：提供准确简洁的信息，复杂问题提供摘要并询问是否需要详情。
        天气查询：使用对应工具查询天气，回答用户关于天气的对话。
        推荐能力
            音乐/影视推荐：结合对话上下文智能推荐。
            旅游景点：结合对话上下文，提供个性化推荐。
            游玩建议：结合对话上下文，提供个性化推荐。
    智能座舱专属功能
        前向窗景互动：
            任务：结合前向窗景识别工具的能力，在用户提及时提供相关信息。
            必须遵守强实时性：用户有前向窗景识别意图时，必须重新调用工具获取并识别前向视野。
                           窗景互动不能依赖对话上下文，必须重新调用工具识别实时前向视野。
        精准控车
            支持自然语言理解的车辆控制，例如：把空调温度调节为22℃ --> 设置空调温度为22℃
            复杂指令拆解：例如：打开车窗通风并播放轻松音乐 --> 分步执行
        模糊控车
            识别隐含需求：例如："有点冷" --> 自动调高空调温度。交互规范
    话术要求
        保持简洁，单次语音输出不超过30秒。
        模糊控车需要二次确认。
能力边界声明
    关于订单预定等功能将在后续的版本退出，当前版本仅能提供语音或文本建议。
    我能够帮助您控制车辆功能、提供天气信息、娱乐服务和旅途建议，但无法代替您进行驾驶操作。请始终将注意力集中在道路上，安全驾驶。
"""
    private val weatherutils = WeatherUtils(this, "c9af807ed95f93b56855a928417586f9")
    private val wheatherTools: List<ToolSpecification> = ToolSpecifications.toolSpecificationsFrom(
        WeatherUtils::class.java
    )
    var doorManager: VehicleDoorManager = VehicleDoorManager()
    private val doorTools: List<ToolSpecification> = ToolSpecifications.toolSpecificationsFrom(
        VehicleDoorManager::class.java
    )
    var vehwindowManager: VehicleWindowManager = VehicleWindowManager()
    private val windowTools: List<ToolSpecification> = ToolSpecifications.toolSpecificationsFrom(
        VehicleWindowManager::class.java
    )
    var seatManager: VehicleSeatManager = VehicleSeatManager()
    private val seatTools: List<ToolSpecification> = ToolSpecifications.toolSpecificationsFrom(
        VehicleSeatManager::class.java
    )
    private val acManager = VehicleAcManager()
    private val acTools: List<ToolSpecification> = ToolSpecifications.toolSpecificationsFrom(
        VehicleAcManager::class.java
    )
    private val fragManager = VehicleFragManager()
    private val speedManager: VehicleSpeedManager = VehicleSpeedManager()
    private val dmsManager: VehicleDMSManager = VehicleDMSManager()
    private val fragTools: List<ToolSpecification> = ToolSpecifications.toolSpecificationsFrom(
        VehicleFragManager::class.java
    )
    private val vlTools: List<ToolSpecification> = ToolSpecifications.toolSpecificationsFrom(
        VlManager::class.java
    )
    private val speedTools: List<ToolSpecification> = ToolSpecifications.toolSpecificationsFrom(
        VehicleSpeedManager::class.java
    )
    private val dmsTools: List<ToolSpecification> = ToolSpecifications.toolSpecificationsFrom(
        VehicleDMSManager::class.java
    )
    private val scene_matcher: SceneMatch = com.hirain.aiagent.scenematch.SceneMatch()
    private var scene_server: SceneServer? = null
    private val mergedTools: List<ToolSpecification> = Stream
        .of(
            wheatherTools,
            doorTools,
            windowTools,
            seatTools,
            acTools,
            fragTools,
            vlTools,
            speedTools,
            dmsTools
        )
        .flatMap { obj: List<ToolSpecification> -> obj.stream() }.collect(Collectors.toList())
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
/*
        val filepath: String =
            applicationContext!!.getExternalFilesDir(null).toString() + "/" + seqid + ".jpg"
        Log.d("TAG", "filepath = $filepath")
        Log.i(
            "TAG",
            "onCaptureGot seqid = " + seqid + " mode = " + mode + "  p = " + p.toString()
        )
        Log.d("TAG", "vl = " + vl)

        if (vl != null) {
            var airesponse = vl!!.front_camera_interactionPositive("你必须从<scene>1.大雪天气 2.儿童睡着 3.浓烟 4.施工绕行</scene>之间定义的场景列表中选择当前的场景，禁止虚构其它场景。\n如果你判断当前不属于其中任意一种场景，直接使用不是作为回复。\n你必须严格按照<normal-reply>不是</normal-reply>之间的Json对象格式示例进行回复。\n任何情况下你的回复都必须是一个Json对象，Json对象内容严格按照上述约束。\n", p.getValue())
            if (airesponse.toString().contains("不是")) {
                Log.d("TAG", "非场景")
            }
            else {
                cleanChat()
                // Log.d("TAG","airesponse.toString() = " + airesponse.toString());
                processPositiveRequest(airesponse.toString() + ", 请执行车辆工具,并以检测到某某为最开头，尽量详细列出执行的内容，但不要列出工具名称,且不要带场景这两个字作为开头，检测到某某只需要出现一次，总字数在100字以内");
            }
        }

 */
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
        val okHttpClientBuilder = OkHttpClient.builder()
            .connectTimeout(Duration.ofSeconds(30))
            .readTimeout(Duration.ofSeconds(120))
        model = OpenAiChatModel.builder()
            .httpClientBuilder(okHttpClientBuilder)
            .apiKey("sk-11129fb7941f49dbb083039a93a160bc")
            .baseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1")
            .modelName("qwen-turbo")
            .parallelToolCalls(true)
            .build()
        chatMemory = MessageWindowChatMemory.builder()
            .maxMessages(50)
            .chatMemoryStore(PersistentChatMemorySqlite(applicationContext, "AIAgentMemory"))
            .build()

        chatMemory!!.add(SystemMessage.systemMessage(systemPrompt))
        vl = VlManager(this)
      //  Thread { processUserRequest("Hello World") }.start()
        mManager = VRServiceManager.getInstance(this)
        mManager?.initCallback(m_vrlistener)
        scene_server = com.hirain.aiagent.sceneserver.SceneServer(this)

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
                    chatMemory!!.clear()
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
    private fun positiveChatWithVehicleStatus() {
        val tmp: MutableList<ChatMessage> = ArrayList()
        tmp.add(UserMessage.userMessage("车辆状态", getVehicleStatus()))
        tmp.addAll(chatMemory!!.messages())
        val request = ChatRequest.builder()
            .messages(tmp)
            .toolSpecifications(mergedTools)
            .build()
        val aiResponse = model!!.chat(request)
        processPositiveAiResponse(aiResponse)
    }

    private fun nagativeChatWithVehicleStatus() {
        Log.d("TAG", "nagativeChatWithVehicleStatus begin" );

        val tmp: MutableList<ChatMessage> = ArrayList()
        tmp.add(UserMessage.userMessage("车辆状态", getVehicleStatus()))
        tmp.addAll(chatMemory!!.messages())
        val request = ChatRequest.builder()
            .messages(tmp)
            .toolSpecifications(mergedTools)
            .build()
        val aiResponse = model!!.chat(request)
        processNagativeAiResponse(aiResponse)
        Log.d("TAG", "nagativeChatWithVehicleStatus end" );

    }

    private fun getVehicleStatus(): String {
        try {
            val json = JSONObject()
            val doorjson = JSONObject(doorManager.doorStatus)
            val windowjson: JSONObject = JSONObject(vehwindowManager.getWindowStatus())
            val seatjson = JSONObject(seatManager.seatStatus)
            val acjson = JSONObject(acManager.acStatus)
            val fragjson = JSONObject(fragManager.fragStatus)
            val speedjson = JSONObject(speedManager.speedStatus)
            val dmsjson: JSONObject = JSONObject(dmsManager.dmsStatus)
            json.put("车门", doorjson)
            json.put("车窗", windowjson)
            json.put("座椅、方向盘", seatjson)
            json.put("空调", acjson)
            json.put("香氛", fragjson)
            json.put("车速", speedjson);
            json.put("DMS", dmsjson);            
            return json.toString()
        } catch (e: JSONException) {
            return "无效的车辆状态"
        }
    }

    private fun handleTools(request: ToolExecutionRequest): String {
        if (doorManager.hasTool(request.name())) {
            return doorManager.handleToolRequest(request)
        } else if (vehwindowManager.hasTool(request.name())) {
            return vehwindowManager.handleToolRequest(request)
        } else if (weatherutils.hasTool(request.name())) {
            return weatherutils.handleToolRequest(request)
        } else if (seatManager.hasTool(request.name())) {
            return seatManager.handleToolRequest(request)
        } else if (acManager.hasTool(request.name())) {
            return acManager.handleToolRequest(request)
        } else if (fragManager.hasTool(request.name())) {
            return fragManager.handleToolRequest(request)
        } else if (vl!!.hasTool(request.name())) {
            return vl!!.handleToolRequest(request)
        } else if (speedManager.hasTool(request.name())) {
            return speedManager.handleToolRequest(request);
        } else if (dmsManager.hasTool(request.name())) {
            return dmsManager.handleToolRequest(request);
        }
        return "无效的工具调用。"
    }

    private fun processPositiveAiResponse(aiResponse: ChatResponse) {
        val aiMessage = aiResponse.aiMessage()
        chatMemory!!.add(aiMessage)
        if (aiMessage.hasToolExecutionRequests()) {
            val tooExecutionRequests = aiMessage.toolExecutionRequests()
            for (toolrequest in tooExecutionRequests) {
                val result = handleTools(toolrequest)
                val toolExecutionResultMessage =
                    ToolExecutionResultMessage.from(toolrequest, result)
                chatMemory!!.add(toolExecutionResultMessage)
            }
            val request_with_tool = ChatRequest.builder()
                .messages(chatMemory!!.messages())
                .toolSpecifications(mergedTools)
                .build()
            val aiResponse_with_tool = model!!.chat(request_with_tool)
            processPositiveAiResponse(aiResponse_with_tool)
        }
        else {
            appendPositiveResponseToChat("AI: ", aiResponse.aiMessage().text())
        }
    }
    private fun processNagativeAiResponse(aiResponse: ChatResponse) {
        Log.d("TAG", "processNagativeAiResponse begin" );

        val aiMessage = aiResponse.aiMessage()
        chatMemory!!.add(aiMessage)

        if (aiMessage.hasToolExecutionRequests()) {
            val tooExecutionRequests = aiMessage.toolExecutionRequests()
            for (toolrequest in tooExecutionRequests) {
                val result = handleTools(toolrequest)
                val toolExecutionResultMessage =
                    ToolExecutionResultMessage.from(toolrequest, result)
                chatMemory!!.add(toolExecutionResultMessage)
            }
            val request_with_tool = ChatRequest.builder()
                .messages(chatMemory!!.messages())
                .toolSpecifications(mergedTools)
                .build()
            val aiResponse_with_tool = model!!.chat(request_with_tool)
            processNagativeAiResponse(aiResponse_with_tool)
        }
        else {
            appendNagativeResponse("AI: ", aiResponse.aiMessage().text())
        }
        Log.d("TAG", "processNagativeAiResponse end" );

    }
    private fun processNagativeRequest(userMessage: String) {
        mNagativeReqExecuting = true

        try {
            Log.d("TAG", "processNagativeRequest begin userMessage =" + userMessage);
            chatMemory!!.add(UserMessage.userMessage(userMessage))
            nagativeChatWithVehicleStatus()
            Log.d("TAG", "processNagativeRequest end" );

        } catch (e: java.lang.Exception) {
            appendNagativeResponse("系统: 请求失败 - ", e.message + "")
        }
        mNagativeReqExecuting = false

    }
    private fun processPositiveRequest(userMessage: String) {
        Log.d("TAG", "processPositiveRequest 1111111111111111111111111 begin " )

        try {
            chatMemory!!.add(UserMessage.userMessage(userMessage))
            positiveChatWithVehicleStatus()
        } catch (e: java.lang.Exception) {
            appendPositiveResponseToChat("", "系统: 请求失败 - " + e.message)
        }
        Log.d("TAG", "processPositiveRequest 1111111111111111111111111111 end " )

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
 
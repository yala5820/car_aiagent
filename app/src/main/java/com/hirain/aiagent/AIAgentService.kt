
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
import com.hirain.aiagent.runtime.ActiveRequest
import com.hirain.aiagent.runtime.ActiveRequestRegistry
import com.hirain.aiagent.runtime.RequestAdmission
import com.hirain.aiagent.runtime.RequestAdmissionResult
import com.hirain.aiagent.runtime.RequestCallRegistry
import com.hirain.aiagent.runtime.RequestDeadline
import com.hirain.aiagent.runtime.RequestExecutionContext
import com.hirain.aiagent.runtime.AgentExecutor
import com.hirain.aiagent.runtime.AgentRuntime
import com.hirain.aiagent.runtime.RuntimeResponseMapper
import com.hirain.aiagent.runtime.RuntimeResult
import com.hirain.aiagent.core.factory.AgentConfigFactory
import com.hirain.aiagent.core.preprocessor.VehicleStatusPreProcessor
import com.hirain.aiagent.conversation.ConversationManager
import com.hirain.aiagent.conversation.MemoryConversationSessionGateway
import com.hirain.aiagent.memory.MemoryOrchestrator
import com.hirain.aiagent.safety.DefaultSafetyRules
import com.hirain.aiagent.safety.ToolSafetyEngine
import com.hirain.aiagent.safety.confirmation.ConfirmationTextParser
import com.hirain.aiagent.safety.confirmation.ToolConfirmationCoordinator
import com.hirain.aiagent.trace.TraceConfig
import com.hirain.aiagent.trace.TraceAttributeKeys
import com.hirain.aiagent.trace.TraceManager
import com.hirain.aiagent.trace.TraceResponseDispatcher
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
import com.hirain.aiagent.VirtualStateMachine.VehicleStateMachine
import com.hirain.aiagent.context.ContextBuildInput

import com.hirain.aiagent.context.ContextOrchestrator
import com.hirain.aiagent.runtime.RuntimeCancelChecker
import com.hirain.aiagent.runtime.SystemTimeProvider
import com.hirain.aiagent.core.TextAgentLoopOrchestrator
import com.hirain.aiagent.tools.vision.vl.VlManager
import com.hirain.aiagent.AgentRequest
import com.hirain.aiagent.AgentResponse
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
    private lateinit var contextOrchestrator: ContextOrchestrator
    private lateinit var vehicleStateMachine: VehicleStateMachine
    private lateinit var toolSafetyEngine: ToolSafetyEngine
    private lateinit var toolConfirmationCoordinator: ToolConfirmationCoordinator
    private lateinit var traceManager: TraceManager
    private lateinit var chatOrchestrator: AgentLoopOrchestrator
    private lateinit var agentRuntime: AgentRuntime
    private lateinit var runtimeResponseMapper: RuntimeResponseMapper
    private lateinit var conversationManager: ConversationManager
    private val activeRequestRegistry = ActiveRequestRegistry()
    private val requestCallRegistry = RequestCallRegistry()
    private val activeTimeouts = java.util.concurrent.ConcurrentHashMap<String, Runnable>()
    private val requestDispatchers = java.util.concurrent.ConcurrentHashMap<String, TraceResponseDispatcher>()
    private lateinit var statusProvider: VehicleStatusPreProcessor.VehicleStatusProvider
    private var mWorkHandlerThread: HandlerThread? = null
    private var mWorkHandler: Handler? = null
    private var mTextWorkHandlerThread: HandlerThread? = null
    private var mTextWorkHandler: Handler? = null

    private var mManager: VRServiceManager? = null
    private var mLastRequestAITimeStamp:Long = 0
    private var mCaptureCnt = 0;
    private var mPositiveReqExecuting: AtomicBoolean = AtomicBoolean(false);
    private var mNagativeReqExecuting: AtomicBoolean = AtomicBoolean(false);
    private var mRequestAIStr = ""
    private var mNagativeTTSplaying = false;
    private lateinit var sceneMatcher: SceneMatch

    private lateinit var textOrchestrator: TextAgentLoopOrchestrator

    private val mainHandler = Handler(Looper.getMainLooper())
    private var mChating = false

    companion object {
        // 非 TEXT 旧链路继续沿用原有时限；TEXT 使用 RequestDeadline 的独立 30 秒语义。
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
        if (mTextWorkHandlerThread == null) {
            mTextWorkHandlerThread = HandlerThread("text_agent_work_thread")
            mTextWorkHandlerThread!!.start()
            mTextWorkHandler = Handler(mTextWorkHandlerThread!!.looper)
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
                        statusProvider, scene)
                    val sceneOrchestrator = AgentLoopOrchestrator(
                        sceneConfig, this@AIAgentService, promptManager!!,
                        memoryOrchestrator, toolRegistry.toolSpecifications,
                        toolSafetyEngine)
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
        if (path == null) return 0
        val file = File(path)

        var out: FileOutputStream? = null
        try {
            val fileParent = file.parentFile ?: return 0
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

        // ── 虚拟车辆状态机 ──
        vehicleStateMachine = VehicleStateMachine()

        // ── 全 Persona 共用的确定性 Tool 安全引擎 ──
        toolSafetyEngine = ToolSafetyEngine(
            vehicleStateMachine,
            DefaultSafetyRules.create()
        )

        // ── 工具管理器和注册表 ──
        val doorManager = VehicleDoorManager(vehicleStateMachine)
        val windowManager = VehicleWindowManager(vehicleStateMachine)
        val seatManager = VehicleSeatManager(vehicleStateMachine)
        val acManager = VehicleAcManager(vehicleStateMachine)
        val chassisManager = VehicleChassisManager(vehicleStateMachine)
        val fragManager = VehicleFragManager(vehicleStateMachine)
        val speedManager = VehicleSpeedManager(vehicleStateMachine)
        val dmsManager = VehicleDMSManager(vehicleStateMachine)
        vl = VlManager(this, promptManager!!)
        val weatherUtils = WeatherUtils(BuildConfig.WEATHER_API_KEY)

        toolRegistry = ToolRegistry().apply {
            registerAll(
                weatherUtils, doorManager, windowManager, seatManager,
                acManager, chassisManager, fragManager,
                dmsManager, vl!!
            )
        }
        toolConfirmationCoordinator = ToolConfirmationCoordinator(
            toolSafetyEngine,
            { toolRequest -> toolRegistry.dispatch(toolRequest) },
            SystemTimeProvider()
        )

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

        // ── 会话管理 ──
        conversationManager = ConversationManager(MemoryConversationSessionGateway(memoryOrchestrator))

        // ── 对话 Agent（持久化 Persona） ──
        chatOrchestrator = AgentLoopOrchestrator(
            AgentConfigFactory.createChatPersona(
                this, promptManager!!, memoryOrchestrator, toolRegistry,
                statusProvider),
            this, promptManager!!, memoryOrchestrator, toolRegistry.toolSpecifications,
            toolSafetyEngine)

        // ── ContextOrchestrator 初始化（必须在 textOrchestrator 前创建，因为需要注入作为 ContextAssemblyGateway） ──
        contextOrchestrator = ContextOrchestrator.defaultForText(
            ContextBuildInput.builder()
                .toolGroupRegistry(com.hirain.aiagent.toolgroup.ToolGroupRegistry.defaultRegistry())
                .promptManager(promptManager!!)
                .memoryGateway(memoryOrchestrator)
                .toolRegistry(toolRegistry)
                .vehicleStatusProvider { statusProvider.getVehicleStatus() }
                .timeProvider(SystemTimeProvider())
                .build()
        )

        // ── TEXT Persona Orchestrator（专用 AgentLoop：只接收 ContextMemoryGateway + ContextAssemblyGateway） ──
        textOrchestrator = TextAgentLoopOrchestrator(
            AgentConfigFactory.createTextPersona(
                this, promptManager!!, memoryOrchestrator,
                toolRegistry, statusProvider, requestCallRegistry, "chat"
            ),
            memoryOrchestrator,  // implements ContextMemoryGateway
            contextOrchestrator,  // implements ContextAssemblyGateway
            toolSafetyEngine,
            toolConfirmationCoordinator
        )

        // ── AgentRuntime 初始化（注入 memoryOrchestrator 作为 SessionIdResolver） ──
        agentRuntime = AgentRuntime(
            AgentExecutor { session, prepareResult ->
                // Phase 5：直接调用 TEXT 独占入口，ChatRequest 由 ContextAssemblyResult 驱动
                textOrchestrator.execute(session, prepareResult)
            },
            contextOrchestrator,
            memoryOrchestrator,
            RuntimeCancelChecker { runtimeSession ->
                activeRequestRegistry.get(runtimeSession.requestId())?.let {
                    it.state() != com.hirain.aiagent.runtime.ActiveRequest.TerminalState.RUNNING
                } ?: false
            }
        )
        runtimeResponseMapper = RuntimeResponseMapper()

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
        mTextWorkHandlerThread?.quitSafely()
        mWorkHandlerThread?.quitSafely()
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

    // ── 请求规范化辅助 ──

    private fun ensureRequestId(request: AgentRequest?): String {
        val existing = request?.requestId?.takeIf { it.isNotBlank() }
        if (existing != null) return existing
        val generated = java.util.UUID.randomUUID().toString()
        if (request != null) {
            request.requestId = generated
        }
        return generated
    }

    private fun normalizeUserId(request: AgentRequest?): String {
        return request?.userId?.takeIf { it.isNotBlank() } ?: "default_user"
    }

    private fun normalizePersonaId(request: AgentRequest?): String {
        return request?.personaId?.takeIf { it.isNotBlank() } ?: "chat"
    }

    private fun normalizeTextPersona(requested: String?): String {
        val requestedPersona = requested?.takeIf { it.isNotBlank() } ?: "chat"
        return if (requestedPersona == "chat" || requestedPersona == "friendly" || requestedPersona == "concise")
            requestedPersona else "chat"
    }

    inner class AIAgentBinder :  IAIAgentAidlInterface.Stub() {

        @Throws(RemoteException::class)
        override fun processAgentRequest(request: AgentRequest?) {
            if (request == null) return
            Log.d("TAG", "processAgentRequest: type=${request.inputType} id=${request.requestId}")

            when (request.inputType) {
                "TEXT" -> handleTextRequest(request)
                "IMAGE" -> handleImageRequest(request)
                "VOICE" -> handleVoiceRequest(request)
                "CONTROL" -> handleControlRequest(request)
                else -> Log.w("TAG", "Unknown inputType: ${request.inputType}")
            }
        }

        @Throws(RemoteException::class)
        override fun createConversation(request: ConversationRequest?): ConversationOperationResult {
            return conversationManager.createConversation(request)
        }

        @Throws(RemoteException::class)
        override fun listConversations(userId: String?): ConversationListResponse {
            return conversationManager.listConversations(userId)
        }

        @Throws(RemoteException::class)
        override fun deleteConversation(userId: String?, sessionId: String?): ConversationOperationResult {
            return conversationManager.deleteConversation(userId, sessionId)
        }

        @Throws(RemoteException::class)
        override fun switchConversation(userId: String?, sessionId: String?): ConversationOperationResult {
            return conversationManager.switchConversation(userId, sessionId)
        }

        @Throws(RemoteException::class)
        override fun getActiveConversation(userId: String?): ConversationInfo? {
            return conversationManager.getActiveConversation(userId)
        }

        @Throws(RemoteException::class)
        override fun cancelAgentRequest(requestId: String?, reason: String?): CancelRequestResult {
            val cancelReason = reason ?: "cancelled_by_client"
            val result = activeRequestRegistry.cancel(requestId, cancelReason, System.currentTimeMillis())
            if (result.isSuccess) {
                requestId?.let { id ->
                    // 终态通知与执行槽位分离：先取消模型 Call，槽位由 worker finally 释放。
                    requestCallRegistry.cancel(id)
                    if (::toolConfirmationCoordinator.isInitialized) {
                        toolConfirmationCoordinator.cancelPendingForOriginalRequest(id)
                    }
                    activeTimeouts.remove(id)?.let { timeoutRunnable ->
                        mainHandler.removeCallbacks(timeoutRunnable)
                    }
                    activeRequestRegistry.get(id)?.let { active ->
                        val cancelledResult = RuntimeResult.cancelled(
                            active.requestId(), active.sessionId(),
                            active.userId(), active.personaId(), active.clientMessageId(),
                            cancelReason, System.currentTimeMillis())
                        val cancelledResponse = runtimeResponseMapper.toAgentResponse(cancelledResult)
                        // 使用 TraceResponseDispatcher 记录取消路径的 response.dispatch
                        requestDispatchers.remove(id)?.let { dispatcher ->
                            dispatcher.dispatchAndClose(cancelledResponse, cancelReason) {
                                notifyAIAgentListeners(it)
                            }
                        } ?: run {
                            notifyAIAgentListeners(cancelledResponse)
                        }
                    }
                }
                stopTTS()
            }
            return result
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
    override fun onBind(intent: Intent?): IBinder? {
        Log.d("TAG", "onBind")
        return mBinder
    }




    // ── 统一请求路由 ──

    private fun handleTextRequest(request: AgentRequest) {
        val message = request.text ?: ""
        val requestId = ensureRequestId(request)
        val userId = normalizeUserId(request)
        val rawPersonaId = normalizePersonaId(request)
        val effectivePersonaId = normalizeTextPersona(rawPersonaId)
        // 将实际使用的 persona 写回 request，确保 Trace 和 Runtime 使用同一值
        request.personaId = effectivePersonaId
        val nowMs = System.currentTimeMillis()
        val deadline = RequestDeadline.standard(nowMs)
        val admission = RequestAdmission(
            requestId, request.sessionId, userId, effectivePersonaId,
            request.clientMessageId, deadline
        )
        val admissionResult = activeRequestRegistry.tryAcquire(admission, nowMs)
        if (!admissionResult.isAccepted) {
            val rejectedResult = if (admissionResult.status() == RequestAdmissionResult.Status.BUSY) {
                RuntimeResult.busy(requestId, request.sessionId, userId,
                    effectivePersonaId, request.clientMessageId, nowMs)
            } else {
                RuntimeResult.duplicate(requestId, request.sessionId, userId,
                    effectivePersonaId, request.clientMessageId, nowMs)
            }
            val rejectedTrace = traceManager.startAgentRequest(
                effectivePersonaId, userId, requestId, request.sessionId,
                request.sourceApp, request.inputType, message)
            rejectedTrace.setAttribute(
                TraceAttributeKeys.REQUEST_ADMISSION_STATUS,
                admissionResult.status().name)
            TraceResponseDispatcher(rejectedTrace).dispatchAndClose(
                runtimeResponseMapper.toAgentResponse(rejectedResult),
                admissionResult.status().name) { notifyAIAgentListeners(it) }
            return
        }

        val activeRequest = admissionResult.activeRequest()
        var traceSession: com.hirain.aiagent.trace.TraceSession? = null
        try {
            traceSession = traceManager.startAgentRequest(
                effectivePersonaId,
                userId,
                requestId,
                request.sessionId,
                request.sourceApp,
                request.inputType,
                message
            )
            val session = traceSession!!
            session.setAttribute(TraceAttributeKeys.REQUEST_ADMISSION_STATUS, "ACCEPTED")
            session.setAttribute(TraceAttributeKeys.REQUEST_DEADLINE_AT_MS, deadline.deadlineAtMs())
            session.setAttribute(TraceAttributeKeys.REQUEST_TIMEOUT_MS,
                RequestDeadline.DEFAULT_TIMEOUT_MS)
            val confirmationCommand = toolConfirmationCoordinator.parse(message)
            if (confirmationCommand != ConfirmationTextParser.Command.NONE) {
                handleConfirmationTextRequest(
                    request, requestId, userId, effectivePersonaId,
                    activeRequest, session, deadline
                )
                return
            }
            // 任何新的普通请求都会取消旧 PendingAction，防止旧动作跨请求获得授权。
            toolConfirmationCoordinator.cancelPendingForOrdinaryRequest()
            val runtimeSession = agentRuntime.startSession(
                request, session.toTraceContext(), deadline)
            activeRequest.bindSession(runtimeSession)
            val responseDispatcher = TraceResponseDispatcher(session)
            requestDispatchers[requestId] = responseDispatcher

            val timeoutRunnable = Runnable {
                Log.e("TAG", "processAgentRequest TEXT timeout")
                if (activeRequestRegistry.tryComplete(
                        requestId, ActiveRequest.TerminalState.TIMEOUT)) {
                    requestCallRegistry.cancel(requestId)
                    toolConfirmationCoordinator.cancelPendingForOriginalRequest(requestId)
                    val timeoutResponse = runtimeResponseMapper.toAgentResponse(
                        agentRuntime.timeoutResult(runtimeSession))
                    responseDispatcher.dispatchAndClose(timeoutResponse, "timeout") {
                        notifyAIAgentListeners(it)
                    }
                    activeTimeouts.remove(requestId)
                    requestDispatchers.remove(requestId)
                }
            }
            activeTimeouts[requestId] = timeoutRunnable
            mainHandler.postDelayed(
                timeoutRunnable, deadline.remainingMs(System.currentTimeMillis()))

            Log.d("TAG", "TextRequest requestId=$requestId sessionId=${request.sessionId} " +
                    "userId=$userId personaId=$effectivePersonaId " +
                    "clientMessageId=${request.clientMessageId}")

            val posted = mTextWorkHandler?.post {
                val traceScope = session.makeCurrent()
                val executionScope = RequestExecutionContext.bind(requestId, deadline)
                try {
                    if (activeRequest.state() != ActiveRequest.TerminalState.RUNNING) {
                        return@post
                    }
                    Log.d("TAG", "handleTextRequest begin")
                    val runtimeResult = agentRuntime.execute(runtimeSession)
                    val terminalState = terminalStateFor(runtimeResult)
                    if (activeRequestRegistry.tryComplete(requestId, terminalState)) {
                        if (terminalState == ActiveRequest.TerminalState.TIMEOUT
                            || terminalState == ActiveRequest.TerminalState.CANCELLED) {
                            requestCallRegistry.cancel(requestId)
                        }
                        activeTimeouts.remove(requestId)?.let {
                            mainHandler.removeCallbacks(it)
                        }
                        val response = runtimeResponseMapper.toAgentResponse(runtimeResult)
                        responseDispatcher.dispatchAndClose(response, runtimeResult.errorType()) {
                            notifyAIAgentListeners(it)
                        }
                        requestDispatchers.remove(requestId)
                    }
                } catch (e: Exception) {
                    Log.e("TAG", "handleTextRequest service-level failure", e)
                    if (activeRequestRegistry.tryComplete(
                            requestId, ActiveRequest.TerminalState.FAILED)) {
                        activeTimeouts.remove(requestId)?.let {
                            mainHandler.removeCallbacks(it)
                        }
                        val errorResult = agentRuntime.errorResult(runtimeSession, e)
                        val response = runtimeResponseMapper.toAgentResponse(errorResult)
                        responseDispatcher.dispatchAndClose(
                            response, e.message ?: "service_failure") {
                            notifyAIAgentListeners(it)
                        }
                        requestDispatchers.remove(requestId)
                    }
                } finally {
                    activeTimeouts.remove(requestId)?.let {
                        mainHandler.removeCallbacks(it)
                    }
                    requestDispatchers.remove(requestId)
                    executionScope.close()
                    traceScope.close()
                    session.close()
                    requestCallRegistry.clear(requestId)
                    activeRequestRegistry.release(requestId, System.currentTimeMillis())
                }
            } ?: false

            if (!posted) {
                if (activeRequestRegistry.tryComplete(
                        requestId, ActiveRequest.TerminalState.FAILED)) {
                    val errorResult = RuntimeResult.failure(
                        requestId, runtimeSession.sessionId(), userId, effectivePersonaId,
                        request.clientMessageId, "WORKER_SUBMISSION_FAILED",
                        "TEXT worker 无法接受请求", System.currentTimeMillis())
                    responseDispatcher.dispatchAndClose(
                        runtimeResponseMapper.toAgentResponse(errorResult),
                        "worker_submission_failed") { notifyAIAgentListeners(it) }
                }
                activeTimeouts.remove(requestId)?.let { mainHandler.removeCallbacks(it) }
                requestDispatchers.remove(requestId)
                session.close()
                requestCallRegistry.clear(requestId)
                activeRequestRegistry.release(requestId, System.currentTimeMillis())
            }
        } catch (e: Exception) {
            Log.e("TAG", "TEXT request setup failed", e)
            if (activeRequestRegistry.tryComplete(requestId, ActiveRequest.TerminalState.FAILED)) {
                val errorResult = RuntimeResult.failure(
                    requestId, request.sessionId, userId, effectivePersonaId,
                    request.clientMessageId, "REQUEST_SETUP_FAILED",
                    e.message ?: "请求初始化失败", System.currentTimeMillis())
                notifyAIAgentListeners(runtimeResponseMapper.toAgentResponse(errorResult))
            }
            traceSession?.close()
            requestCallRegistry.clear(requestId)
            activeRequestRegistry.release(requestId, System.currentTimeMillis())
        }
    }

    /**
     * 在 IntentRouter 与 LLM 前处理严格确认文本。
     * 该分支仍复用单槽位准入、30 秒 deadline、取消状态和唯一终态响应。
     */
    private fun handleConfirmationTextRequest(
        request: AgentRequest,
        requestId: String,
        userId: String,
        personaId: String,
        activeRequest: ActiveRequest,
        traceSession: com.hirain.aiagent.trace.TraceSession,
        deadline: RequestDeadline
    ) {
        val responseDispatcher = TraceResponseDispatcher(traceSession)
        requestDispatchers[requestId] = responseDispatcher
        val timeoutRunnable = Runnable {
            if (activeRequestRegistry.tryComplete(requestId, ActiveRequest.TerminalState.TIMEOUT)) {
                val timeoutResult = RuntimeResult.timeout(
                    requestId, request.sessionId, userId, personaId,
                    request.clientMessageId, System.currentTimeMillis())
                responseDispatcher.dispatchAndClose(
                    runtimeResponseMapper.toAgentResponse(timeoutResult), "confirmation_timeout"
                ) { notifyAIAgentListeners(it) }
                activeTimeouts.remove(requestId)
                requestDispatchers.remove(requestId)
            }
        }
        activeTimeouts[requestId] = timeoutRunnable
        mainHandler.postDelayed(
            timeoutRunnable, deadline.remainingMs(System.currentTimeMillis()))

        val posted = mTextWorkHandler?.post {
            val traceScope = traceSession.makeCurrent()
            val executionScope = RequestExecutionContext.bind(requestId, deadline)
            try {
                if (activeRequest.state() != ActiveRequest.TerminalState.RUNNING) return@post
                // 当前只支持一个 Session；请求未显式携带 id 时使用待确认动作所属 session。
                val confirmationSessionId = request.sessionId
                    ?: toolConfirmationCoordinator.pendingAction()?.sessionId()
                val confirmationResult = toolConfirmationCoordinator.handleText(
                    request.text, confirmationSessionId
                ) {
                    deadline.isExpired(System.currentTimeMillis()) ||
                        activeRequest.state() != ActiveRequest.TerminalState.RUNNING
                }
                confirmationResult.confirmationId()?.let {
                    traceSession.setAttribute(TraceAttributeKeys.CONFIRMATION_ID, it)
                }
                confirmationResult.reasonCode()?.let {
                    traceSession.setAttribute(TraceAttributeKeys.CONFIRMATION_REASON_CODE, it.name)
                }
                traceSession.setAttribute(
                    TraceAttributeKeys.CONFIRMATION_STATUS,
                    if (confirmationResult.success()) "COMPLETED" else "REJECTED"
                )

                val now = System.currentTimeMillis()
                val runtimeResult = if (confirmationResult.success()) {
                    RuntimeResult.success(
                        requestId, confirmationSessionId, userId, personaId,
                        request.clientMessageId, confirmationResult.text(), now, 0, 0)
                } else {
                    RuntimeResult.failure(
                        requestId, confirmationSessionId, userId, personaId,
                        request.clientMessageId, "CONFIRMATION_FAILED",
                        confirmationResult.text(), now)
                }
                val terminalState = if (confirmationResult.success()) {
                    ActiveRequest.TerminalState.COMPLETED
                } else {
                    ActiveRequest.TerminalState.FAILED
                }
                if (activeRequestRegistry.tryComplete(requestId, terminalState)) {
                    if (!confirmationSessionId.isNullOrBlank()) {
                        val memory = memoryOrchestrator.chatMemoryForSession(confirmationSessionId, 50)
                        memory.add(dev.langchain4j.data.message.UserMessage.from(request.text ?: ""))
                        memory.add(dev.langchain4j.data.message.AiMessage.from(confirmationResult.text()))
                    }
                    activeTimeouts.remove(requestId)?.let { mainHandler.removeCallbacks(it) }
                    responseDispatcher.dispatchAndClose(
                        runtimeResponseMapper.toAgentResponse(runtimeResult),
                        confirmationResult.reasonCode()?.name
                    ) { notifyAIAgentListeners(it) }
                    requestDispatchers.remove(requestId)
                }
            } catch (e: Exception) {
                Log.e("TAG", "confirmation request failed", e)
                if (activeRequestRegistry.tryComplete(requestId, ActiveRequest.TerminalState.FAILED)) {
                    val error = RuntimeResult.failure(
                        requestId, request.sessionId, userId, personaId,
                        request.clientMessageId, "CONFIRMATION_FAILED",
                        e.message ?: "确认处理失败", System.currentTimeMillis())
                    responseDispatcher.dispatchAndClose(
                        runtimeResponseMapper.toAgentResponse(error), "confirmation_exception"
                    ) { notifyAIAgentListeners(it) }
                }
            } finally {
                activeTimeouts.remove(requestId)?.let { mainHandler.removeCallbacks(it) }
                requestDispatchers.remove(requestId)
                executionScope.close()
                traceScope.close()
                traceSession.close()
                requestCallRegistry.clear(requestId)
                activeRequestRegistry.release(requestId, System.currentTimeMillis())
            }
        } ?: false

        if (!posted) {
            if (activeRequestRegistry.tryComplete(requestId, ActiveRequest.TerminalState.FAILED)) {
                val failure = RuntimeResult.failure(
                    requestId, request.sessionId, userId, personaId,
                    request.clientMessageId, "WORKER_SUBMISSION_FAILED",
                    "TEXT worker 无法接受确认请求", System.currentTimeMillis())
                responseDispatcher.dispatchAndClose(
                    runtimeResponseMapper.toAgentResponse(failure), "confirmation_worker_failed"
                ) { notifyAIAgentListeners(it) }
            }
            activeTimeouts.remove(requestId)?.let { mainHandler.removeCallbacks(it) }
            requestDispatchers.remove(requestId)
            traceSession.close()
            activeRequestRegistry.release(requestId, System.currentTimeMillis())
        }
    }

    private fun terminalStateFor(result: RuntimeResult): ActiveRequest.TerminalState {
        return when (result.errorType()) {
            null -> ActiveRequest.TerminalState.COMPLETED
            "TIMEOUT" -> ActiveRequest.TerminalState.TIMEOUT
            "CANCELLED" -> ActiveRequest.TerminalState.CANCELLED
            else -> ActiveRequest.TerminalState.FAILED
        }
    }

    private fun handleImageRequest(request: AgentRequest) {
        val timeoutRunnable = Runnable {
            Log.e("TAG", "processAgentRequest IMAGE timeout")
            notifyAIAgentListeners(AgentResponse().apply {
                requestId = request.requestId
                sessionId = request.sessionId
                setSuccess(false)
                text = "系统: 请求超时"
                errorType = "TIMEOUT"
                timestamp = System.currentTimeMillis()
            })
        }
        mainHandler.postDelayed(timeoutRunnable, SENDMESSAGE_TIMEOUT_MS)

        mWorkHandler?.post {
            try {
                Log.d("TAG", "handleImageRequest begin")
                if (vl == null) {
                    mainHandler.removeCallbacks(timeoutRunnable)
                    notifyAIAgentListeners(AgentResponse().apply {
                        requestId = request.requestId
                        sessionId = request.sessionId
                        setSuccess(false)
                        text = "系统: 多模态模型未初始化"
                        errorType = "VL_NOT_INITIALIZED"
                        timestamp = System.currentTimeMillis()
                    })
                    return@post
                }
                val imageBytes = if (request.imagePath != null) {
                    val file = java.io.File(request.imagePath)
                    if (file.exists()) file.readBytes() else throw Exception("图片文件不存在: ${request.imagePath}")
                } else {
                    throw Exception("IMAGE 请求缺少 imagePath")
                }
                val res = vl!!.frontCameraInteractionPositive(request.text ?: "", imageBytes)
                mainHandler.removeCallbacks(timeoutRunnable)
                notifyAIAgentListeners(AgentResponse().apply {
                    requestId = request.requestId
                    sessionId = request.sessionId
                    setSuccess(true)
                    text = res
                    timestamp = System.currentTimeMillis()
                })
            } catch (e: Exception) {
                mainHandler.removeCallbacks(timeoutRunnable)
                Log.e("TAG", "handleImageRequest failed", e)
                notifyAIAgentListeners(AgentResponse().apply {
                    requestId = request.requestId
                    sessionId = request.sessionId
                    setSuccess(false)
                    text = "系统: 请求失败 - ${e.message}"
                    errorType = "EXCEPTION"
                    timestamp = System.currentTimeMillis()
                })
            }
        }
    }

    private fun handleVoiceRequest(request: AgentRequest) {
        mNagativeReqExecuting.set(true)
        stopTTS()

        val userId = normalizeUserId(request)
        val personaId = normalizePersonaId(request)
        val session = traceManager.startSession(personaId, userId, request.text ?: "")
        mWorkHandler?.post {
            try {
                Log.d("TAG", "handleVoiceRequest begin text=${request.text}")
                val ctx = mutableMapOf<String, Any>(
                    "user_id" to userId,
                    "persona_id" to personaId
                )
                ctx.putAll(session.toTraceContext().toContextData())
                val result = chatOrchestrator.execute(request.text ?: "", ctx)
                session.setStatus(result.isSuccess, result.errorDetail())
                val res = if (result.isSuccess) result.output()
                          else "系统: 请求失败 - ${result.errorDetail() ?: "未知错误"}"
                notifyAIAgentListeners(AgentResponse().apply {
                    requestId = request.requestId
                    sessionId = request.sessionId
                    setSuccess(result.isSuccess)
                    text = res
                    errorType = if (!result.isSuccess) result.errorType()?.name else null
                    timestamp = System.currentTimeMillis()
                })
                mNagativeTTSplaying = true
                mainHandler.post {
                    stopTTS()
                    mManager?.speak(res)
                    mChating = false
                    mNagativeReqExecuting.set(false)
                }
            } catch (e: Exception) {
                session.setStatus(false, e.message)
                notifyAIAgentListeners(AgentResponse().apply {
                    requestId = request.requestId
                    sessionId = request.sessionId
                    setSuccess(false)
                    text = "系统: 请求失败 - ${e.message}"
                    errorType = "EXCEPTION"
                    timestamp = System.currentTimeMillis()
                })
                mNagativeTTSplaying = true
                mainHandler.post {
                    stopTTS()
                    mManager?.speak("系统: 请求失败")
                    mChating = false
                    mNagativeReqExecuting.set(false)
                }
            } finally {
                session.close()
            }
        }
    }

    private fun handleControlRequest(request: AgentRequest) {
        val command = request.text ?: ""
        Log.d("TAG", "handleControlRequest command=$command")
        when (command) {
            "StartListen", "@#%^StartListen" -> {
                mainHandler.post {
                    mRequestAIStr = ""
                    mChating = true
                    stopTTS()
                }
            }
            "StopListen", "@#%^StopListen" -> {
                mainHandler.post {
                    mChating = false
                }
            }
            "ClearChatMemory", "@#%^ClearChatMemory" -> {
                mainHandler.post {
                    // 清除当前共享 session 的短期消息，不创建新 session
                    val userId = normalizeUserId(request)
                    val sessionId = request.sessionId
                        ?: memoryOrchestrator.resolveSessionId(
                            userId, null, request.text, request.personaId, request.sourceApp)
                    memoryOrchestrator.clearSessionMemory(sessionId)
                }
            }
            else -> Log.w("TAG", "Unknown control command: $command")
        }
    }

    private fun notifyAIAgentListeners(response: AgentResponse) {
        mainHandler.post {
            for ((listener) in mIAIAgentAidlListeners) {
                try {
                    listener.onAIResponse(response)
                } catch (e: RemoteException) {
                    Log.e("TAG", "notify listener failed", e)
                }
            }
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
            .readTimeout(java.time.Duration.ofSeconds(30))
            .requestCallRegistry(requestCallRegistry)
        return OpenAiChatModel.builder()
            .httpClientBuilder(httpBuilder)
            .apiKey(BuildConfig.DASHSCOPE_API_KEY)
            .baseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1")
            .modelName("qwen-turbo")
            .parallelToolCalls(true)
            .build()
    }
}
 

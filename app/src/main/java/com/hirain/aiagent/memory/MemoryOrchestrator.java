package com.hirain.aiagent.memory;

import android.content.Context;
import android.util.Log;

import com.hirain.aiagent.trace.AgentTraceRecorder;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;

/**
 * 记忆协调器 — AgentLoopOrchestrator 与记忆系统之间的唯一交互点。
 * <p>
 * 职责：
 * <ul>
 *   <li>管理多个用户的记忆上下文（UserMemoryContext 池）</li>
 *   <li>Agent 执行前：准备 SystemMessage（注入长期记忆）</li>
 *   <li>Agent 执行后：提取长期记忆、检查压缩</li>
 *   <li>Session 生命周期管理</li>
 * </ul>
 */
public class MemoryOrchestrator {

    private static final String TAG = "MemoryOrchestrator";

    private final SessionMemoryStore sessionStore;
    private final SessionManager sessionManager;
    private final LongTermMemoryStore longTermStore;
    private final MemoryCompressor compressor;
    private final MemoryExtractor extractor;

    private final ConcurrentHashMap<String, UserMemoryContext> userContexts = new ConcurrentHashMap<>();

    public MemoryOrchestrator(Context context, ChatModel summaryModel, ChatModel extractModel) {
        this.sessionStore = new SessionMemoryStore(context);
        this.sessionManager = new SessionManager(sessionStore);
        this.longTermStore = new LongTermMemoryStore(context);
        this.compressor = new MemoryCompressor(summaryModel);
        this.extractor = new MemoryExtractor(extractModel);

        // 初始化默认用户
        getUserContext("default_user").initSession();
    }

    /**
     * 为指定用户准备完整的 SystemPrompt（基础提示词 + 长期记忆上下文）。
     * 在每次 AgentLoop 执行前调用。
     */
    public String prepareSystemPrompt(String userId, String baseSystemPrompt) {
        // 确保用户的 Session 已初始化
        UserMemoryContext ctx = getUserContext(userId);
        if (!sessionManager.hasActiveSession(userId)) {
            ctx.initSession();
        }

        // 注入长期记忆
        String longTermCtx = ctx.getLongTermContext();
        if (longTermCtx.isEmpty()) {
            return baseSystemPrompt;
        }
        return baseSystemPrompt + "\n" + longTermCtx;
    }

    /**
     * 每轮对话完成后调用：提取长期记忆、检查压缩。
     */
    public void onTurnComplete(String userId, List<ChatMessage> currentMessages,
                                int tokenEstimate, String userMessage, String aiResponse) {
        onTurnComplete(userId, currentMessages, tokenEstimate, userMessage, aiResponse, null);
    }

    /**
     * 每轮对话完成后调用：提取长期记忆、检查压缩，并在 TEXT 主 Agent trace 中记录记忆链路。
     */
    public void onTurnComplete(String userId, List<ChatMessage> currentMessages,
                                int tokenEstimate, String userMessage, String aiResponse,
                                AgentTraceRecorder trace) {
        UserMemoryContext ctx = getUserContext(userId);

        // 1. 提取长期记忆
        ctx.extractAndStore(userMessage, aiResponse, trace);

        // 2. 检查压缩
        List<ChatMessage> compressed = ctx.compressIfNeeded(currentMessages, tokenEstimate, trace);
        if (compressed != currentMessages) {
            // 发生了压缩，持久化
            String memoryId = ctx.currentMemoryId();
            if (memoryId != null) {
                sessionStore.updateMessages(memoryId, compressed);
            }
        }
    }

    /**
     * 主动开启新 Session（用户指令触发）。
     */
    public String startNewSession(String userId) {
        UserMemoryContext ctx = getUserContext(userId);
        String oldSessionId = ctx.currentSessionId();
        ctx.startNewSession();
        Log.d(TAG, "New session started for user " + userId
                + " (old: " + oldSessionId + ")");
        return ctx.currentSessionId();
    }

    /**
     * 程序关闭时调用：结束所有活跃 Session。
     */
    public void shutdown() {
        for (Map.Entry<String, UserMemoryContext> entry : userContexts.entrySet()) {
            entry.getValue().endSession();
        }
        userContexts.clear();
        Log.d(TAG, "MemoryOrchestrator shut down");
    }

    // ── 会话管理门面 ──

    public List<SessionMemoryStore.SessionInfo> listSessions(String userId) {
        return sessionStore.listSessions(userId);
    }

    public SessionMemoryStore.SessionInfo getActiveSession(String userId) {
        return sessionStore.getActiveSession(userId);
    }

    public SessionMemoryStore.SessionInfo getSession(String userId, String sessionId) {
        return sessionStore.getSession(userId, sessionId);
    }

    public String createConversationSession(String userId, String title,
                                            String personaId, String sourceApp) {
        getUserContext(userId);
        String sessionId = sessionManager.createConversationSession(userId, title, personaId, sourceApp);
        Log.d(TAG, "Conversation session created for user " + userId + ": " + sessionId);
        return sessionId;
    }

    public boolean switchSession(String userId, String sessionId) {
        boolean switched = sessionManager.switchSession(userId, sessionId);
        if (switched) {
            Log.d(TAG, "Session switched for user " + userId + ": " + sessionId);
        }
        return switched;
    }

    public boolean deleteSession(String userId, String sessionId) {
        return sessionStore.deleteSession(userId, sessionId);
    }

    // ── 内部 ──

    private UserMemoryContext getUserContext(String userId) {
        return userContexts.computeIfAbsent(userId, id -> {
            UserMemoryContext ctx = new UserMemoryContext(
                    id, sessionManager, compressor, extractor, longTermStore);
            Log.d(TAG, "Created memory context for user: " + id);
            return ctx;
        });
    }

    // ── 访问器（供 MemoryPreProcessor/PostProcessor 使用） ──

    SessionMemoryStore sessionStore() { return sessionStore; }
    LongTermMemoryStore longTermStore() { return longTermStore; }
    public UserMemoryContext getUserContextDirect(String userId) {
        return userContexts.get(userId);
    }
}

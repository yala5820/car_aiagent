package com.hirain.aiagent.memory;

import android.content.Context;
import android.util.Log;

import com.hirain.aiagent.trace.AgentTraceRecorder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
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
public class MemoryOrchestrator implements SessionIdResolver, ContextMemoryGateway {

    private static final String TAG = "MemoryOrchestrator";

    private final SessionMemoryStore sessionStore;
    private final SessionManager sessionManager;
    private final LongTermMemoryStore longTermStore;
    private final MemoryCompressor compressor;
    private final MemoryExtractor extractor;
    private final SessionChatMemoryProvider sessionChatMemoryProvider;

    private final ConcurrentHashMap<String, UserMemoryContext> userContexts = new ConcurrentHashMap<>();

    public MemoryOrchestrator(Context context, ChatModel summaryModel, ChatModel extractModel) {
        this.sessionStore = new SessionMemoryStore(context);
        this.sessionManager = new SessionManager(sessionStore);
        this.longTermStore = new LongTermMemoryStore(context);
        this.compressor = new MemoryCompressor(summaryModel);
        this.extractor = new MemoryExtractor(extractModel);
        this.sessionChatMemoryProvider = new SessionChatMemoryProvider(sessionStore, 50);

        // 初始化默认用户
        getUserContext("default_user").initSession();
    }

    /**
     * 准备 SystemPrompt（基础提示词 + 长期记忆上下文）。
     * <p>
     * 注意：session 身份已由 Runtime 前置 resolveSessionId 链路决定，
     * 此方法不再负责初始化 session，仅注入长期记忆。
     */
    public String prepareSystemPrompt(String userId, String baseSystemPrompt) {
        UserMemoryContext ctx = getUserContext(userId);
        // 注入长期记忆（无 session 初始化 — 由 Runtime 的 sessionId 解析链路决定）
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
     * <p>
     * 注意：此重载压缩写回直接调用 {@code sessionStore.updateMessages()}，
     * 不通过 {@link SessionChatMemoryProvider#replaceMessages}，因此不会同步 provider 缓存中的 live ChatMemory。
     * 这是 legacy 路径的限制。Phase 4 起 TEXT 主路径必须使用带 {@code sessionId} 的重载。
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
     * 每轮对话完成后调用：带 sessionId 的重载。
     * <p>
     * 设计原因：Phase 4 起 TEXT 主路径使用此重载，压缩写回通过
     * {@link SessionChatMemoryProvider#replaceMessages} 同步 live ChatMemory 与 store。
     *
     * @param sessionId Phase 0 已解析的 sessionId（非空）
     */
    public void onTurnComplete(String userId, String sessionId, List<ChatMessage> currentMessages,
                                int tokenEstimate, String userMessage, String aiResponse,
                                AgentTraceRecorder trace) {
        UserMemoryContext ctx = getUserContext(userId);

        // 1. 提取长期记忆
        ctx.extractAndStore(userMessage, aiResponse, trace);

        // 2. 检查压缩（通过 provider 替换，同步 live ChatMemory 与 store）
        List<ChatMessage> compressed = ctx.compressIfNeeded(currentMessages, tokenEstimate, trace);
        if (compressed != currentMessages) {
            sessionChatMemoryProvider.replaceMessages(sessionId, compressed);
        }
    }

    @Override
    public void extractTurnMemory(String userId, String sessionId,
                                   String userMessage, String aiResponse,
                                   AgentTraceRecorder trace) {
        UserMemoryContext ctx = getUserContext(userId);
        ctx.extractAndStore(userMessage, aiResponse, trace);
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

    /**
     * 解析本轮请求的真实 sessionId。
     * <p>
     * 若请求携带了非空 sessionId，先确保当前 userId 的 metadata 指针指向该 session（如不存在则创建）；
     * 否则查询当前用户的 active session；无 active session 时自动创建新会话。
     * 确保 TEXT 主路径始终有确定的 sessionId，且 user -> session 指针保持一致。
     */
    @Override
    public String resolveSessionId(String userId, String requestedSessionId,
                                   String title, String personaId, String sourceApp) {
        String normalizedUserId = isBlank(userId) ? "default_user" : userId.trim();
        if (!isBlank(requestedSessionId)) {
            String trimmed = requestedSessionId.trim();
            // 确保当前 userId 的 metadata 指针指向该 session
            SessionMemoryStore.SessionInfo existing = sessionStore.getSession(normalizedUserId, trimmed);
            if (existing != null) {
                // 已有 metadata → 切换 active 指针
                switchSession(normalizedUserId, trimmed);
            } else {
                // 没有 metadata → 创建元数据行并激活，使该 session 对当前 userId 可见
                boolean created = sessionStore.createActiveSession(
                        normalizedUserId, trimmed, title, personaId, sourceApp);
                if (!created) {
                    throw new IllegalStateException(
                            "Failed to create active session for userId=" + normalizedUserId
                                    + " sessionId=" + trimmed);
                }
                getUserContext(normalizedUserId);
                sessionManager.cacheSession(normalizedUserId, trimmed);
            }
            return trimmed;
        }
        SessionMemoryStore.SessionInfo active = sessionStore.getActiveSession(normalizedUserId);
        if (active != null && !isBlank(active.sessionId)) {
            return active.sessionId;
        }
        return createConversationSession(normalizedUserId, title, personaId, sourceApp);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
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

    /**
     * 删除共享 session — 同时清理 provider 缓存、短期消息 store 和 metadata。
     * <p>
     * 设计原因：仅删除 SQLite 会导致已缓存的 live ChatMemory 残留旧消息，
     * 后续同 sessionId 的 {@link #chatMemoryForSession} 会命中缓存并返回已删除数据。
     */
    public boolean deleteSession(String userId, String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            return false;
        }
        // 先执行 store 层面的事务删除（metadata + 短期消息），确保存在性校验通过后再 evict 缓存
        // 设计原因：避免 store 删除失败后 provider 缓存已被清空导致的数据状态不一致
        if (sessionStore.deleteSession(userId, sessionId)) {
            sessionChatMemoryProvider.clear(sessionId.trim());
            return true;
        }
        return false;
    }

    // ── 短期记忆管理（SessionChatMemoryProvider + Context 预留读取） ──

    /**
     * 获取指定 session 的 ChatMemory（TEXT 主路径使用）。
     *
     * @param sessionId  Phase 0 已解析的非空 sessionId
     * @param maxMessages 窗口大小（通常来自 config.maxMemoryMessages()）
     */
    public dev.langchain4j.memory.ChatMemory chatMemoryForSession(String sessionId, int maxMessages) {
        return sessionChatMemoryProvider.getOrCreate(sessionId, maxMessages);
    }

    /**
     * 清除指定 session 的短期消息和缓存。
     */
    public void clearSessionMemory(String sessionId) {
        sessionChatMemoryProvider.clear(sessionId);
    }

    /**
     * 从底层 store 读取指定 session 的短期消息快照（不经过 provider 缓存）。
     * <p>
     * 设计原因：Context 模块需要只读读取当前 session 的记忆数据，
     * 但不应当触发 provider 缓存的热加载。
     */
    public List<ChatMessage> readSessionMessages(String sessionId) {
        return sessionStore.getMessages(SessionMemoryIds.shortTermMemoryId(sessionId));
    }

    /**
     * 获取指定 session 的只读记忆快照（Context 预留接口）。
     * <p>
     * 设计原因：Memory 模块只负责提供数据，不决定这些数据是否进入本轮 prompt。
     */
    public MemorySnapshot getMemorySnapshot(String sessionId) {
        List<ChatMessage> messages = readSessionMessages(sessionId);
        String summary = extractExistingSummary(messages);
        return new MemorySnapshot(sessionId, messages, estimateTokensForSnapshot(messages), summary);
    }

    /**
     * 返回指定 session 的短期记忆快照（原始 sessionId，不携带 {@code memory:} 前缀）。
     * <p>
     * 设计原因：Context 模块需要通过原始 sessionId 关联会话，不需要 store 层的前缀。
     * 新 Provider 应优先调用此方法而非 {@link #getMemorySnapshot(String)}。
     */
    public MemorySnapshot sessionMemorySnapshot(String sessionId, int maxMessages) {
        List<ChatMessage> messages = readSessionMessages(sessionId);
        int max = Math.max(maxMessages, 1);
        List<ChatMessage> windowed = messages.size() > max
                ? messages.subList(messages.size() - max, messages.size())
                : messages;
        return new MemorySnapshot(sessionId, windowed,
                estimateTokensForSnapshot(windowed), extractExistingSummary(windowed), true);
    }

    /**
     * 返回指定用户的长期记忆快照。
     * <p>
     * 设计原因：Context 模块需要结构化条目而非已拼入 System Prompt 的字符串。
     * 新 Provider 禁止调用 {@link #prepareSystemPrompt(String, String)}。
     */
    public LongTermMemorySnapshot longTermMemorySnapshot(String userId) {
        Map<String, List<MemoryEntry>> userMemories = longTermStore.getUserMemories(userId);
        List<MemoryEntry> allEntries = new ArrayList<>();
        long version = 0;
        for (Map.Entry<String, List<MemoryEntry>> group : userMemories.entrySet()) {
            List<MemoryEntry> sorted = group.getValue();
            allEntries.addAll(sorted);
            for (MemoryEntry e : sorted) {
                version += (long) (e.key() != null ? e.key().hashCode() : 0);
            }
        }
        return new LongTermMemorySnapshot(userId, allEntries, version);
    }

    /**
     * 获取指定 session 的压缩摘要（截断至 maxChars）。
     * <p>
     * 设计原因：Context 模块可能只需要摘要而非全量消息，此方法提供 best-effort 结果。
     */
    public String getSummarizedMemory(String sessionId, int maxChars) {
        String text = extractExistingSummary(readSessionMessages(sessionId));
        if (text.length() <= maxChars) return text;
        return text.substring(0, Math.max(0, maxChars));
    }

    /**
     * 生成短期记忆压缩计划（不调用模型、不写 Store）。
     * <p>
     * Phase 4 生产影子链路只调用此方法，不触发真实压缩写回。
     */
    public MemoryCompactionPlan planSessionCompaction(String sessionId, int targetTokens) {
        List<ChatMessage> currentMessages = readSessionMessages(sessionId);
        int currentTokens = estimateTokensForSnapshot(currentMessages);
        List<ChatMessage> proposed = compressor.planCompact(currentMessages, targetTokens);
        int proposedTokens = estimateTokensForSnapshot(proposed);
        return new MemoryCompactionPlan(sessionId, targetTokens,
                currentTokens, proposed, proposedTokens);
    }

    /**
     * 执行压缩计划 — 调用摘要模型并原子写回 Store。
     * <p>
     * Phase 4 仅测试环境调用，生产影子不调用此方法。
     *
     * @param plan 压缩计划
     * @param trace Trace recorder（可为 null）
     * @return 压缩结果
     */
    public MemoryCompactionResult executeCompactionPlan(MemoryCompactionPlan plan,
                                                         AgentTraceRecorder trace) {
        if (plan == null) {
            return MemoryCompactionResult.notExecuted();
        }
        try {
            sessionChatMemoryProvider.replaceMessages(plan.sessionId(), plan.proposedMessages());
            return new MemoryCompactionResult(true, true,
                    plan.currentTokens(), plan.estimatedTokens(), true, null);
        } catch (Exception e) {
            return new MemoryCompactionResult(true, false,
                    plan.currentTokens(), plan.estimatedTokens(), false, e.getMessage());
        }
    }

    /**
     * 从消息列表中查找已有的【对话摘要】。
     * <p>
     * 设计原因：当前摘要仍存放在普通 UserMessage 中，尚不是稳定结构化字段。
     * 此方法只读取已有结果，不主动触发压缩。
     */
    private static String extractExistingSummary(List<ChatMessage> messages) {
        for (ChatMessage message : messages) {
            if (message instanceof UserMessage) {
                String text = ((UserMessage) message).singleText();
                if (text != null && text.startsWith("【对话摘要】")) {
                    return text;
                }
            }
        }
        return "";
    }

    /** 粗略估算消息列表 Token 数（中英文混合约 2 chars/token）。 */
    private static int estimateTokensForSnapshot(List<ChatMessage> messages) {
        int chars = 0;
        for (ChatMessage msg : messages) {
            if (msg instanceof UserMessage) {
                chars += ((UserMessage) msg).singleText().length();
            } else if (msg instanceof AiMessage) {
                chars += ((AiMessage) msg).text() != null ? ((AiMessage) msg).text().length() : 0;
            } else if (msg instanceof SystemMessage) {
                chars += ((SystemMessage) msg).text().length();
            } else if (msg instanceof ToolExecutionResultMessage) {
                chars += ((ToolExecutionResultMessage) msg).text().length();
            }
        }
        return chars / 2;
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

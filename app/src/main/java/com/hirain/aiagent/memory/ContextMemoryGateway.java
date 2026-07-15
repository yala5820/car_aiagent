package com.hirain.aiagent.memory;

import com.hirain.aiagent.trace.AgentTraceRecorder;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.memory.ChatMemory;

import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * TEXT AgentLoop、Context Provider 和测试共用的窄 Memory 接口。
 * <p>
 * 暴露快照、session ChatMemory、turn 提取和压缩协议，不暴露 SQLite、SQLiteOpenHelper 或 Android Context。
 * MemoryOrchestrator 实现此接口，测试使用纯内存 fake。
 */
public interface ContextMemoryGateway {

    /** 返回该 session 的 live LangChain4j ChatMemory。 */
    ChatMemory chatMemoryForSession(String sessionId, int maxMessages);

    /** TEXT 主入口：完整持久化历史，不使用固定消息窗口。 */
    default ChatMemory chatMemoryForSession(String sessionId) {
        return chatMemoryForSession(sessionId, Integer.MAX_VALUE);
    }

    /** 返回不可变 MemorySnapshot，供 Context Provider 每轮读取。 */
    MemorySnapshot sessionMemorySnapshot(String sessionId, int maxMessages);

    /** TEXT 主入口：返回经过 Memory 序列校验的完整 Session 历史。 */
    default MemorySnapshot sessionMemorySnapshot(String sessionId) {
        return sessionMemorySnapshot(sessionId, Integer.MAX_VALUE);
    }

    /** 返回用户级不可变 LongTermMemorySnapshot，供长期记忆 Provider 读取。 */
    LongTermMemorySnapshot longTermMemorySnapshot(String userId);

    /** 只执行长期记忆提取，不执行阈值压缩。 */
    void extractTurnMemory(String userId, String sessionId, String userMessage,
                           String aiResponse, AgentTraceRecorder trace);

    /** 生成短期记忆压缩计划（不调用模型、不写 Store）。 */
    MemoryCompactionPlan planSessionCompaction(String sessionId, int targetTokens);

    /** 执行压缩计划（摘要模型 + 原子写回）。 */
    MemoryCompactionResult executeCompactionPlan(MemoryCompactionPlan plan, AgentTraceRecorder trace);

    /** 支持摘要返回后、原子写回前的取消检查。 */
    default MemoryCompactionResult executeCompactionPlan(
            MemoryCompactionPlan plan, AgentTraceRecorder trace,
            BooleanSupplier cancelChecker) {
        return executeCompactionPlan(plan, trace);
    }
}

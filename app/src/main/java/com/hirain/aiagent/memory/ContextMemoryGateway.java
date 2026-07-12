package com.hirain.aiagent.memory;

import com.hirain.aiagent.trace.AgentTraceRecorder;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.memory.ChatMemory;

import java.util.List;

/**
 * TEXT AgentLoop、Context Provider 和测试共用的窄 Memory 接口。
 * <p>
 * 暴露快照、session ChatMemory、turn 提取和压缩协议，不暴露 SQLite、SQLiteOpenHelper 或 Android Context。
 * MemoryOrchestrator 实现此接口，测试使用纯内存 fake。
 */
public interface ContextMemoryGateway {

    /** 返回该 session 的 live LangChain4j ChatMemory。 */
    ChatMemory chatMemoryForSession(String sessionId, int maxMessages);

    /** 返回不可变 MemorySnapshot，供 Context Provider 每轮读取。 */
    MemorySnapshot sessionMemorySnapshot(String sessionId, int maxMessages);

    /** 返回用户级不可变 LongTermMemorySnapshot，供长期记忆 Provider 读取。 */
    LongTermMemorySnapshot longTermMemorySnapshot(String userId);

    /** 只执行长期记忆提取，不执行阈值压缩。 */
    void extractTurnMemory(String userId, String sessionId, String userMessage,
                           String aiResponse, AgentTraceRecorder trace);

    /** 生成短期记忆压缩计划（不调用模型、不写 Store）。Phase 3 正式使用。 */
    MemoryCompactionPlan planSessionCompaction(String sessionId, int targetTokens);

    /** 执行压缩计划（摘要模型 + 原子写回）。Phase 3 正式使用。 */
    MemoryCompactionResult executeCompactionPlan(MemoryCompactionPlan plan, AgentTraceRecorder trace);
}

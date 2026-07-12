package com.hirain.aiagent.memory;

/**
 * Memory 持久化异常 — SessionMemoryStore 完整替换失败时抛出。
 * <p>
 * 设计原因：防止 SQLite/序列化异常被静默吞掉导致 cache 与 store 不一致。
 */
public class MemoryPersistenceException extends RuntimeException {

    private final String memoryId;

    public MemoryPersistenceException(String memoryId, String message, Throwable cause) {
        super(message, cause);
        this.memoryId = memoryId;
    }

    public MemoryPersistenceException(String memoryId, String message) {
        super(message);
        this.memoryId = memoryId;
    }

    public String memoryId() { return memoryId; }
}

package com.hirain.aiagent.rag.indexer.chunk;

import com.hirain.aiagent.rag.indexer.util.Sha256;

/** 稳定 ID 只使用明确字段与 SHA-256，禁止依赖对象地址、hashCode 或构建时间。 */
public final class StableIdGenerator {
    public static final int ALGORITHM_VERSION = 1;

    public String parentId(ParentChunk parent) {
        return hash("parent", parent.documentId(), parent.headingPath(), String.valueOf(parent.ordinal()), String.valueOf(ALGORITHM_VERSION));
    }

    public String childId(ChildChunk child, String parentId) {
        String contentHash = Sha256.ofUtf8(new ChunkCanonicalizer().canonicalize(child.text()));
        return hash("child", parentId, String.valueOf(child.ordinal()), contentHash, String.valueOf(ALGORITHM_VERSION));
    }

    private String hash(String... fields) {
        return Sha256.ofUtf8(String.join("\u001F", fields));
    }
}

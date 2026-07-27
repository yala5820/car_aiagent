package com.hirain.aiagent.rag.indexer.store;

import com.hirain.aiagent.rag.indexer.lexical.LexicalPosting;
import com.hirain.aiagent.rag.store.entity.LexicalTermEntity;

import java.util.List;
import java.util.Map;

/** G402 在获得本次 Store 的 Child long ID 后才调用，禁止把稳定 Chunk ID 写入 ObjectBox posting。 */
public final class LexicalTermEntityMapper {
    public LexicalTermEntity map(String field, String term, List<LexicalPosting> postings, Map<String, Long> childEntityIds) {
        LexicalTermEntity entity = new LexicalTermEntity();
        entity.field = field; entity.rawTerm = term; entity.term = field + ":" + term; entity.documentFrequency = postings.size();
        entity.chunkEntityIds = new long[postings.size()]; entity.termFrequencies = new int[postings.size()];
        for (int index = 0; index < postings.size(); index++) {
            LexicalPosting posting = postings.get(index); Long childId = childEntityIds.get(posting.childId());
            if (childId == null || childId <= 0) throw new IllegalArgumentException("posting 引用未知 Child Entity");
            entity.chunkEntityIds[index] = childId; entity.termFrequencies[index] = posting.termFrequency();
        }
        return entity;
    }

    /** 兼容旧调用方，旧 posting 统一视为 BODY。 */
    public LexicalTermEntity map(String term, List<LexicalPosting> postings, Map<String, Long> childEntityIds) {
        return map("BODY", term, postings, childEntityIds);
    }
}

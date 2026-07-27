package com.hirain.aiagent.rag.indexer.pipeline;

import com.hirain.aiagent.rag.indexer.lexical.LexicalDocument;
import com.hirain.aiagent.rag.indexer.lexical.LexicalIndex;
import com.hirain.aiagent.rag.indexer.lexical.LexicalIndexBuilder;

import java.util.ArrayList;
import java.util.List;

/** 只将可检索 Child 写入倒排中间模型，并复用 Stable Child ID 作为 posting 引用。 */
public final class LexicalStageRunner {
    private final LexicalIndexBuilder builder;
    public LexicalStageRunner(LexicalIndexBuilder builder) { this.builder = builder; }
    public LexicalIndex index(DocumentBuildState state, ChunkStableIds ids) {
        if (state.chunkResult() == null) throw new IllegalStateException("Chunk 阶段尚未完成");
        List<LexicalDocument> documents = new ArrayList<>();
        for (var child : state.chunkResult().children()) {
            String id = ids.childIds().get(child); if (id == null) throw new IllegalStateException("Child 稳定 ID 缺失");
            var parent = state.chunkResult().parents().stream().filter(value -> value.ordinal() == child.parentOrdinal()).findFirst().orElseThrow();
            String title = com.hirain.aiagent.rag.store.HeadingTitleResolver.parentTitle(parent.headingPath(), parent.title());
            documents.add(new LexicalDocument(id, title, child.text()));
        }
        return builder.build(documents);
    }
}

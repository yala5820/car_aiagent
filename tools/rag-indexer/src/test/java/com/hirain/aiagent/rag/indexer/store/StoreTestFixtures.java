package com.hirain.aiagent.rag.indexer.store;

import com.hirain.aiagent.rag.indexer.chunk.ParentChunk;
import com.hirain.aiagent.rag.indexer.corpus.CorpusDocumentDefinition;
import com.hirain.aiagent.rag.indexer.corpus.KnowledgeScopeDefinition;
import com.hirain.aiagent.rag.indexer.model.SourceFormat;
import com.hirain.aiagent.rag.indexer.model.SourceLocator;

import java.util.List;

final class StoreTestFixtures {
    static final KnowledgeScopeDefinition SCOPE = new KnowledgeScopeDefinition("V1", "2026", "CN", "1.0", "base");

    static CorpusDocumentDefinition document(String format) {
        String suffix = "PDF".equals(format) ? "pdf" : "STATIC_HTML".equals(format) ? "html" : "md";
        return new CorpusDocumentDefinition("manual-1", "车辆手册", "OWNER", "1.0", "zh-CN", format,
                "nested/manual." + suffix, "a".repeat(64), SCOPE);
    }

    static ParentChunk parent(SourceLocator locator) {
        return new ParentChunk(0, "manual-1", "车辆手册", "制动 > 制动液", "制动液说明", locator, List.of(), List.of());
    }

    static SourceLocator locator(SourceFormat format) {
        return switch (format) {
            case PDF -> new SourceLocator(format, 2, 3, "", 0, 0, "制动 > 制动液", 1);
            case STATIC_HTML -> new SourceLocator(format, 0, 0, "brake-fluid", 0, 0, "制动 > 制动液", 1);
            case MARKDOWN -> new SourceLocator(format, 0, 0, "", 8, 10, "制动 > 制动液", 1);
        };
    }

    private StoreTestFixtures() { }
}

package com.hirain.aiagent.rag.indexer.parser;

import com.hirain.aiagent.rag.indexer.model.ParseResult;
import com.hirain.aiagent.rag.indexer.model.SourceDocument;
import com.hirain.aiagent.rag.indexer.model.SourceFormat;

/** Parser 只负责源格式到统一结构，禁止在此层 Chunk、Embedding、网络访问或写 Store。 */
public interface DocumentParser {
    SourceFormat sourceFormat();

    /**
     * 默认实现保留 Registry 的轻量格式识别契约；实际 Parser 必须覆写，避免错误地将未实现解析视为成功。
     */
    default ParseResult parse(SourceDocument document) {
        throw new UnsupportedOperationException("Parser 未实现解析：" + sourceFormat());
    }
}

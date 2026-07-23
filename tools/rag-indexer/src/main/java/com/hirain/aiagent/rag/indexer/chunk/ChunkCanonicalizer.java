package com.hirain.aiagent.rag.indexer.chunk;

import java.text.Normalizer;

/** 统一 Unicode NFC 与空白规则，所有稳定 ID、Embedding 与排序前均使用该规范化结果。 */
public final class ChunkCanonicalizer {
    public String canonicalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFC).replaceAll("\\s+", " ").trim();
    }
}

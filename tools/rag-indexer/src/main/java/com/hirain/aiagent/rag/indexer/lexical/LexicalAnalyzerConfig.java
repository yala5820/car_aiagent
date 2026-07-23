package com.hirain.aiagent.rag.indexer.lexical;

import java.util.Set;

/**
 * Analyzer V1 的冻结规则。版本变更会使 postings 与 Android 查询规则同时失效，
 * 因此不能以运行时 Flag 临时覆盖。
 */
public record LexicalAnalyzerConfig(
        String version,
        int minCjkGram,
        int maxCjkGram,
        Set<String> stopWords,
        boolean keepNumericTokens) {
    public static LexicalAnalyzerConfig v1() {
        return new LexicalAnalyzerConfig("lexical-analyzer-v1", 2, 3,
                Set.of("的", "了", "和", "与", "及", "the", "a", "an"), true);
    }
}

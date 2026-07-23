package com.hirain.aiagent.rag.retrieval;

import com.hirain.aiagent.rag.config.RagRetrievalConfig;

/** BM25 纯数学实现；后续 Store 查询负责提供 posting 的 tf/df 与 Child 文档长度。 */
public final class Bm25Searcher {
    private final RagRetrievalConfig config;
    public Bm25Searcher(RagRetrievalConfig config) { this.config = config; }
    public double score(int termFrequency, int documentFrequency, int corpusDocumentCount, int documentLength, double averageDocumentLength) {
        if (termFrequency <= 0 || documentFrequency <= 0 || corpusDocumentCount <= 0 || documentLength < 0 || averageDocumentLength <= 0d) return 0d;
        double idf = Math.log(1d + (corpusDocumentCount - documentFrequency + 0.5d) / (documentFrequency + 0.5d));
        double denominator = termFrequency + config.bm25K1() * (1d - config.bm25B() + config.bm25B() * documentLength / averageDocumentLength);
        return idf * termFrequency * (config.bm25K1() + 1d) / denominator;
    }
}

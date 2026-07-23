package com.hirain.aiagent.rag.retrieval;

import com.hirain.aiagent.rag.config.RagRetrievalConfig;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** BM25 分值只依赖 posting 统计，不能退化为 ObjectBox 文本 contains 查询。 */
public class Bm25SearcherTest {
    @Test public void favorsHigherTermFrequencyAndRejectsInvalidStatistics() { Bm25Searcher searcher = new Bm25Searcher(RagRetrievalConfig.v1()); double low = searcher.score(1, 2, 100, 100, 100d); double high = searcher.score(3, 2, 100, 100, 100d); assertTrue(high > low); assertEquals(0d, searcher.score(0, 2, 100, 100, 100d), 0d); }
}

package com.hirain.aiagent.rag.retrieval;

import com.hirain.aiagent.rag.config.RagRetrievalConfig;
import org.junit.Test;
import static org.junit.Assert.*;

/** Query 长度以 Unicode Code Point 计数，确保代理对不会在截断或计数时失真。 */
public class QueryNormalizerTest {
    @Test public void normalizesUnicodeWhitespaceAndCase() { QueryNormalizationResult value = new QueryNormalizer(RagRetrievalConfig.v1()).normalize("  ＥＳＰ　 ALERT  "); assertTrue(value.valid()); assertEquals("esp alert", value.normalizedQuery()); assertEquals(64, value.queryHash().length()); }
    @Test public void rejectsEmptyAndOverlongCodePointQueries() { QueryNormalizer normalizer = new QueryNormalizer(RagRetrievalConfig.v1()); assertEquals("QUERY_EMPTY", normalizer.normalize(" \t ").failureReason()); StringBuilder query = new StringBuilder(); for (int index = 0; index < 513; index++) query.appendCodePoint(0x1F600); assertEquals("QUERY_TOO_LONG", normalizer.normalize(query.toString()).failureReason()); }
}

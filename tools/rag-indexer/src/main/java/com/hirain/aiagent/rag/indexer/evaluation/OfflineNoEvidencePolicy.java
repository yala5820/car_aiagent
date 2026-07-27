package com.hirain.aiagent.rag.indexer.evaluation;

import com.hirain.aiagent.rag.store.entity.KnowledgeChunkEntity;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Eval V2 的初始无证据阈值：要求 Query 去除车型范围词后，至少与 Parent 标题/正文共享一段四字以上语义短语。
 * 这是可校准的 TEST_ONLY 诊断策略，不把字符串重合伪装成答案正确率，也不改变 Android 线上检索排序。
 */
public final class OfflineNoEvidencePolicy {
    private static final int MIN_SHARED_CHARS = 5;
    private static final Set<String> SCOPE_WORDS = Set.of("modely", "model y", "2026", "中国大陆", "后驱版", "后轮驱动");

    public boolean hasSufficientEvidence(String query, List<KnowledgeChunkEntity> parents) {
        String normalizedQuery = normalize(removeScopeWords(query));
        if (normalizedQuery.isBlank()) return false;
        for (KnowledgeChunkEntity parent : parents == null ? List.<KnowledgeChunkEntity>of() : parents) {
            String text = normalize(removeScopeWords((parent.headingPath == null ? "" : parent.headingPath) + " "
                    + (parent.content == null ? "" : parent.content)));
            if (longestCommonRun(normalizedQuery, text) >= MIN_SHARED_CHARS || sharedLatinToken(normalizedQuery, text)) return true;
        }
        return false;
    }

    private static String removeScopeWords(String value) {
        String output = value == null ? "" : value.toLowerCase(Locale.ROOT);
        for (String word : SCOPE_WORDS) output = output.replace(word, "");
        return output;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private static int longestCommonRun(String left, String right) {
        int[] a = left.codePoints().toArray();
        int[] b = right.codePoints().toArray();
        int best = 0;
        for (int i = 0; i < a.length; i++) {
            for (int j = 0; j < b.length; j++) {
                int length = 0;
                while (i + length < a.length && j + length < b.length && a[i + length] == b[j + length]) length++;
                best = Math.max(best, length);
            }
        }
        return best;
    }

    private static boolean sharedLatinToken(String left, String right) {
        Set<String> a = new HashSet<>();
        for (String value : left.split("[^a-z0-9]+")) if (value.length() >= 3) a.add(value);
        for (String value : right.split("[^a-z0-9]+")) if (value.length() >= 3 && a.contains(value)) return true;
        return false;
    }
}

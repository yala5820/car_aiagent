package com.hirain.aiagent.rag.indexer.chunk;

import com.hirain.aiagent.rag.contract.RagTokenEstimator;

/**
 * 离线端的 V2 Token 估算适配器。
 *
 * <p>V1 的历史 Bundle 仍使用 {@link #LEGACY_VERSION} 所代表的旧算法解释其 Manifest；新构建
 * 必须经 V2 配置显式声明后才使用本类默认算法，不能把两种语义混为同一个版本。</p>
 */
public final class TokenEstimator {
    public static final int LEGACY_VERSION = 1;
    public static final int VERSION = 2;
    public static final String ALGORITHM_VERSION = RagTokenEstimator.VERSION;

    private final RagTokenEstimator delegate = new RagTokenEstimator();

    public int estimate(String text) {
        return delegate.estimate(text).totalTokens();
    }

    /** 仅用于读取或审计 V1 历史记录；新 V2 代码不得调用。 */
    public static int estimateV1ForHistoricalBundle(String text) {
        if (text == null || text.isEmpty()) return 0;
        int tokens = 0;
        int latinRun = 0;
        for (int index = 0; index < text.length(); index++) {
            char value = text.charAt(index);
            if (Character.UnicodeScript.of(value) == Character.UnicodeScript.HAN) {
                tokens++;
                latinRun = 0;
            } else if (!Character.isWhitespace(value)) {
                latinRun++;
                if (latinRun % 4 == 1) {
                    tokens++;
                }
            }
        }
        return tokens;
    }
}

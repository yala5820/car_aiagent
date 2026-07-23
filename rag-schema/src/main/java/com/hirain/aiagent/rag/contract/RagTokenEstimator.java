package com.hirain.aiagent.rag.contract;

/**
 * RAG V2 唯一 Token 预算估算器。
 *
 * <p>离线 Chunk、Rerank 候选和 Android Parent Evidence 必须使用同一算法，避免同一段
 * 文本在构建端与运行端落入不同预算区间。该算法只用于可解释的预算控制，不调用远程
 * tokenizer，也不能被描述为模型的精确 Token 数。</p>
 */
public final class RagTokenEstimator {
    public static final String VERSION = "rag-token-estimator-v2";

    /**
     * 估算规则：CJK 字符逐个计数；连续 Latin 字母/数字每四个代码点计一个 Token；
     * 每个标点、换行和其他可见符号各计一个 Token；普通空白不计数。CRLF 仅计一个换行。
     */
    public RagTokenEstimate estimate(String text) {
        if (text == null || text.isEmpty()) {
            return new RagTokenEstimate(VERSION, 0, 0, 0, 0, 0, 0);
        }
        int cjk = 0;
        int latinAndDigits = 0;
        int punctuation = 0;
        int newlines = 0;
        int others = 0;
        int latinRunCodePoints = 0;

        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);

            if (codePoint == '\r') {
                latinAndDigits += roundedLatinTokens(latinRunCodePoints);
                latinRunCodePoints = 0;
                if (offset < text.length() && text.charAt(offset) == '\n') {
                    offset++;
                }
                newlines++;
            } else if (codePoint == '\n') {
                latinAndDigits += roundedLatinTokens(latinRunCodePoints);
                latinRunCodePoints = 0;
                newlines++;
            } else if (isCjk(codePoint)) {
                latinAndDigits += roundedLatinTokens(latinRunCodePoints);
                latinRunCodePoints = 0;
                cjk++;
            } else if (isLatinLetterOrDigit(codePoint)) {
                latinRunCodePoints++;
            } else if (Character.isWhitespace(codePoint)) {
                latinAndDigits += roundedLatinTokens(latinRunCodePoints);
                latinRunCodePoints = 0;
            } else if (isPunctuation(codePoint)) {
                latinAndDigits += roundedLatinTokens(latinRunCodePoints);
                latinRunCodePoints = 0;
                punctuation++;
            } else {
                latinAndDigits += roundedLatinTokens(latinRunCodePoints);
                latinRunCodePoints = 0;
                others++;
            }
        }
        latinAndDigits += roundedLatinTokens(latinRunCodePoints);
        int total = cjk + latinAndDigits + punctuation + newlines + others;
        return new RagTokenEstimate(VERSION, total, cjk, latinAndDigits, punctuation, newlines, others);
    }

    private static int roundedLatinTokens(int codePoints) {
        return codePoints == 0 ? 0 : (codePoints + 3) / 4;
    }

    private static boolean isLatinLetterOrDigit(int codePoint) {
        return Character.isDigit(codePoint) || Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.LATIN;
    }

    private static boolean isCjk(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
    }

    private static boolean isPunctuation(int codePoint) {
        return switch (Character.getType(codePoint)) {
            case Character.CONNECTOR_PUNCTUATION,
                    Character.DASH_PUNCTUATION,
                    Character.START_PUNCTUATION,
                    Character.END_PUNCTUATION,
                    Character.INITIAL_QUOTE_PUNCTUATION,
                    Character.FINAL_QUOTE_PUNCTUATION,
                    Character.OTHER_PUNCTUATION -> true;
            default -> false;
        };
    }
}

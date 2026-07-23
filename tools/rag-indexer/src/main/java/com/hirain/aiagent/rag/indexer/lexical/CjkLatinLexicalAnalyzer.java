package com.hirain.aiagent.rag.indexer.lexical;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * V1 使用 Unicode NFC、中文连续片段的 bi/tri-gram 与大小写折叠的字母数字 Token。
 * 保留连字符、点和下划线的字母数字组合，避免故障码和版本号在离线/Android 两端被拆散。
 */
public final class CjkLatinLexicalAnalyzer implements LexicalAnalyzer {
    private static final Pattern CJK_RUN = Pattern.compile("[\\p{IsHan}]+");
    private static final Pattern LATIN_OR_CODE = Pattern.compile("[A-Za-z0-9]+(?:[._-][A-Za-z0-9]+)*");
    private final LexicalAnalyzerConfig config;

    public CjkLatinLexicalAnalyzer(LexicalAnalyzerConfig config) {
        this.config = config;
    }

    @Override
    public List<String> analyze(String text) {
        // NFKC 将全角字母和兼容字符折叠到查询端同样可生成的形式，避免全半角造成召回漂移。
        String normalized = Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFKC);
        List<String> terms = new ArrayList<>();
        Matcher cjkMatcher = CJK_RUN.matcher(normalized);
        while (cjkMatcher.find()) addCjkNgrams(cjkMatcher.group(), terms);

        Matcher latinMatcher = LATIN_OR_CODE.matcher(normalized);
        while (latinMatcher.find()) {
            String token = latinMatcher.group().toLowerCase(Locale.ROOT);
            if ((!config.keepNumericTokens() && token.chars().allMatch(Character::isDigit))
                    || config.stopWords().contains(token)) continue;
            terms.add(token);
        }
        addUppercasePhrases(normalized, terms);
        return List.copyOf(terms);
    }

    private void addCjkNgrams(String run, List<String> terms) {
        if (run.length() == 1) {
            if (!config.stopWords().contains(run)) terms.add(run);
            return;
        }
        for (int gram = config.minCjkGram(); gram <= config.maxCjkGram(); gram++) {
            for (int offset = 0; offset + gram <= run.length(); offset++) {
                String term = run.substring(offset, offset + gram);
                if (!config.stopWords().contains(term)) terms.add(term);
            }
        }
    }

    private static void addUppercasePhrases(String text, List<String> terms) {
        Matcher matcher = Pattern.compile("(?:\\b[A-Z]{2,}\\b(?:\\s+|$)){2,}").matcher(text);
        while (matcher.find()) {
            String phrase = matcher.group().trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
            if (!phrase.isEmpty()) terms.add(phrase);
        }
    }
}

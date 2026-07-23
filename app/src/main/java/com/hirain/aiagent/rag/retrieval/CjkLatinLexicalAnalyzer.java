package com.hirain.aiagent.rag.retrieval;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 与离线 CjkLatinLexicalAnalyzer V1 同步：NFKC、中文 bi/tri-gram、完整故障码和大写短语。 */
public final class CjkLatinLexicalAnalyzer implements LexicalAnalyzer {
    private static final Pattern CJK_RUN = Pattern.compile("[\\p{IsHan}]+");
    private static final Pattern LATIN_OR_CODE = Pattern.compile("[A-Za-z0-9]+(?:[._-][A-Za-z0-9]+)*");
    private static final Pattern UPPERCASE_PHRASE = Pattern.compile("(?:\\b[A-Z]{2,}\\b(?:\\s+|$)){2,}");
    private static final Set<String> STOP_WORDS = Set.of("的", "了", "和", "与", "及", "the", "a", "an");
    @Override public List<String> analyze(String text) {
        String normalized = Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFKC);
        List<String> terms = new ArrayList<>();
        Matcher cjk = CJK_RUN.matcher(normalized); while (cjk.find()) addCjk(cjk.group(), terms);
        Matcher latin = LATIN_OR_CODE.matcher(normalized); while (latin.find()) { String term = latin.group().toLowerCase(Locale.ROOT); if (!STOP_WORDS.contains(term)) terms.add(term); }
        Matcher phrase = UPPERCASE_PHRASE.matcher(normalized); while (phrase.find()) { String term = phrase.group().trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT); if (!term.isEmpty()) terms.add(term); }
        return List.copyOf(terms);
    }
    private static void addCjk(String run, List<String> terms) {
        if (run.length() == 1) { if (!STOP_WORDS.contains(run)) terms.add(run); return; }
        for (int length = 2; length <= 3; length++) for (int offset = 0; offset + length <= run.length(); offset++) { String term = run.substring(offset, offset + length); if (!STOP_WORDS.contains(term)) terms.add(term); }
    }
}

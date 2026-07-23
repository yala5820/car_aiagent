package com.hirain.aiagent.rag.indexer.parser.html;

/** 按 HTML 标题层级维护稳定 Heading Path，跳级不虚构缺失标题。 */
final class HtmlHeadingPathTracker {
    private final String[] headings = new String[6];

    void accept(int level, String text) {
        headings[level - 1] = text;
        for (int index = level; index < headings.length; index++) {
            headings[index] = null;
        }
    }

    String currentPath() {
        return java.util.Arrays.stream(headings).filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.joining(" > "));
    }
}

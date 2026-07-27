package com.hirain.aiagent.rag.store;

/** 跨离线构建端与 Android 端统一解析 Parent 二级标题，避免各端自行 split 造成标题漂移。 */
public final class HeadingTitleResolver {
    private HeadingTitleResolver() {
    }

    public static String parentTitle(String headingPath, String documentTitle) {
        String path = headingPath == null ? "" : headingPath.trim();
        String[] parts = path.isEmpty() ? new String[0] : path.split("\\s+>\\s+", -1);
        if (parts.length >= 2 && !parts[1].isBlank()) return parts[1].trim();
        if (parts.length == 1 && !parts[0].isBlank()) return parts[0].trim();
        return documentTitle == null ? "" : documentTitle.trim();
    }
}

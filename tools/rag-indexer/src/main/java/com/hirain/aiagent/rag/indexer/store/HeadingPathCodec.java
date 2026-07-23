package com.hirain.aiagent.rag.indexer.store;

import java.util.List;

/**
 * Heading Path 使用长度前缀编码，避免标题自身含有常见分隔符时产生不可逆歧义。
 * V1 的编码值直接保存到 Store；展示层必须显式解码而非自行 split。
 */
final class HeadingPathCodec {
    private HeadingPathCodec() { }

    static String encode(String headingPath) {
        if (headingPath == null || headingPath.isBlank()) return "v1:";
        return "v1:" + headingPath.length() + ":" + headingPath;
    }

    static String encode(List<String> segments) {
        StringBuilder output = new StringBuilder("v1:");
        for (String segment : segments) output.append(segment.length()).append(':').append(segment);
        return output.toString();
    }
}

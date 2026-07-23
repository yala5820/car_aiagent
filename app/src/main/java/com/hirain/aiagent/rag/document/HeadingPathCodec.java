package com.hirain.aiagent.rag.document;

import java.util.ArrayList;
import java.util.List;

/** 消费离线端长度前缀 HeadingPath V1，拒绝损坏路径而不自行猜测章节。 */
final class HeadingPathCodec {
    private HeadingPathCodec() { }
    static List<String> decode(String encoded) {
        if (encoded == null || encoded.isEmpty()) return List.of();
        List<String> result = new ArrayList<>(); int cursor = 0;
        while (cursor < encoded.length()) { int colon = encoded.indexOf(':', cursor); if (colon < cursor) throw new IllegalArgumentException("HeadingPath 编码非法");
            int length; try { length = Integer.parseInt(encoded.substring(cursor, colon)); } catch (NumberFormatException error) { throw new IllegalArgumentException("HeadingPath 编码非法", error); }
            int start = colon + 1, end = start + length; if (length < 0 || end > encoded.length()) throw new IllegalArgumentException("HeadingPath 编码非法");
            result.add(encoded.substring(start, end)); cursor = end; }
        return List.copyOf(result);
    }
}

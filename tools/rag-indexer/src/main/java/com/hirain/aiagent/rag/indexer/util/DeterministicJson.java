package com.hirain.aiagent.rag.indexer.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** 配置指纹必须与输入对象的字段书写顺序无关，因此先递归排序对象 Key 再计算 SHA-256。 */
public final class DeterministicJson {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DeterministicJson() {
    }

    public static String sha256(JsonNode node) {
        try {
            byte[] bytes = MAPPER.writeValueAsBytes(sort(node));
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder result = new StringBuilder("sha256:");
            for (byte value : digest) {
                result.append(String.format("%02x", value));
            }
            return result.toString();
        } catch (JsonProcessingException | java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException("无法生成确定性 JSON 指纹", error);
        }
    }

    public static JsonNode sort(JsonNode node) {
        if (node.isObject()) {
            ObjectNode sorted = MAPPER.createObjectNode();
            List<String> names = new ArrayList<>();
            Iterator<String> iterator = node.fieldNames();
            iterator.forEachRemaining(names::add);
            Collections.sort(names);
            for (String name : names) {
                sorted.set(name, sort(node.get(name)));
            }
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode sorted = MAPPER.createArrayNode();
            for (JsonNode child : node) {
                sorted.add(sort(child));
            }
            return sorted;
        }
        return node.deepCopy();
    }
}

package com.hirain.aiagent.rag.indexer.parser.html;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * 受限提取已下载 HTML 的 Next.js 数据快照中的中国大陆服务中心记录。
 *
 * <p>只读取 `__NEXT_DATA__` 的 JSON 文本，不求值 JavaScript、不解析 URL、不访问网络。只有同时具备
 * service 类型、门店名称和地址对象的记录才会被转换为正文；其余页面仍走普通静态 DOM 流程。</p>
 */
final class TeslaServiceCentersStaticDataExtractor {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    Element extract(Document document) {
        Element script = document.selectFirst("script#__NEXT_DATA__[type=application/json]");
        if (script == null || script.data().isBlank()) return null;
        try {
            JsonNode root = MAPPER.readTree(script.data());
            Element output = document.createElement("main").attr("id", "rag-tesla-service-centers");
            Set<String> seen = new HashSet<>();
            appendLocations(root, output, seen);
            return seen.isEmpty() ? null : output;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void appendLocations(JsonNode node, Element output, Set<String> seen) {
        if (node == null) return;
        if (node.isObject()) {
            appendLocation(node, output, seen);
            Iterator<JsonNode> values = node.elements();
            while (values.hasNext()) appendLocations(values.next(), output, seen);
        } else if (node.isArray()) {
            for (JsonNode item : node) appendLocations(item, output, seen);
        }
    }

    private static void appendLocation(JsonNode node, Element output, Set<String> seen) {
        JsonNode source = node.path("_source");
        JsonNode marketing = source.path("marketing");
        JsonNode address = source.path("key_data").path("address");
        if (!isService(node.path("location_type")) || !source.isObject() || !marketing.isObject() || !address.isObject()) return;
        String name = text(marketing, "display_name");
        String city = text(address, "city");
        String addressLine = text(address, "address_1");
        if (name.isBlank() || addressLine.isBlank()) return;
        String id = text(node, "uuid");
        if (id.isBlank()) id = text(node, "location_url_slug");
        if (id.isBlank() || !seen.add(id)) return;
        Element section = output.appendElement("section").attr("id", "service-center-" + id);
        section.appendElement("h2").text("特斯拉服务中心：" + name);
        StringBuilder summary = new StringBuilder("城市：").append(city.isBlank() ? "未提供" : city)
                .append("；地址：").append(addressLine);
        appendIfPresent(summary, "服务中心电话", text(marketing, "service_center_phone"));
        appendIfPresent(summary, "道路救援电话", text(marketing, "roadside_assistance_number"));
        section.appendElement("p").text(summary.toString());
    }

    private static boolean isService(JsonNode values) {
        if (!values.isArray()) return false;
        for (JsonNode value : values) if (value.isTextual() && "service".equals(value.asText())) return true;
        return false;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText().trim() : "";
    }

    private static void appendIfPresent(StringBuilder target, String label, String value) {
        if (!value.isBlank()) target.append('；').append(label).append('：').append(value);
    }
}

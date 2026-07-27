package com.hirain.aiagent.rag.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** 只编码模型白名单字段；不接受或反射内部 RagResult，形成可审计的数据出站边界。 */
public final class RagJsonCodec {
    public static final int TOOL_RESULT_SCHEMA_VERSION = 1;
    public String encodeToolResult(VehicleKnowledgeToolResult value) {
        if (value.schemaVersion() != TOOL_RESULT_SCHEMA_VERSION) throw new IllegalArgumentException("ToolResult schemaVersion 不受支持");
        JsonObject root = new JsonObject(); root.addProperty("schemaVersion", value.schemaVersion()); root.addProperty("status", value.status().name()); root.addProperty("answerable", value.answerable()); root.addProperty("query", value.query());
        JsonArray evidence = new JsonArray(); for (VehicleKnowledgeEvidence item : value.evidence()) evidence.add(evidence(item)); root.add("evidence", evidence);
        JsonArray degraded = new JsonArray(); for (String reason : value.degradedReasons()) degraded.add(reason); root.add("degradedReasons", degraded);
        if (value.failureReasonCode() != null) root.addProperty("failureReasonCode", value.failureReasonCode().name());
        if (value.userSafeMessage() != null) root.addProperty("userSafeMessage", value.userSafeMessage());
        return root.toString();
    }
    private static JsonObject evidence(VehicleKnowledgeEvidence value) {
        JsonObject result = new JsonObject(); result.addProperty("evidenceId", value.evidenceId()); result.addProperty("content", value.content()); result.addProperty("documentTitle", value.documentTitle()); result.addProperty("documentVersion", value.documentVersion()); if (value.sectionPath() != null) result.addProperty("sectionPath", value.sectionPath()); result.add("sourceLocator", locator(value.sourceLocator())); result.addProperty("applicability", value.applicability().name()); result.addProperty("retrievalConfidence", value.retrievalConfidence().name()); return result;
    }
    private static JsonObject locator(SourceLocator value) {
        JsonObject result = new JsonObject(); result.addProperty("sourceFormat", value.sourceFormat().name()); JsonArray path = new JsonArray(); for (String part : value.headingPath()) path.add(part); result.add("headingPath", path);
        if (value.sourceFormat() == SourceFormat.PDF) { result.addProperty("pdfPageStart", value.pdfPageStart()); result.addProperty("pdfPageEnd", value.pdfPageEnd()); if (value.printedPageStartLabel() != null) result.addProperty("printedPageStartLabel", value.printedPageStartLabel()); if (value.printedPageEndLabel() != null) result.addProperty("printedPageEndLabel", value.printedPageEndLabel()); }
        if (value.sourceFormat() == SourceFormat.STATIC_HTML && value.htmlElementId() != null) result.addProperty("htmlElementId", value.htmlElementId());
        if (value.sourceFormat() == SourceFormat.MARKDOWN && value.sourceLineStart() != 0) { result.addProperty("sourceLineStart", value.sourceLineStart()); result.addProperty("sourceLineEnd", value.sourceLineEnd()); }
        result.addProperty("sectionOrdinal", value.sectionOrdinal()); return result;
    }
}

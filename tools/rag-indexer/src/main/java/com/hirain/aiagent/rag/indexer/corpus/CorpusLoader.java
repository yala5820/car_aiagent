package com.hirain.aiagent.rag.indexer.corpus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hirain.aiagent.rag.indexer.cli.CliCommandException;
import com.hirain.aiagent.rag.indexer.cli.CliExitCode;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/** 严格读取 corpus.json：未知字段、缺失字段和错误节点类型均在文件解析阶段失败。 */
public final class CorpusLoader {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> ROOT = Set.of("schemaVersion", "bundleId", "bundleVersion", "knowledgeScopeId", "scope", "documents");
    private static final Set<String> SCOPE = Set.of("vehicleModel", "modelYear", "region", "softwareVersion", "configurationCode");
    private static final Set<String> DOCUMENT = Set.of("documentId", "title", "documentType", "documentVersion", "language", "sourceFormat", "relativePath", "expectedSha256", "sourceLayout", "applicability");

    public CorpusDefinition load(Path file) {
        try {
            JsonNode root = MAPPER.readTree(file.toFile());
            requireObject(root); rejectUnknown(root, ROOT); requireFields(root, ROOT);
            KnowledgeScopeDefinition scope = scope(root.get("scope"), false);
            List<CorpusDocumentDefinition> documents = new ArrayList<>();
            if (!root.get("documents").isArray()) fail("CORPUS_JSON_INVALID");
            for (JsonNode node : root.get("documents")) {
                requireObject(node); rejectUnknown(node, DOCUMENT); requireFields(node, DOCUMENT.stream().filter(field -> !field.equals("sourceLayout")).collect(java.util.stream.Collectors.toSet()));
                documents.add(new CorpusDocumentDefinition(text(node, "documentId"), text(node, "title"), text(node, "documentType"),
                        text(node, "documentVersion"), text(node, "language"), text(node, "sourceFormat"), text(node, "relativePath"),
                        text(node, "expectedSha256"), optionalText(node, "sourceLayout", "SINGLE_FILE"), scope(node.get("applicability"), true)));
            }
            CorpusDefinition definition = new CorpusDefinition(root.get("schemaVersion").asInt(),
                    new BundleDefinition(text(root, "bundleId"), text(root, "bundleVersion"), text(root, "knowledgeScopeId"), scope), documents);
            new CorpusValidator().validate(definition);
            return definition;
        } catch (IOException | IllegalArgumentException error) {
            fail("CORPUS_JSON_INVALID");
            throw new AssertionError(error);
        }
    }

    private static KnowledgeScopeDefinition scope(JsonNode node, boolean allowWildcard) {
        requireObject(node); rejectUnknown(node, SCOPE); requireFields(node, SCOPE);
        return new KnowledgeScopeDefinition(scopeText(node, "vehicleModel", allowWildcard), scopeText(node, "modelYear", allowWildcard),
                scopeText(node, "region", allowWildcard), scopeText(node, "softwareVersion", allowWildcard), scopeText(node, "configurationCode", allowWildcard));
    }
    private static String scopeText(JsonNode node, String field, boolean wildcard) { String value = text(node, field); if (!wildcard && "*".equals(value)) fail("CORPUS_SCOPE_INVALID"); return value; }
    private static String text(JsonNode node, String field) { JsonNode value = node.get(field); if (value == null || !value.isTextual() || value.asText().isBlank()) fail("CORPUS_JSON_INVALID"); return value.asText(); }
    private static String optionalText(JsonNode node, String field, String fallback) { return node.has(field) ? text(node, field) : fallback; }
    private static void requireObject(JsonNode node) { if (node == null || !node.isObject()) fail("CORPUS_JSON_INVALID"); }
    private static void requireFields(JsonNode node, Set<String> fields) { for (String field : fields) if (!node.has(field)) fail("CORPUS_JSON_REQUIRED"); }
    private static void rejectUnknown(JsonNode node, Set<String> fields) { Iterator<String> names=node.fieldNames(); while(names.hasNext()) if(!fields.contains(names.next())) fail("CORPUS_JSON_UNKNOWN_FIELD"); }
    private static void fail(String reason) { throw new CliCommandException(CliExitCode.ARGUMENT_OR_CONFIG_ERROR, reason); }
}

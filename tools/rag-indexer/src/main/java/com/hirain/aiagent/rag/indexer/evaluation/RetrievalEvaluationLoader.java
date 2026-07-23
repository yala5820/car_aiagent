package com.hirain.aiagent.rag.indexer.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 严格加载评测 JSON；未知字段、重复 Case/Query 与空期望集合均在任何云调用前失败。 */
public final class RetrievalEvaluationLoader {
    private static final Set<String> ROOT_FIELDS = Set.of("schemaVersion", "datasetVersion", "knowledgeScopeId", "cases");
    private static final Set<String> CASE_FIELDS = Set.of("caseId", "query", "expectedChunkIds");

    public RetrievalEvaluationDataset load(Path file) {
        try {
            if (!Files.isRegularFile(file)) throw invalid();
            JsonNode root = new ObjectMapper().readTree(file.toFile());
            if (root == null || !root.isObject() || !fieldNames(root).equals(ROOT_FIELDS) || root.path("schemaVersion").asInt(-1) != 1) throw invalid();
            String version = requiredText(root, "datasetVersion");
            String scope = requiredText(root, "knowledgeScopeId");
            JsonNode cases = root.path("cases");
            if (!cases.isArray() || cases.isEmpty()) throw invalid();
            Set<String> ids = new HashSet<>(); Set<String> queries = new HashSet<>(); List<RetrievalEvaluationDataset.Case> output = new ArrayList<>();
            for (JsonNode item : cases) {
                if (!item.isObject() || !fieldNames(item).equals(CASE_FIELDS)) throw invalid();
                String id = requiredText(item, "caseId"); String query = requiredText(item, "query");
                if (!ids.add(id) || !queries.add(query)) throw invalid();
                JsonNode expected = item.path("expectedChunkIds"); if (!expected.isArray() || expected.isEmpty()) throw invalid();
                Set<String> chunkIds = new HashSet<>(); List<String> values = new ArrayList<>();
                for (JsonNode value : expected) { if (!value.isTextual() || value.asText().isBlank() || !chunkIds.add(value.asText())) throw invalid(); values.add(value.asText()); }
                output.add(new RetrievalEvaluationDataset.Case(id, query, values));
            }
            return new RetrievalEvaluationDataset(version, scope, output);
        } catch (IllegalArgumentException exception) { throw exception; }
        catch (Exception exception) { throw invalid(); }
    }

    private static String requiredText(JsonNode node, String field) { JsonNode value = node.get(field); if (value == null || !value.isTextual() || value.asText().isBlank()) throw invalid(); return value.asText(); }
    private static Set<String> fieldNames(JsonNode node) { Set<String> fields = new HashSet<>(); node.fieldNames().forEachRemaining(fields::add); return fields; }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("EVALUATION_DATASET_INVALID"); }
}

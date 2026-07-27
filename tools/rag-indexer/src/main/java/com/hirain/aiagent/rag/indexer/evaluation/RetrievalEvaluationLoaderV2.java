package com.hirain.aiagent.rag.indexer.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** 严格加载 Eval V2：未知字段、重复 ID/Query、无效 Parent 集合均在检索前拒绝。 */
public final class RetrievalEvaluationLoaderV2 {
    private static final Set<String> ROOT_FIELDS = Set.of("schemaVersion", "datasetVersion", "knowledgeScopeId", "cases");
    private static final Set<String> CASE_FIELDS = Set.of("caseId", "query", "category", "answerability", "answerCriteria", "acceptableEvidenceSets", "tags", "split");
    private static final Set<String> EVIDENCE_FIELDS = Set.of("requiredParentIds", "optionalLocatorChildIds", "rationale");

    public RetrievalEvaluationDatasetV2 load(Path file) {
        try {
            if (!Files.isRegularFile(file)) throw invalid();
            JsonNode root = new ObjectMapper().readTree(file.toFile());
            if (root == null || !root.isObject() || !fields(root).equals(ROOT_FIELDS) || root.path("schemaVersion").asInt(-1) != 2) throw invalid();
            String version = text(root, "datasetVersion");
            String scope = text(root, "knowledgeScopeId");
            JsonNode casesNode = root.path("cases");
            if (!casesNode.isArray() || casesNode.isEmpty()) throw invalid();
            Set<String> ids = new HashSet<>();
            Set<String> queries = new HashSet<>();
            List<RetrievalEvaluationDatasetV2.Case> cases = new ArrayList<>();
            for (JsonNode node : casesNode) {
                if (!node.isObject() || !fields(node).equals(CASE_FIELDS)) throw invalid();
                String caseId = text(node, "caseId");
                String query = text(node, "query");
                if (!ids.add(caseId) || !queries.add(query)) throw invalid();
                EvaluationCategory category = enumValue(EvaluationCategory.class, text(node, "category"));
                EvaluationAnswerability answerability = enumValue(EvaluationAnswerability.class, text(node, "answerability"));
                List<String> criteria = stringArray(node.path("answerCriteria"));
                List<String> tags = stringArray(node.path("tags"));
                String split = text(node, "split");
                JsonNode setsNode = node.path("acceptableEvidenceSets");
                if (!setsNode.isArray()) throw invalid();
                List<EvidenceSetExpectation> sets = new ArrayList<>();
                for (JsonNode set : setsNode) {
                    if (!set.isObject() || !fields(set).equals(EVIDENCE_FIELDS)) throw invalid();
                    List<String> parents = stringArray(set.path("requiredParentIds"));
                    List<String> children = stringArray(set.path("optionalLocatorChildIds"));
                    sets.add(new EvidenceSetExpectation(parents, children, text(set, "rationale")));
                }
                cases.add(new RetrievalEvaluationDatasetV2.Case(caseId, query, category, answerability, criteria, sets, tags, split));
            }
            return new RetrievalEvaluationDatasetV2(version, scope, cases);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalid();
        }
    }

    private static String text(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || !value.isTextual() || value.asText().isBlank()) throw invalid();
        return value.asText();
    }

    private static List<String> stringArray(JsonNode value) {
        if (!value.isArray()) throw invalid();
        List<String> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode item : value) {
            if (!item.isTextual() || item.asText().isBlank() || !seen.add(item.asText())) throw invalid();
            result.add(item.asText());
        }
        return List.copyOf(result);
    }

    private static Set<String> fields(JsonNode node) {
        Set<String> result = new HashSet<>();
        node.fieldNames().forEachRemaining(result::add);
        return result;
    }

    private static <T extends Enum<T>> T enumValue(Class<T> type, String value) {
        try { return Enum.valueOf(type, value); } catch (Exception exception) { throw invalid(); }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("EVALUATION_V2_DATASET_INVALID");
    }
}

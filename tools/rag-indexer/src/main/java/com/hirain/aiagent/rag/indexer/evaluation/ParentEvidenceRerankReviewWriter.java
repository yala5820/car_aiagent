package com.hirain.aiagent.rag.indexer.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hirain.aiagent.rag.indexer.store.ObjectBoxStoreFactory;
import com.hirain.aiagent.rag.store.entity.KnowledgeChunkEntity;
import io.objectbox.BoxStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** 生成 RRF/Rerank Parent 排名差异的本地审核页，正文不进入脱敏评测报告。 */
public final class ParentEvidenceRerankReviewWriter {
    public static void main(String[] args) throws Exception {
        if (args.length != 5) throw new IllegalArgumentException("bundle dataset rrfReport rerankReport output required");
        new ParentEvidenceRerankReviewWriter().write(Path.of(args[4]), Path.of(args[0]), Path.of(args[1]), Path.of(args[2]), Path.of(args[3]));
    }

    public void write(Path output, Path bundle, Path datasetFile, Path rrfFile, Path rerankFile) throws Exception {
        if (Files.exists(output)) throw new IllegalArgumentException("RERANK_V2_REVIEW_ALREADY_EXISTS");
        RetrievalEvaluationDatasetV2 dataset = new RetrievalEvaluationLoaderV2().load(datasetFile);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode rrf = mapper.readTree(rrfFile.toFile()).path("cases");
        JsonNode rerank = mapper.readTree(rerankFile.toFile()).path("cases");
        Map<String, JsonNode> rrfById = byId(rrf), rerankById = byId(rerank);
        Map<String, KnowledgeChunkEntity> parents = new HashMap<>();
        try (BoxStore store = new ObjectBoxStoreFactory().openReadOnlyExisting(bundle)) {
            for (KnowledgeChunkEntity entity : store.boxFor(KnowledgeChunkEntity.class).getAll()) {
                if ("PARENT".equals(entity.chunkLevel)) parents.put(entity.chunkId, entity);
            }
        }
        StringBuilder html = new StringBuilder("""
                <!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>RRF/Rerank Parent 差异审核</title>
                <style>body{margin:0;background:#f5f7fb;color:#182230;font:15px/1.6 -apple-system,BlinkMacSystemFont,"Segoe UI","Microsoft YaHei",sans-serif}main{max-width:1240px;margin:auto;padding:24px 16px 80px}.case{background:#fff;border:1px solid #dfe5ef;border-radius:8px;padding:18px;margin:18px 0}.query{background:#eef5ff;border-left:4px solid #2869c9;padding:10px}.cols{display:grid;grid-template-columns:1fr 1fr;gap:14px}.panel{border:1px solid #dfe5ef;border-radius:6px;padding:10px;margin-top:12px}.bad{background:#fff2f0;border-color:#df8d82}.parent{border-top:1px solid #e6eaf0;padding:9px 0}.content{white-space:pre-wrap}.meta{font-size:12px;color:#667085}.decision{border-top:1px solid #e5e8ee;margin-top:12px;padding-top:10px}.decision label{display:block}.decision textarea{width:100%;min-height:60px;box-sizing:border-box}@media(max-width:780px){.cols{grid-template-columns:1fr}}</style></head><body><main><h1>RRF / Rerank Parent 差异审核</h1>
                <p class="meta">仅展示 RRF 与 Rerank 排名不同或 Rerank 未覆盖标注 Evidence Set 的样本。请阅读完整 Parent 后判断 Rerank 是否真正降级。</p>
                """);
        int number = 0;
        for (RetrievalEvaluationDatasetV2.Case value : dataset.cases()) {
            JsonNode a = rrfById.get(value.caseId()), b = rerankById.get(value.caseId());
            if (a == null || b == null || !needsReview(a, b)) continue;
            appendCase(html, ++number, value, a, b, parents);
        }
        html.append("</main></body></html>");
        Files.createDirectories(output.getParent());
        Files.writeString(output, html, StandardCharsets.UTF_8);
    }

    private static boolean needsReview(JsonNode rrf, JsonNode rerank) {
        if (!rerank.path("evidenceSetCovered").asBoolean(false)) return true;
        return !rrf.path("rankedParentIds").toString().equals(rerank.path("rankedParentIds").toString());
    }

    private static Map<String, JsonNode> byId(JsonNode values) {
        Map<String, JsonNode> output = new HashMap<>();
        if (values.isArray()) for (JsonNode value : values) output.put(value.path("caseId").asText(), value);
        return output;
    }

    private static void appendCase(StringBuilder html, int number, RetrievalEvaluationDatasetV2.Case value,
                                   JsonNode rrf, JsonNode rerank, Map<String, KnowledgeChunkEntity> parents) {
        html.append("<article class=\"case\"><h2>").append(number).append(". ").append(escape(value.caseId())).append("</h2><div class=\"query\"><strong>问题：</strong>")
                .append(escape(value.query())).append("</div><p><strong>回答标准：</strong>").append(escape(String.join("；", value.answerCriteria())))
                .append("</p><div class=\"cols\"><section class=\"panel\"><h3>RRF</h3><div class=\"meta\">覆盖=").append(rrf.path("evidenceSetCovered").asBoolean(false))
                .append("；首个覆盖排名=").append(rrf.path("firstCoveredRank").asInt(0)).append("</div>");
        appendParents(html, rrf.path("rankedParentIds"), parents);
        html.append("</section><section class=\"panel bad\"><h3>Rerank</h3><div class=\"meta\">覆盖=").append(rerank.path("evidenceSetCovered").asBoolean(false))
                .append("；首个覆盖排名=").append(rerank.path("firstCoveredRank").asInt(0)).append("</div>");
        appendParents(html, rerank.path("rankedParentIds"), parents);
        html.append("</section></div><section class=\"decision\"><strong>人工结论：</strong><label><input type=\"radio\" name=\"").append(escape(value.caseId())).append("\">Rerank 更好</label><label><input type=\"radio\" name=\"").append(escape(value.caseId())).append("\">RRF 更好</label><label><input type=\"radio\" name=\"").append(escape(value.caseId())).append("\">两者等价或都不足</label><textarea placeholder=\"记录判断依据\"></textarea></section></article>");
    }

    private static void appendParents(StringBuilder html, JsonNode ids, Map<String, KnowledgeChunkEntity> parents) {
        if (!ids.isArray()) return;
        for (JsonNode id : ids) {
            KnowledgeChunkEntity parent = parents.get(id.asText());
            if (parent == null) continue;
            html.append("<div class=\"parent\"><strong>").append(escape(parent.headingPath)).append("</strong><div class=\"meta\">").append(escape(parent.chunkId)).append("</div><div class=\"content\">").append(escape(parent.content)).append("</div></div>");
        }
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }
}

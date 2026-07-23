package com.hirain.aiagent.rag.indexer.evaluation;

import com.hirain.aiagent.rag.indexer.store.ObjectBoxStoreFactory;
import com.hirain.aiagent.rag.store.entity.KnowledgeChunkEntity;
import io.objectbox.BoxStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 生成仅供本地人工审核的 HTML 页面。
 *
 * <p>报告不参与指标计算：它只把评测题、人工标注的正确 Chunk 与 Rerank Top5 放在同一页面，
 * 避免审核者根据不可读的 Chunk ID 反查 ObjectBox。页面中的选择和备注仅写入浏览器 localStorage，
 * 可由审核者导出 JSON 后交回评测维护者。</p>
 */
public final class RerankManualReviewReportWriter {
    private static final int CONTENT_PREVIEW_MAX_CHARACTERS = 700;

    public void write(Path file, Path bundle, RetrievalEvaluationDataset dataset,
                      List<OfflineHybridEvaluator.RerankDiagnostic> diagnostics) throws IOException {
        if (Files.exists(file)) throw new IllegalArgumentException("RERANK_MANUAL_REVIEW_ALREADY_EXISTS");
        Map<String, RetrievalEvaluationDataset.Case> casesById = new HashMap<>();
        for (RetrievalEvaluationDataset.Case value : dataset.cases()) casesById.put(value.caseId(), value);

        try (BoxStore store = new ObjectBoxStoreFactory().openReadOnlyExisting(bundle)) {
            Map<String, KnowledgeChunkEntity> chunks = children(store);
            List<ReviewCase> misses = new ArrayList<>();
            for (OfflineHybridEvaluator.RerankDiagnostic diagnostic : diagnostics) {
                List<String> top5 = diagnostic.finalRankedChunkIds().stream().limit(5).toList();
                if (top5.stream().anyMatch(diagnostic.expectedChunkIds()::contains)) continue;
                RetrievalEvaluationDataset.Case evaluationCase = casesById.get(diagnostic.caseId());
                if (evaluationCase == null) throw new IllegalArgumentException("RERANK_MANUAL_REVIEW_CASE_UNKNOWN");
                misses.add(new ReviewCase(evaluationCase, diagnostic, top5));
            }
            Files.createDirectories(file.getParent());
            Files.writeString(file, render(misses, chunks), StandardCharsets.UTF_8);
        }
    }

    private static Map<String, KnowledgeChunkEntity> children(BoxStore store) {
        Map<String, KnowledgeChunkEntity> output = new HashMap<>();
        for (KnowledgeChunkEntity value : store.boxFor(KnowledgeChunkEntity.class).getAll()) {
            if ("CHILD".equals(value.chunkLevel)) output.put(value.chunkId, value);
        }
        return output;
    }

    private static String render(List<ReviewCase> cases, Map<String, KnowledgeChunkEntity> chunks) {
        StringBuilder html = new StringBuilder("""
                <!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>Rerank 人工审核报告</title>
                <style>
                body{margin:0;background:#f5f7fb;color:#182230;font:15px/1.6 -apple-system,BlinkMacSystemFont,"Segoe UI","Microsoft YaHei",sans-serif}
                main{max-width:1200px;margin:auto;padding:28px 18px 80px}h1{margin:0 0 8px}h2{margin:0 0 12px;font-size:20px}.note{color:#526071}.case{margin:24px 0;padding:22px;background:#fff;border:1px solid #dfe5ef;border-radius:12px}.query{padding:12px 14px;background:#f1f6ff;border-left:4px solid #2969cc;border-radius:6px}.grid{display:grid;grid-template-columns:1fr 1fr;gap:16px;margin-top:16px}.panel{border:1px solid #dfe5ef;border-radius:9px;padding:14px}.expected{border-color:#d99a2b;background:#fffaf0}.candidate{margin:10px 0;padding:12px;background:#f8fafc;border-radius:7px}.meta{font-size:13px;color:#667085}.content{white-space:pre-wrap;margin-top:8px}.rank{font-weight:650;color:#1d4e9e}.decision{margin-top:16px;padding-top:12px;border-top:1px solid #e4e7ec}.decision label{display:block;margin:7px 0}.decision textarea{box-sizing:border-box;width:100%;min-height:72px;margin-top:7px;padding:8px}.actions{position:sticky;bottom:0;padding:12px;background:#ffffffeb;border:1px solid #dfe5ef;border-radius:8px}button{padding:8px 12px;border:0;border-radius:6px;background:#2463c5;color:#fff;cursor:pointer}@media(max-width:800px){.grid{grid-template-columns:1fr}}
                </style></head><body><main><h1>Rerank 人工审核报告</h1>
                """);
        html.append("<p class=\"note\">范围：Rerank 后 Top5 未命中标注正确 Chunk 的 <strong>")
                .append(cases.size()).append("</strong> 条样本。请阅读“标注正确块”与“Rerank Top5”正文后选择结论。选择和备注会保存在本机浏览器；完成后点击“导出审核 JSON”。</p>");
        for (int index = 0; index < cases.size(); index++) appendCase(html, index + 1, cases.get(index), chunks);
        html.append("""
                <div class="actions"><button id="export">导出审核 JSON</button> <span id="saved" class="note"></span></div>
                </main><script>
                const key='rag-rerank-manual-review-v1'; const saved=document.getElementById('saved');
                const load=()=>JSON.parse(localStorage.getItem(key)||'{}'); const persist=()=>{const data={};document.querySelectorAll('[data-case]').forEach(e=>{const id=e.dataset.case;data[id]||={};if(e.type==='radio'&&e.checked)data[id].decision=e.value;if(e.tagName==='TEXTAREA')data[id].note=e.value});localStorage.setItem(key,JSON.stringify(data));saved.textContent='已保存到本机浏览器';};
                const data=load(); document.querySelectorAll('[data-case]').forEach(e=>{const item=data[e.dataset.case]||{};if(e.type==='radio'&&item.decision===e.value)e.checked=true;if(e.tagName==='TEXTAREA')e.value=item.note||'';e.addEventListener('change',persist);e.addEventListener('input',persist)});
                document.getElementById('export').addEventListener('click',()=>{const blob=new Blob([JSON.stringify({schemaVersion:1,generatedAt:new Date().toISOString(),reviews:load()},null,2)],{type:'application/json'});const a=document.createElement('a');a.href=URL.createObjectURL(blob);a.download='rerank-manual-review.json';a.click();URL.revokeObjectURL(a.href);});
                </script></body></html>
                """);
        return html.toString();
    }

    private static void appendCase(StringBuilder html, int sequence, ReviewCase review, Map<String, KnowledgeChunkEntity> chunks) {
        String caseId = escape(review.evaluationCase().caseId());
        html.append("<article class=\"case\"><h2>").append(sequence).append(". ").append(caseId).append("</h2><div class=\"query\"><strong>用户问题：</strong>")
                .append(escape(review.evaluationCase().query())).append("</div><div class=\"grid\"><section class=\"panel expected\"><h3>标注正确 Chunk</h3>");
        for (String chunkId : review.evaluationCase().expectedChunkIds()) {
            appendChunk(html, chunks.get(chunkId), chunkId, rank(review.diagnostic().rrfCandidateChunkIds(), chunkId),
                    responseScore(review.diagnostic(), chunkId), rank(review.diagnostic().finalRankedChunkIds(), chunkId));
        }
        html.append("</section><section class=\"panel\"><h3>Rerank 最终 Top5</h3>");
        for (String chunkId : review.top5()) {
            appendChunk(html, chunks.get(chunkId), chunkId, rank(review.diagnostic().rrfCandidateChunkIds(), chunkId),
                    responseScore(review.diagnostic(), chunkId), rank(review.diagnostic().finalRankedChunkIds(), chunkId));
        }
        html.append("</section></div><section class=\"decision\"><strong>人工结论（四选一）：</strong>");
        option(html, caseId, "EXPECTED_CORRECT_DEMOTED", "标注正确 Chunk 明显更好：Rerank 排序错误");
        option(html, caseId, "EQUIVALENT_ANSWER", "Rerank Top1 同样能完整回答：应增加等价正确 Chunk");
        option(html, caseId, "EXPECTED_LABEL_WRONG", "Rerank Top1 更好：原始评测标注错误");
        option(html, caseId, "ALL_INSUFFICIENT", "两边都不够好：需要检查分块、标题或资料内容");
        html.append("<textarea data-case=\"").append(caseId).append("\" placeholder=\"可选：记录判断依据，例如 Top1 只覆盖步骤的一部分。\"></textarea></section></article>");
    }

    private static void option(StringBuilder html, String caseId, String value, String text) {
        html.append("<label><input type=\"radio\" name=\"").append(caseId).append("\" data-case=\"").append(caseId).append("\" value=\"").append(value).append("\"> ")
                .append(escape(text)).append("</label>");
    }

    private static void appendChunk(StringBuilder html, KnowledgeChunkEntity chunk, String chunkId, int rrfRank, Double score, int finalRank) {
        if (chunk == null) throw new IllegalArgumentException("RERANK_MANUAL_REVIEW_CHUNK_UNKNOWN");
        html.append("<div class=\"candidate\"><div class=\"rank\">最终第 ").append(finalRank).append(" 名；RRF 第 ").append(rrfRank).append(" 名；Rerank 分数：")
                .append(score == null ? "未返回 Top20" : String.format(java.util.Locale.ROOT, "%.6f", score)).append("</div><div><strong>")
                .append(escape(chunk.headingPath)).append("</strong></div><div class=\"meta\">").append(escape(chunk.documentTitle)).append(" · ")
                .append(escape(chunk.documentType)).append(sourceLocation(chunk)).append("</div><div class=\"content\">").append(escape(preview(chunk.content))).append("</div></div>");
    }

    private static String sourceLocation(KnowledgeChunkEntity chunk) {
        if (chunk.pdfPageStart > 0) return " · PDF 第 " + chunk.pdfPageStart + " 页";
        if (chunk.sourceLineStart > 0) return " · 源文件第 " + chunk.sourceLineStart + " 行";
        return "";
    }

    private static int rank(List<String> values, String chunkId) { int index = values.indexOf(chunkId); return index < 0 ? 0 : index + 1; }
    private static Double responseScore(OfflineHybridEvaluator.RerankDiagnostic diagnostic, String chunkId) {
        for (OfflineHybridEvaluator.RerankResponse value : diagnostic.response()) if (chunkId.equals(value.chunkId())) return value.relevanceScore();
        return null;
    }
    private static String preview(String value) { String normalized = value == null ? "" : value.replaceAll("\\s+", " ").trim(); return normalized.length() <= CONTENT_PREVIEW_MAX_CHARACTERS ? normalized : normalized.substring(0, CONTENT_PREVIEW_MAX_CHARACTERS) + "…"; }
    private static String escape(String value) { if (value == null) return ""; return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;"); }

    private record ReviewCase(RetrievalEvaluationDataset.Case evaluationCase, OfflineHybridEvaluator.RerankDiagnostic diagnostic, List<String> top5) { }
}

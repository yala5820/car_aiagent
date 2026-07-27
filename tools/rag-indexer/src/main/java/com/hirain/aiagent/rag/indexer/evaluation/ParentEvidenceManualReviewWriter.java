package com.hirain.aiagent.rag.indexer.evaluation;

import com.hirain.aiagent.rag.indexer.store.ObjectBoxStoreFactory;
import com.hirain.aiagent.rag.store.entity.KnowledgeChunkEntity;
import io.objectbox.BoxStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** 生成只供本地人工复核的 Eval V2 HTML，不把正文写入可提交 JSON 报告。 */
public final class ParentEvidenceManualReviewWriter {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("bundle dataset output required");
        new ParentEvidenceManualReviewWriter().write(Path.of(args[2]), Path.of(args[0]), Path.of(args[1]));
    }

    public void write(Path output, Path bundle, Path datasetFile) throws Exception {
        if (Files.exists(output)) throw new IllegalArgumentException("EVALUATION_V2_REVIEW_ALREADY_EXISTS");
        RetrievalEvaluationDatasetV2 dataset = new RetrievalEvaluationLoaderV2().load(datasetFile);
        Map<String, KnowledgeChunkEntity> parents = new HashMap<>();
        try (BoxStore store = new ObjectBoxStoreFactory().openReadOnlyExisting(bundle)) {
            for (KnowledgeChunkEntity entity : store.boxFor(KnowledgeChunkEntity.class).getAll()) {
                if ("PARENT".equals(entity.chunkLevel)) parents.put(entity.chunkId, entity);
            }
        }
        StringBuilder html = new StringBuilder("""
                <!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
                <title>Eval V2 Parent Evidence 人工审核</title><style>
                body{margin:0;background:#f4f6f9;color:#17202b;font:15px/1.6 -apple-system,BlinkMacSystemFont,"Segoe UI","Microsoft YaHei",sans-serif}main{max-width:1240px;margin:auto;padding:24px 16px 90px}h1{margin:0 0 8px}.note{color:#5f6b7a}.case{background:#fff;border:1px solid #d8dee8;border-radius:8px;margin:18px 0;padding:18px}.query{background:#eef5ff;border-left:4px solid #2b6dcc;padding:10px 12px}.criteria{margin:10px 0}.set{border:1px solid #e0a33a;background:#fffaf0;border-radius:6px;padding:10px;margin:10px 0}.parent{border:1px solid #d8dee8;border-radius:6px;margin:8px 0;padding:10px}.meta{font-size:12px;color:#647184}.content{white-space:pre-wrap;margin-top:6px}.decision{border-top:1px solid #e5e9ef;margin-top:14px;padding-top:10px}.decision label{display:block;margin:5px 0}.decision textarea{width:100%;min-height:64px;box-sizing:border-box;margin-top:6px}.bar{position:sticky;bottom:0;background:#fffffff2;border:1px solid #d8dee8;border-radius:8px;padding:10px}.bar button{border:0;background:#245fbd;color:white;padding:8px 12px;border-radius:5px;cursor:pointer}@media(max-width:760px){main{padding:16px 10px}}
                </style></head><body><main><h1>Eval V2 Parent Evidence 人工审核</h1>
                <p class="note">本页读取本地 Eval 输入和 Bundle，只用于确认问题质量与完整 Parent 证据。请逐条选择结论；选择和备注保存于本机浏览器，点击导出后得到 <code>eval-v2-review.json</code>。</p>
                """);
        int index = 0;
        for (RetrievalEvaluationDatasetV2.Case value : dataset.cases()) appendCase(html, ++index, value, parents);
        html.append("""
                <div class="bar"><button id="export">导出审核 JSON</button> <span id="saved" class="note"></span></div></main><script>
                const key='rag-eval-v2-review-v2',saved=document.getElementById('saved'),load=()=>JSON.parse(localStorage.getItem(key)||'{}');
                const field=e=>e.dataset.field||'decision';
                const persist=()=>{const data=load();document.querySelectorAll('[data-case]').forEach(e=>{const id=e.dataset.case;data[id]??={};if(e.type==='radio'&&e.checked)data[id][field(e)]=e.value;if(e.tagName==='TEXTAREA')data[id].note=e.value});localStorage.setItem(key,JSON.stringify(data));saved.textContent='已保存到本机浏览器'};
                const data=load();document.querySelectorAll('[data-case]').forEach(e=>{const item=data[e.dataset.case]||{};if(e.type==='radio'&&item[field(e)]===e.value)e.checked=true;if(e.tagName==='TEXTAREA')e.value=item.note||'';e.addEventListener('change',persist);e.addEventListener('input',persist)});
                document.getElementById('export').onclick=()=>{const blob=new Blob([JSON.stringify({schemaVersion:1,generatedAt:new Date().toISOString(),reviews:load()},null,2)],{type:'application/json'});const a=document.createElement('a');a.href=URL.createObjectURL(blob);a.download='eval-v2-review.json';a.click();URL.revokeObjectURL(a.href)};
                </script></body></html>
                """);
        Files.createDirectories(output.getParent());
        Files.writeString(output, html, StandardCharsets.UTF_8);
    }

    private static void appendCase(StringBuilder html, int number, RetrievalEvaluationDatasetV2.Case value,
                                   Map<String, KnowledgeChunkEntity> parents) {
        String caseId = escape(value.caseId());
        html.append("<article class=\"case\"><h2>").append(number).append(". ").append(caseId)
                .append(" <span class=\"meta\">[").append(value.category()).append(" / ").append(value.split()).append(" / ")
                .append(value.answerability()).append("]</span></h2><div class=\"query\"><strong>用户问题：</strong>")
                .append(escape(value.query())).append("</div><div class=\"criteria\"><strong>回答必须覆盖：</strong> ")
                .append(escape(String.join("；", value.answerCriteria()))).append("</div>");
        for (int setIndex = 0; setIndex < value.acceptableEvidenceSets().size(); setIndex++) {
            EvidenceSetExpectation set = value.acceptableEvidenceSets().get(setIndex);
            html.append("<section class=\"set\"><strong>可接受证据集合 ").append(setIndex + 1).append("（集合内 AND）</strong><div class=\"meta\">")
                    .append(escape(set.rationale())).append("</div>");
            for (String parentId : set.requiredParentIds()) {
                KnowledgeChunkEntity parent = parents.get(parentId);
                if (parent == null) throw new IllegalArgumentException("EVALUATION_V2_REVIEW_PARENT_UNKNOWN");
                html.append("<div class=\"parent\"><div><strong>").append(escape(parent.headingPath)).append("</strong></div><div class=\"meta\">")
                        .append(escape(parentId)).append(" · tokenEstimate=").append(parent.tokenEstimate).append("</div><div class=\"content\">")
                        .append(escape(parent.content)).append("</div></div>");
            }
            html.append("</section>");
        }
        html.append("<section class=\"decision\"><strong>人工结论：</strong>");
        option(html, caseId, "APPROVED", "问题和证据均合理");
        option(html, caseId, "REWRITE", "问题需要重写");
        option(html, caseId, "GROUND_TRUTH_REVISE", "Evidence Set 需要调整");
        option(html, caseId, "REMOVE", "删除该样本");
        html.append("<div class=\"split\"><strong>冻结分组：</strong>");
        splitOption(html, caseId, "DEV", "DEV（用于阈值校准）");
        splitOption(html, caseId, "TEST", "TEST（仅最终确认）");
        html.append("</div><textarea data-case=\"").append(caseId).append("\" placeholder=\"记录证据是否足以回答、缺少什么或为什么越界\"></textarea></section></article>");
    }

    private static void option(StringBuilder html, String id, String value, String label) {
        html.append("<label><input type=\"radio\" name=\"").append(id).append("\" data-case=\"").append(id)
                .append("\" value=\"").append(value).append("\"> ").append(label).append("</label>");
    }

    private static void splitOption(StringBuilder html, String id, String value, String label) {
        html.append("<label><input type=\"radio\" name=\"split-").append(id).append("\" data-case=\"")
                .append(id).append("\" data-field=\"split\" value=\"").append(value).append("\"> ")
                .append(label).append("</label>");
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}

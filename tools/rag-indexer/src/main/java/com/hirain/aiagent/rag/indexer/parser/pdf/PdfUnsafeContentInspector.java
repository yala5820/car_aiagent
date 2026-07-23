package com.hirain.aiagent.rag.indexer.parser.pdf;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.pdmodel.PDDocument;

import java.io.IOException;
import java.nio.file.Path;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 只遍历 PDF 的 COS 对象以报告潜在主动内容；不解析、执行、打开或提取任何外部资源。
 */
final class PdfUnsafeContentInspector {
    private static final int MAX_VISITED_OBJECTS = 10_000;

    Set<String> inspect(Path path) throws IOException {
        try (PDDocument document = PDDocument.load(path.toFile())) {
            Set<String> findings = new LinkedHashSet<>();
            scan(document.getDocumentCatalog().getCOSObject(), new IdentityHashMap<>(), findings, new int[]{0});
            return Set.copyOf(findings);
        }
    }

    private void scan(COSBase value, IdentityHashMap<COSBase, Boolean> visited, Set<String> findings, int[] count) {
        if (value == null || visited.put(value, Boolean.TRUE) != null || ++count[0] > MAX_VISITED_OBJECTS) {
            return;
        }
        if (value instanceof COSObject object) {
            scan(object.getObject(), visited, findings, count);
            return;
        }
        if (value instanceof COSDictionary dictionary) {
            String action = dictionary.getNameAsString(COSName.S);
            if ("JavaScript".equals(action) || "Launch".equals(action) || "URI".equals(action) || "RichMediaExecute".equals(action)) {
                findings.add("PDF_IGNORED_ACTION_" + action.toUpperCase());
            }
            for (java.util.Map.Entry<COSName, COSBase> entry : dictionary.entrySet()) {
                String key = entry.getKey().getName();
                if ("JavaScript".equals(key) || "EmbeddedFiles".equals(key) || "OpenAction".equals(key)) {
                    findings.add("PDF_IGNORED_" + key.toUpperCase());
                }
                scan(entry.getValue(), visited, findings, count);
            }
        } else if (value instanceof COSArray array) {
            for (COSBase item : array) {
                scan(item, visited, findings, count);
            }
        }
    }
}

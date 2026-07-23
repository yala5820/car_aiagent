package com.hirain.aiagent.rag.document;

import com.hirain.aiagent.rag.model.SourceLocator;

/** 从可信 Evidence 元数据确定性渲染来源，绝不暴露 Chunk ID、排名或检索分数。 */
public final class SourceCitationRenderer {
    public String render(String evidenceId, String documentTitle, SourceLocator locator) {
        if (evidenceId == null || evidenceId.isBlank() || documentTitle == null || documentTitle.isBlank()) throw new IllegalArgumentException("引用必填字段为空");
        String chapter = locator.headingPath().isEmpty() ? "" : "“" + String.join(" > ", locator.headingPath()) + "”章节";
        return switch (locator.sourceFormat()) {
            case PDF -> "来源：[" + evidenceId + "]《" + documentTitle + "》" + chapter + pdfLocation(locator);
            case STATIC_HTML -> "来源：[" + evidenceId + "]《" + documentTitle + "》" + chapter + (locator.htmlElementId() == null ? "" : "（锚点：" + locator.htmlElementId() + "）");
            case MARKDOWN -> "来源：[" + evidenceId + "]《" + documentTitle + "》" + chapter + markdownLocation(locator);
        };
    }
    private static String pdfLocation(SourceLocator value) { return value.printedPageStartLabel() != null ? "，印刷页 " + value.printedPageStartLabel() + "（PDF 第 " + value.pdfPageStart() + " 页）" : "，PDF 第 " + value.pdfPageStart() + " 页"; }
    private static String markdownLocation(SourceLocator value) { return value.sourceLineStart() == 0 ? "" : value.sourceLineStart() == value.sourceLineEnd() ? "（源码第 " + value.sourceLineStart() + " 行）" : "（源码第 " + value.sourceLineStart() + "—" + value.sourceLineEnd() + " 行）"; }
}

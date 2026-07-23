package com.hirain.aiagent.rag.indexer.config;

import java.util.List;

/** V1 PDF 只允许文本型输入，扫描件和加密件在分类阶段拒绝。 */
public record PdfParserConfig(String tableStrategy, List<PdfTableStrategyOverride> tableStrategyOverrides) {
    public PdfParserConfig {
        tableStrategy = requireStrategy(tableStrategy);
        tableStrategyOverrides = List.copyOf(tableStrategyOverrides);
    }

    public String tableStrategyFor(String documentId, int physicalPage) {
        return tableStrategyOverrides.stream().filter(override -> override.appliesTo(documentId, physicalPage))
                .map(PdfTableStrategyOverride::strategy).findFirst().orElse(tableStrategy);
    }

    private static String requireStrategy(String strategy) {
        if (!List.of("AUTO", "LATTICE", "STREAM").contains(strategy)) {
            throw new IllegalArgumentException("PDF 表格策略无效");
        }
        return strategy;
    }
}

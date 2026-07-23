package com.hirain.aiagent.rag.indexer.parser.pdf;

/** AUTO 策略只依据页面是否存在可见绘制线索；实际表格仍需由 Validator 验证。 */
final class PdfTableStrategyResolver {
    PdfTableStrategy resolve(PdfTableStrategy requested, boolean hasRulingLines) {
        if (requested == PdfTableStrategy.LATTICE || requested == PdfTableStrategy.STREAM) {
            return requested;
        }
        return hasRulingLines ? PdfTableStrategy.LATTICE : PdfTableStrategy.STREAM;
    }
}

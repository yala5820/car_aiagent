package com.hirain.aiagent.rag.indexer.parser.pdf;

import com.hirain.aiagent.rag.indexer.model.BoundingBox;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PdfHeadingDetectorTest {
    @Test
    void shouldUseConfiguredFontScaleAndNumberingAsHeadingSignals() {
        PdfHeadingDetector detector = new PdfHeadingDetector(1.25f);

        assertTrue(detector.isHeading(line("标题", 16), 12));
        assertTrue(detector.isHeading(line("2.1 制动系统", 12), 12));
        assertFalse(detector.isHeading(line("普通正文", 12), 12));
    }

    private PdfTextLine line(String text, float fontSize) {
        return new PdfTextLine(text, new BoundingBox(0, 0, 10, 10), fontSize);
    }
}

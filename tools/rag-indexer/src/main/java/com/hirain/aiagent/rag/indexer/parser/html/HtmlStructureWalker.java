package com.hirain.aiagent.rag.indexer.parser.html;

import com.hirain.aiagent.rag.indexer.model.BlockType;
import com.hirain.aiagent.rag.indexer.model.ExtractionConfidence;
import com.hirain.aiagent.rag.indexer.model.StructuredBlock;
import org.jsoup.nodes.Element;

import java.util.ArrayList;
import java.util.List;

/** 以 DOM 文档顺序提取正文结构；表格交由 HtmlTableExtractor，不扁平化为段落。 */
final class HtmlStructureWalker {
    List<StructuredBlock> walk(Element root) {
        HtmlHeadingPathTracker headings = new HtmlHeadingPathTracker();
        HtmlSourceLocatorFactory locators = new HtmlSourceLocatorFactory();
        List<StructuredBlock> blocks = new ArrayList<>();
        int ordinal = 0;
        for (Element element : root.select("h1,h2,h3,h4,h5,h6,p,li,blockquote,pre")) {
            if (element.parents().stream().anyMatch(parent -> parent.tagName().equals("table"))) {
                continue;
            }
            String text = element.text().trim();
            if (text.isEmpty()) {
                continue;
            }
            boolean heading = element.tagName().matches("h[1-6]");
            if (heading) {
                headings.accept(Integer.parseInt(element.tagName().substring(1)), text);
            }
            blocks.add(new StructuredBlock(heading ? BlockType.HEADING : BlockType.PARAGRAPH, text,
                    locators.create(element, headings.currentPath(), ++ordinal), null, ExtractionConfidence.HIGH));
        }
        return List.copyOf(blocks);
    }
}

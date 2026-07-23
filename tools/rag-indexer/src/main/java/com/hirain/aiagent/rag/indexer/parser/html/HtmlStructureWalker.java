package com.hirain.aiagent.rag.indexer.parser.html;

import com.hirain.aiagent.rag.indexer.model.BlockType;
import com.hirain.aiagent.rag.indexer.model.BlockStructure;
import com.hirain.aiagent.rag.indexer.model.ExtractionConfidence;
import com.hirain.aiagent.rag.indexer.model.SequenceType;
import com.hirain.aiagent.rag.indexer.model.StructuredBlock;
import org.jsoup.nodes.Element;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** 以 DOM 文档顺序提取正文结构；表格交由 HtmlTableExtractor，不扁平化为段落。 */
final class HtmlStructureWalker {
    List<StructuredBlock> walk(Element root) {
        HtmlHeadingPathTracker headings = new HtmlHeadingPathTracker();
        HtmlSourceLocatorFactory locators = new HtmlSourceLocatorFactory();
        List<StructuredBlock> blocks = new ArrayList<>();
        Map<Element, Integer> listOrdinals = new IdentityHashMap<>();
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
            int currentOrdinal=++ordinal;
            Element list=element.closest("ol,ul"); boolean listItem="li".equals(element.tagName());
            int depth=list==null?0:(int)list.parents().stream().filter(parent->parent.tagName().equals("ol")||parent.tagName().equals("ul")).count()+1;
            SequenceType sequence=list==null?SequenceType.NONE:("ol".equals(list.tagName())?SequenceType.ORDERED_STEPS:SequenceType.BULLET_LIST);
            // DOM 对象地址会随 JVM 运行变化，不能作为可复现构建的结构身份；采用文档遍历顺序编号。
            String group=list==null?null:"html-list-"+listOrdinals.computeIfAbsent(list, ignored -> listOrdinals.size()+1);
            BlockType type=heading?BlockType.HEADING:(listItem?BlockType.LIST_ITEM:BlockType.PARAGRAPH);
            int level=heading?Integer.parseInt(element.tagName().substring(1)):0;
            blocks.add(new StructuredBlock(type, text, locators.create(element, headings.currentPath(), currentOrdinal), null, ExtractionConfidence.HIGH,
                    new BlockStructure(level,headings.currentPath(),group,sequence,depth,listItem,currentOrdinal)));
        }
        return List.copyOf(blocks);
    }
}

package com.hirain.aiagent.rag.indexer.parser.markdown;

import com.hirain.aiagent.rag.indexer.model.BlockType;
import com.hirain.aiagent.rag.indexer.model.BlockStructure;
import com.hirain.aiagent.rag.indexer.config.MarkdownParserConfig;
import com.hirain.aiagent.rag.indexer.model.DiagnosticSeverity;
import com.hirain.aiagent.rag.indexer.model.ExtractionConfidence;
import com.hirain.aiagent.rag.indexer.model.ParseDiagnostic;
import com.hirain.aiagent.rag.indexer.model.ParseResult;
import com.hirain.aiagent.rag.indexer.model.SourceDocument;
import com.hirain.aiagent.rag.indexer.model.SourceFormat;
import com.hirain.aiagent.rag.indexer.model.SourceLocator;
import com.hirain.aiagent.rag.indexer.model.StructuredBlock;
import com.hirain.aiagent.rag.indexer.model.SequenceType;
import com.hirain.aiagent.rag.indexer.model.TableBlock;
import com.hirain.aiagent.rag.indexer.model.TableExtractionMode;
import com.hirain.aiagent.rag.indexer.parser.DocumentParser;
import com.hirain.aiagent.rag.indexer.parser.table.TableNormalizer;
import com.hirain.aiagent.rag.indexer.parser.table.TableValidator;
import org.commonmark.Extension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.Node;
import org.commonmark.node.Paragraph;
import org.commonmark.node.ListItem;
import org.commonmark.node.BulletList;
import org.commonmark.node.OrderedList;
import org.commonmark.parser.Parser;
import org.commonmark.parser.IncludeSourceSpans;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** 本地 Markdown AST Parser；不执行 HTML、不加载包含文件、不进入 Chunk/Embedding。 */
public final class MarkdownDocumentParser implements DocumentParser {
    private final Parser parser;

    public MarkdownDocumentParser() {
        this(new MarkdownParserConfig("COMMONMARK", List.of("GFM_TABLE")));
    }

    public MarkdownDocumentParser(MarkdownParserConfig config) {
        if (!"COMMONMARK".equals(config.syntax()) || !config.extensions().equals(List.of("GFM_TABLE"))) {
            throw new IllegalArgumentException("MARKDOWN_EXTENSION_UNSUPPORTED");
        }
        this.parser = Parser.builder().extensions(List.<Extension>of(TablesExtension.create()))
                .includeSourceSpans(IncludeSourceSpans.BLOCKS).build();
    }

    @Override
    public SourceFormat sourceFormat() {
        return SourceFormat.MARKDOWN;
    }

    @Override
    public ParseResult parse(SourceDocument source) {
        try {
            String raw = Files.readString(source.path(), StandardCharsets.UTF_8);
            int bodyOffset = new MarkdownFrontMatterDetector().bodyStartOffset(raw);
            String body = raw.substring(bodyOffset);
            String parseSource = "\n".repeat((int) raw.substring(0, bodyOffset).chars().filter(value -> value == '\n').count()) + body;
            List<ParseDiagnostic> diagnostics = new ArrayList<>();
            if (new MarkdownExtensionPolicy().containsRawHtml(body)) {
                diagnostics.add(diagnostic(source, "MARKDOWN_RAW_HTML_IGNORED", DiagnosticSeverity.WARNING, "Markdown 原始 HTML 未执行且不进入正文"));
            }
            List<StructuredBlock> blocks = new ArrayList<>();
            List<TableBlock> tables = new ArrayList<>();
            walk(parser.parse(parseSource), source, new MarkdownHeadingPathTracker(), blocks, tables, diagnostics, new int[]{0}, new java.util.IdentityHashMap<>());
            return new ParseResult(blocks, tables, diagnostics);
        } catch (Exception exception) {
            return new ParseResult(List.of(), List.of(diagnostic(source, "MARKDOWN_PARSE_FAILED", DiagnosticSeverity.ERROR, "Markdown 解析失败")));
        }
    }

    private void walk(Node node, SourceDocument source, MarkdownHeadingPathTracker headings, List<StructuredBlock> blocks, List<TableBlock> tables,
                      List<ParseDiagnostic> diagnostics, int[] ordinal, java.util.IdentityHashMap<Node,String> listGroups) {
        for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof Heading heading) {
                String text = text(heading);
                headings.accept(heading.getLevel(), text);
                blocks.add(block(BlockType.HEADING, text, source, headings.path(), ++ordinal[0], heading, new BlockStructure(heading.getLevel(),headings.path(),null,SequenceType.NONE,0,false,ordinal[0])));
            } else if (child instanceof ListItem item) {
                Node list=item.getParent(); boolean ordered=list instanceof OrderedList;
                String group=listGroups.computeIfAbsent(list, ignored -> "markdown-list-"+(listGroups.size()+1));
                int depth=0; for(Node parent=list;parent!=null;parent=parent.getParent())if(parent instanceof BulletList||parent instanceof OrderedList)depth++;
                int current=++ordinal[0]; String path=headings.path();
                blocks.add(block(BlockType.LIST_ITEM,text(item),source,path,current,item,new BlockStructure(0,path,group,ordered?SequenceType.ORDERED_STEPS:SequenceType.BULLET_LIST,depth,true,current)));
            } else if (child instanceof Paragraph paragraph) {
                int current=++ordinal[0]; blocks.add(block(BlockType.PARAGRAPH, text(paragraph), source, headings.path(), current, paragraph,new BlockStructure(0,headings.path(),null,SequenceType.NONE,0,false,current)));
            } else if (child instanceof FencedCodeBlock code) {
                int current=++ordinal[0]; blocks.add(block(BlockType.CODE, code.getLiteral().trim(), source, headings.path(), current, code,new BlockStructure(0,headings.path(),null,SequenceType.NONE,0,true,current)));
            } else if (child instanceof HtmlBlock) {
                diagnostics.add(diagnostic(source, "MARKDOWN_RAW_HTML_IGNORED", DiagnosticSeverity.WARNING, "Markdown HTML Block 已忽略"));
            } else if (child instanceof org.commonmark.ext.gfm.tables.TableBlock table) {
                extractTable(table, source, headings.path(), tables, diagnostics, ++ordinal[0]);
            } else {
                walk(child, source, headings, blocks, tables, diagnostics, ordinal,listGroups);
            }
        }
    }

    private void extractTable(org.commonmark.ext.gfm.tables.TableBlock table, SourceDocument source, String headingPath,
                              List<TableBlock> tables, List<ParseDiagnostic> diagnostics, int ordinal) {
        List<List<String>> rows = new ArrayList<>();
        for (Node section = table.getFirstChild(); section != null; section = section.getNext()) {
            for (Node row = section.getFirstChild(); row != null; row = row.getNext()) {
                if (row instanceof org.commonmark.ext.gfm.tables.TableRow) {
                    List<String> cells = new ArrayList<>();
                    for (Node cell = row.getFirstChild(); cell != null; cell = cell.getNext()) {
                        if (cell instanceof org.commonmark.ext.gfm.tables.TableCell) {
                            cells.add(text(cell));
                        }
                    }
                    if (!cells.isEmpty()) {
                        rows.add(cells);
                    }
                }
            }
        }
        rows = new TableNormalizer().normalize(rows);
        SourceLocator locator = new SourceLocator(SourceFormat.MARKDOWN, 0, 0, null, 0, 0, headingPath, ordinal);
        if (rows.size() < 2) {
            diagnostics.add(new ParseDiagnostic("MARKDOWN_TABLE_INSUFFICIENT_ROWS", DiagnosticSeverity.WARNING,
                    source.metadata().documentId(), locator, "Markdown 表格不足以形成表头和数据行"));
            return;
        }
        String reason = new TableValidator().validate(rows.get(0), rows.subList(1, rows.size()));
        if (reason != null) {
            diagnostics.add(new ParseDiagnostic(reason, DiagnosticSeverity.WARNING, source.metadata().documentId(), locator, "Markdown 表格结构无效"));
            return;
        }
        tables.add(new TableBlock("", rows.get(0), rows.subList(1, rows.size()), locator, TableExtractionMode.MARKDOWN, ExtractionConfidence.HIGH));
    }

    private StructuredBlock block(BlockType type, String text, SourceDocument source, String path, int ordinal, Node node, BlockStructure structure) {
        int line = node.getSourceSpans().isEmpty() ? 0 : node.getSourceSpans().get(0).getLineIndex() + 1;
        return new StructuredBlock(type, text, new SourceLocator(SourceFormat.MARKDOWN, 0, 0, null, line, line, path, ordinal), null, ExtractionConfidence.HIGH,structure);
    }

    private ParseDiagnostic diagnostic(SourceDocument source, String reason, DiagnosticSeverity severity, String summary) {
        return new ParseDiagnostic(reason, severity, source.metadata().documentId(),
                new SourceLocator(SourceFormat.MARKDOWN, 0, 0, null, 0, 0, null, 0), summary);
    }

    private String text(Node node) {
        StringBuilder output = new StringBuilder();
        appendText(node, output);
        return output.toString().trim();
    }

    private void appendText(Node node, StringBuilder output) {
        for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof org.commonmark.node.Text value) {
                output.append(value.getLiteral());
            } else if (child instanceof org.commonmark.node.Code value) {
                output.append(value.getLiteral());
            } else {
                appendText(child, output);
            }
        }
    }
}

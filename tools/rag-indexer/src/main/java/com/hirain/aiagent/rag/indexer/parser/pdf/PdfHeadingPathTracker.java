package com.hirain.aiagent.rag.indexer.parser.pdf;

/** PDF 标题路径只由已经确认层级的标题推进；无层级正文继承当前可靠路径。 */
final class PdfHeadingPathTracker {
    private final String[] headings=new String[6];
    void accept(int level,String text){if(level<1||level>6)return;headings[level-1]=text;for(int i=level;i<headings.length;i++)headings[i]=null;}
    String path(){return java.util.Arrays.stream(headings).filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.joining(" > "));}
}

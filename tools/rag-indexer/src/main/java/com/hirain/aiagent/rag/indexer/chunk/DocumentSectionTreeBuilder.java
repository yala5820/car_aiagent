package com.hirain.aiagent.rag.indexer.chunk;

import com.hirain.aiagent.rag.indexer.model.BlockType;
import com.hirain.aiagent.rag.indexer.model.StructuredBlock;
import java.util.*;

/** 仅使用 Parser 已确认的 level/path 生成树，禁止在 Chunker 重新猜标题层级。 */
final class DocumentSectionTreeBuilder {
    DocumentSection build(List<StructuredBlock> blocks){
        DocumentSection root=new DocumentSection("",0); Map<String,DocumentSection> nodes=new LinkedHashMap<>();nodes.put("",root);
        for(StructuredBlock block:blocks){String path=block.structure().sectionPath(); if(path==null)path=""; int level=block.structure().headingLevel();
            if(block.type()==BlockType.HEADING&&level>0){ensure(nodes,root,path,level); continue;}
            ensure(nodes,root,path,Math.max(1,segments(path))).directBlocks.add(block);
        } return root;
    }
    private static DocumentSection ensure(Map<String,DocumentSection> nodes,DocumentSection root,String path,int depth){DocumentSection existing=nodes.get(path);if(existing!=null)return existing;String parent=parentPath(path);DocumentSection parentNode=ensure(nodes,root,parent,Math.max(0,depth-1));DocumentSection created=new DocumentSection(path,depth);nodes.put(path,created);parentNode.children.add(created);return created;}
    private static int segments(String value){return value.isBlank()?0:value.split(" > ").length;}
    private static String parentPath(String value){int index=value.lastIndexOf(" > ");return index<0?"":value.substring(0,index);}
}

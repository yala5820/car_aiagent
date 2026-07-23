package com.hirain.aiagent.rag.indexer.pipeline;

import com.hirain.aiagent.rag.indexer.lexical.LexicalIndex;
import com.hirain.aiagent.rag.indexer.store.KnowledgeDocumentEntityMapper;
import com.hirain.aiagent.rag.indexer.store.KnowledgeStoreMetadataMapper;
import com.hirain.aiagent.rag.indexer.store.StoreMetadataInput;
import com.hirain.aiagent.rag.indexer.store.StoreModelValidator;
import com.hirain.aiagent.rag.indexer.store.StoreWriteModel;
import com.hirain.aiagent.rag.store.entity.KnowledgeChunkEntity;
import com.hirain.aiagent.rag.store.entity.KnowledgeDocumentEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 汇总跨文档的确定性构建结果，生成 ObjectBox Writer 唯一允许接收的逻辑模型。
 *
 * <p>该阶段不读取原始文件、不调用模型、也不创建 Store；这样 Store 写入失败时不会触发重解析或
 * 重复计费。全局词法索引必须在所有文档 Child 的稳定 ID 已确定后传入，避免 posting 指向临时 ID。</p>
 */
public final class StoreWriteModelAssembler {
    private final KnowledgeDocumentEntityMapper documentMapper = new KnowledgeDocumentEntityMapper();
    private final StoreModelStageAssembler chunkMapper = new StoreModelStageAssembler();
    private final KnowledgeStoreMetadataMapper metadataMapper = new KnowledgeStoreMetadataMapper();

    public StoreWriteModel assemble(List<DocumentBuildState> states, Map<String, ChunkStableIds> idsByDocument,
                                    LexicalIndex globalLexicalIndex, StoreMetadataInput metadataInput) {
        List<KnowledgeDocumentEntity> documents = new ArrayList<>();
        List<KnowledgeChunkEntity> chunks = new ArrayList<>();
        for (DocumentBuildState state : states) {
            ChunkStableIds ids = idsByDocument.get(state.corpusDocument().documentId());
            if (ids == null) {
                throw new IllegalStateException("文档缺少稳定 Chunk ID：" + state.corpusDocument().documentId());
            }
            documents.add(documentMapper.map(state.corpusDocument(), sourceCharset(state), pdfPageCount(state)));
            chunks.addAll(chunkMapper.mapChunks(state, ids, globalLexicalIndex));
        }
        StoreWriteModel model = new StoreWriteModel(metadataMapper.map(metadataInput), documents, chunks, globalLexicalIndex);
        new StoreModelValidator().validate(model);
        return model;
    }

    private static String sourceCharset(DocumentBuildState state) {
        return "PDF".equals(state.corpusDocument().sourceFormat()) ? "" : "UTF-8";
    }

    private static int pdfPageCount(DocumentBuildState state) {
        if (!"PDF".equals(state.corpusDocument().sourceFormat())) {
            return 0;
        }
        if (state.parseResult() == null) {
            throw new IllegalStateException("PDF 文档尚未完成解析：" + state.corpusDocument().documentId());
        }
        int blockPages = state.parseResult().blocks().stream()
                .mapToInt(block -> block.locator().pdfPageEnd()).max().orElse(0);
        int tablePages = state.parseResult().tables().stream()
                .mapToInt(table -> table.locator().pdfPageEnd()).max().orElse(0);
        int pageCount = Math.max(blockPages, tablePages);
        if (pageCount < 1) {
            throw new IllegalStateException("PDF 文档没有可验证页码：" + state.corpusDocument().documentId());
        }
        return pageCount;
    }
}

package com.hirain.aiagent.rag.indexer.store;

import com.hirain.aiagent.rag.indexer.corpus.CorpusDocumentDefinition;
import com.hirain.aiagent.rag.store.entity.KnowledgeDocumentEntity;

import java.nio.file.Path;

/** 只复制经 Corpus 校验的声明字段；正文、标题解析结果和绝对路径均不能覆盖它们。 */
public final class KnowledgeDocumentEntityMapper {
    public KnowledgeDocumentEntity map(CorpusDocumentDefinition source, String sourceCharset, int pageCount) {
        if (pageCount < 0 || ("PDF".equals(source.sourceFormat()) && pageCount <= 0)) {
            throw new IllegalArgumentException("文档页数与格式不一致");
        }
        if (!"PDF".equals(source.sourceFormat()) && pageCount != 0) {
            throw new IllegalArgumentException("非 PDF 文档页数必须为 0");
        }
        KnowledgeDocumentEntity entity = new KnowledgeDocumentEntity();
        entity.documentId = source.documentId();
        entity.documentTitle = source.title();
        entity.documentType = source.documentType();
        entity.documentVersion = source.documentVersion();
        entity.language = source.language();
        entity.vehicleModel = source.applicability().vehicleModel();
        entity.modelYear = source.applicability().modelYear();
        entity.region = source.applicability().region();
        entity.softwareVersion = source.applicability().softwareVersion();
        entity.configurationCode = source.applicability().configurationCode();
        entity.sourceFormat = source.sourceFormat();
        entity.sourceFileName = Path.of(source.relativePath()).getFileName().toString();
        entity.sourceSha256 = source.expectedSha256();
        entity.sourceCharset = sourceCharset == null ? "" : sourceCharset;
        entity.pageCount = pageCount;
        return entity;
    }
}

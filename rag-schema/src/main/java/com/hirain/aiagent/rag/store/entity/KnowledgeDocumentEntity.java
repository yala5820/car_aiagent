package com.hirain.aiagent.rag.store.entity;

import io.objectbox.annotation.Entity;
import io.objectbox.annotation.Id;
import io.objectbox.annotation.Index;
import io.objectbox.annotation.IndexType;
import io.objectbox.annotation.Unique;

/**
 * 语料文档的可信 Metadata。模型、用户和额外上下文均不能生成或覆盖这些字段，
 * 后续检索以它们作为 Scope 与适用性过滤的事实来源。
 */
@Entity
public class KnowledgeDocumentEntity {

    @Id
    public long id;
    @Unique
    @Index(type = IndexType.VALUE)
    public String documentId;
    public String documentTitle;
    public String documentType;
    public String documentVersion;
    public String language;
    public String vehicleModel;
    public String modelYear;
    public String region;
    public String softwareVersion;
    public String configurationCode;
    public String sourceFormat;
    public String sourceFileName;
    public String sourceSha256;
    public String sourceCharset;
    /** PDF 必须大于 0；HTML/Markdown 按协议落库为 0。 */
    public int pageCount;

    public KnowledgeDocumentEntity() {
    }
}

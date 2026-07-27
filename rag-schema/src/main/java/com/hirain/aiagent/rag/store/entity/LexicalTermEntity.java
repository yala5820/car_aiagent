package com.hirain.aiagent.rag.store.entity;

import io.objectbox.annotation.Entity;
import io.objectbox.annotation.Id;
import io.objectbox.annotation.Index;
import io.objectbox.annotation.IndexType;
import io.objectbox.annotation.Unique;

/**
 * BM25 的本地倒排表。postings 只允许引用同一 Store 内的 Child Entity ID，
 * 因而数据库重建时必须与 Child 集合一起全量重建，禁止跨 Bundle 复用。
 */
@Entity
public class LexicalTermEntity {

    @Id
    public long id;
    @Unique
    @Index(type = IndexType.VALUE)
    public String term;
    /** TITLE 或 BODY；term 使用 field 前缀保证旧版单字段唯一约束仍然可用。 */
    public String field;
    public String rawTerm;
    public int documentFrequency;
    public long[] chunkEntityIds;
    public int[] termFrequencies;

    public LexicalTermEntity() {
    }
}

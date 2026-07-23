package com.hirain.aiagent.rag.store.entity;

import com.hirain.aiagent.rag.store.KnowledgeStoreContract;

import io.objectbox.annotation.Entity;
import io.objectbox.annotation.HnswIndex;
import io.objectbox.annotation.Id;
import io.objectbox.annotation.Index;
import io.objectbox.annotation.IndexType;
import io.objectbox.annotation.Unique;
import io.objectbox.annotation.VectorDistanceType;

/**
 * Parent/Child Chunk 共用一个 Entity，避免向量召回后的跨实体读取。Child 冗余文档
 * Metadata 是受控复制而非二次推断，确保 Scope 过滤发生在 RRF/Rerank 之前。
 */
@Entity
public class KnowledgeChunkEntity {

    @Id
    public long id;
    @Unique
    @Index(type = IndexType.VALUE)
    public String chunkId;
    /** 协议值仅为 PARENT 或 CHILD。 */
    @Index(type = IndexType.VALUE)
    public String chunkLevel;
    @Index(type = IndexType.VALUE)
    public String parentChunkId;
    @Index(type = IndexType.VALUE)
    public String documentId;
    public String documentTitle;
    public String documentVersion;
    public String documentType;
    public String language;
    @Index(type = IndexType.VALUE)
    public String vehicleModel;
    @Index(type = IndexType.VALUE)
    public String modelYear;
    @Index(type = IndexType.VALUE)
    public String region;
    @Index(type = IndexType.VALUE)
    public String softwareVersion;
    @Index(type = IndexType.VALUE)
    public String configurationCode;
    public String chunkType;
    public String headingPath;
    public String chapter;
    public String section;
    public String content;
    public String sourceFormat;
    /** 非 PDF 格式使用 0，领域映射必须还原为“不适用”而不是第 0 页。 */
    public int pdfPageStart;
    public int pdfPageEnd;
    public String printedPageStartLabel;
    public String printedPageEndLabel;
    public String htmlElementId;
    /** 非 HTML/Markdown 格式使用 0，领域映射必须还原为空。 */
    public int sourceLineStart;
    public int sourceLineEnd;
    public int sectionOrdinal;
    public int ordinal;
    public int tokenEstimate;
    public int lexicalDocumentLength;
    /** Parent 保持 null；可检索 Child 必须写入恰好 1024 维有限向量。 */
    @HnswIndex(
            dimensions = KnowledgeStoreContract.EMBEDDING_DIMENSION,
            neighborsPerNode = KnowledgeStoreContract.HNSW_NEIGHBORS_PER_NODE,
            indexingSearchCount = KnowledgeStoreContract.HNSW_INDEXING_SEARCH_COUNT,
            distanceType = VectorDistanceType.COSINE,
            reparationBacklinkProbability = KnowledgeStoreContract.HNSW_REPARATION_BACKLINK_PROBABILITY,
            vectorCacheHintSizeKB = KnowledgeStoreContract.HNSW_VECTOR_CACHE_HINT_SIZE_KB
    )
    public float[] embedding;

    public KnowledgeChunkEntity() {
    }
}

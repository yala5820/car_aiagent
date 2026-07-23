package com.hirain.aiagent.rag.indexer.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 将不含正文的质量统计写到本地审核运行目录；禁止覆盖历史审核结果。 */
public final class ChunkQualityReportWriter {
    private final ObjectMapper json=new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    public void write(Path file,ChunkQualityReport report)throws IOException{if(Files.exists(file))throw new IllegalArgumentException("Chunk 质量报告已存在，禁止覆盖");json.writeValue(file.toFile(),report);}
}

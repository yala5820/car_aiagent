package com.hirain.aiagent.rag.indexer.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Path;

/** 稳定输出审计 JSON；报告并非 APK Asset，仍需避免泄露正文或本机路径。 */
public final class BuildReportWriter {
    private final ObjectMapper json = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    public void write(Path file, BuildReport report) throws IOException { json.writeValue(file.toFile(), report); }
}

package com.hirain.aiagent.rag.indexer.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Checkpoint 位于 Run 私有目录，拒绝无匹配指纹的恢复。 */
public final class BuildCheckpointStore {
    private final ObjectMapper json = new ObjectMapper();
    public void save(Path run, BuildCheckpoint checkpoint) throws IOException { json.writeValue(run.resolve("checkpoint.json").toFile(), checkpoint); }
    public BuildCheckpoint loadMatching(Path run, BuildCheckpoint expected) throws IOException {
        Path file = run.resolve("checkpoint.json"); if (!Files.isRegularFile(file)) return null; BuildCheckpoint actual = json.readValue(file.toFile(), BuildCheckpoint.class);
        if (!actual.schemaFingerprint().equals(expected.schemaFingerprint()) || !actual.parserFingerprint().equals(expected.parserFingerprint()) || !actual.chunkFingerprint().equals(expected.chunkFingerprint()) || !actual.embeddingTemplateFingerprint().equals(expected.embeddingTemplateFingerprint())) return null;
        return actual;
    }
}

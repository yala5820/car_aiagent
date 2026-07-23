package com.hirain.aiagent.rag.indexer.embedding;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** 本地二进制缓存使用原子临时文件替换；读到非法维度或内容时不抛出正文相关信息，直接 miss。 */
public final class FileEmbeddingCache implements EmbeddingCache {
    private final Path directory;

    public FileEmbeddingCache(Path directory) {
        this.directory = directory;
    }

    @Override
    public Optional<float[]> get(EmbeddingCacheKey key) {
        Path file = directory.resolve(key.value() + ".vec");
        try (DataInputStream input = new DataInputStream(Files.newInputStream(file))) {
            int size = input.readInt();
            if (size != EmbeddingVectorValidator.DIMENSION) return Optional.empty();
            float[] vector = new float[size];
            for (int index = 0; index < size; index++) vector[index] = input.readFloat();
            new EmbeddingVectorValidator().validate(vector);
            return Optional.of(vector);
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    @Override
    public void put(EmbeddingCacheKey key, float[] vector) {
        try {
            new EmbeddingVectorValidator().validate(vector);
            Files.createDirectories(directory);
            Path temp = directory.resolve(key.value() + ".tmp");
            try (DataOutputStream output = new DataOutputStream(Files.newOutputStream(temp))) {
                output.writeInt(vector.length);
                for (float value : vector) output.writeFloat(value);
            }
            Files.move(temp, directory.resolve(key.value() + ".vec"), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception exception) {
            throw new IllegalStateException("EMBEDDING_CACHE_WRITE_FAILED");
        }
    }
}

package com.hirain.aiagent.rag.indexer.store;

import com.hirain.aiagent.rag.store.MyObjectBox;

import io.objectbox.BoxStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 统一创建独享 Store；调用方不得复用旧目录实施 V1 增量更新。 */
public final class ObjectBoxStoreFactory {
    public BoxStore createEmpty(Path directory) throws IOException {
        if (Files.exists(directory)) {
            try (var entries = Files.list(directory)) {
                if (entries.findAny().isPresent()) throw new IllegalArgumentException("ObjectBox staging 目录必须为空");
            }
        }
        Files.createDirectories(directory);
        return MyObjectBox.builder().directory(directory.toFile()).build();
    }

    public BoxStore openExisting(Path directory) {
        return MyObjectBox.builder().directory(directory.toFile()).build();
    }

    /** 评测只允许查询已验证 Bundle；只读打开避免评测命令成为数据库写入者。 */
    public BoxStore openReadOnlyExisting(Path directory) {
        return MyObjectBox.builder().directory(directory.toFile()).readOnly().build();
    }
}

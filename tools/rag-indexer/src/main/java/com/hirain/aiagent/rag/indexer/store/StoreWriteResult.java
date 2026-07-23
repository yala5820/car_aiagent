package com.hirain.aiagent.rag.indexer.store;

import java.nio.file.Path;

/** G402 成功结果只指向 staging Store，G404 才有资格把验证通过的产物发布到输出目录。 */
public record StoreWriteResult(Path stagingDirectory, StoreStatistics statistics) { }

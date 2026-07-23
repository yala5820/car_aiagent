package com.hirain.aiagent.rag.indexer.store;

import io.objectbox.BoxStore;

import java.io.IOException;
import java.nio.file.Path;

/** G402 写库协调器：先校验逻辑模型，再关闭 Store，最后重新打开验证。 */
public final class ObjectBoxKnowledgeStoreWriter {
    private final ObjectBoxStoreFactory factory = new ObjectBoxStoreFactory();
    private final DeterministicEntityWriter entityWriter = new DeterministicEntityWriter();
    private final ObjectBoxStoreVerifier verifier = new ObjectBoxStoreVerifier();

    public StoreWriteResult write(Path stagingDirectory, StoreWriteModel model) throws IOException {
        new StoreModelValidator().validate(model);
        try (BoxStore store = factory.createEmpty(stagingDirectory)) {
            entityWriter.write(store, model);
        }
        StoreWriteResult result = new StoreWriteResult(stagingDirectory, verifier.verify(stagingDirectory));
        // ObjectBox 会为运行期互斥创建 lock.mdb；它不属于三文件 Bundle 交付协议。
        java.nio.file.Files.deleteIfExists(com.hirain.aiagent.rag.indexer.artifact.BundleLayout.runtimeLock(stagingDirectory));
        return result;
    }
}

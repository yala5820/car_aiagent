package com.hirain.aiagent.rag.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.List;
import org.junit.Test;

/** 验证业务层只能经 Gateway 从离线预构建 Store 读取 Metadata 与指定 Child。 */
public class ObjectBoxKnowledgeStoreGatewayInstrumentedTest {
    @Test public void readsOfflineFixtureThroughReadOnlyGateway() throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File directory = new File(target.getCacheDir(), "rag-gateway-" + System.nanoTime());
        assertTrue(directory.mkdirs());
        copy(InstrumentationRegistry.getInstrumentation().getContext(), "rag/knowledge_db/data.mdb", new File(directory, "data.mdb"));
        try (ObjectBoxKnowledgeStoreGateway gateway = new ObjectBoxKnowledgeStoreGateway(MyObjectBox.builder().androidContext(target).directory(directory).build())) {
            assertEquals("demo-model-2026-cn-demo-version-default", gateway.metadata().knowledgeScopeId);
            List<com.hirain.aiagent.rag.store.entity.KnowledgeChunkEntity> children = gateway.childChunksByIds(List.of("pdf-child", "missing"));
            assertEquals(1, children.size());
            assertNotNull(children.get(0).embedding);
        }
    }

    private static void copy(Context context, String assetPath, File destination) throws Exception {
        try (InputStream input = context.getAssets().open(assetPath); FileOutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[8 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            output.getFD().sync();
        }
    }
}

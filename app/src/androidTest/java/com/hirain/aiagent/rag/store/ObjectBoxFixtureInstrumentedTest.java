package com.hirain.aiagent.rag.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.platform.app.InstrumentationRegistry;

import com.hirain.aiagent.rag.store.entity.KnowledgeChunkEntity;
import com.hirain.aiagent.rag.store.entity.KnowledgeChunkEntity_;
import com.hirain.aiagent.rag.store.entity.KnowledgeStoreMetadataEntity;
import com.hirain.aiagent.rag.store.entity.LexicalTermEntity;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.List;

import io.objectbox.Box;
import io.objectbox.BoxStore;
import io.objectbox.query.Query;
import io.objectbox.query.QueryBuilder;

/**
 * 真实 Android 运行时的最小跨端验证。Fixture 只能来自离线端 generated assets，
 * 本测试不写入任何知识 Entity；复制文件仅为让 ObjectBox 在应用私有目录打开预构建 DB。
 */
public class ObjectBoxFixtureInstrumentedTest {

    @Test
    public void opensOfflineFixtureAndQueriesDenseLexicalAndScope() throws Exception {
        Context targetContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Context testContext = InstrumentationRegistry.getInstrumentation().getContext();
        File storeDirectory = new File(targetContext.getCacheDir(), "rag-objectbox-fixture-" + System.nanoTime());
        assertTrue(storeDirectory.mkdirs());
        // Fixture 由 androidTest assets 打包，必须通过测试 APK 的 Context 读取；数据库仍在目标应用私有目录打开。
        copyAsset(testContext, "rag/knowledge_db/data.mdb", new File(storeDirectory, "data.mdb"));

        try (BoxStore store = MyObjectBox.builder()
                .androidContext(targetContext)
                .directory(storeDirectory)
                .build()) {
            Box<KnowledgeStoreMetadataEntity> metadata = store.boxFor(KnowledgeStoreMetadataEntity.class);
            Box<KnowledgeChunkEntity> chunks = store.boxFor(KnowledgeChunkEntity.class);
            Box<LexicalTermEntity> terms = store.boxFor(LexicalTermEntity.class);

            assertEquals(1, metadata.count());
            assertEquals("demo-model-2026-cn-demo-version-default", metadata.getAll().get(0).knowledgeScopeId);
            assertEquals(1, terms.count());
            assertEquals("制动液", terms.getAll().get(0).term);
            assertEquals(3, terms.getAll().get(0).chunkEntityIds.length);

            try (Query<KnowledgeChunkEntity> denseQuery = chunks.query()
                    .nearestNeighbors(KnowledgeChunkEntity_.embedding, vector(), 3)
                    .build()) {
                List<KnowledgeChunkEntity> denseMatches = denseQuery.find();
                assertFalse(denseMatches.isEmpty());
                assertNotNull(denseMatches.get(0).embedding);
                assertEquals(1024, denseMatches.get(0).embedding.length);
            }

            try (Query<KnowledgeChunkEntity> scopeQuery = chunks.query()
                    .equal(KnowledgeChunkEntity_.vehicleModel, "DEMO_MODEL", QueryBuilder.StringOrder.CASE_SENSITIVE)
                    .and()
                    .equal(KnowledgeChunkEntity_.region, "CN", QueryBuilder.StringOrder.CASE_SENSITIVE)
                    .build()) {
                assertEquals(6, scopeQuery.count());
            }

            // Scope 是检索前置资格条件；不匹配的地区不能因向量或词法命中而泄漏进候选集。
            try (Query<KnowledgeChunkEntity> rejectedScopeQuery = chunks.query()
                    .equal(KnowledgeChunkEntity_.vehicleModel, "DEMO_MODEL", QueryBuilder.StringOrder.CASE_SENSITIVE)
                    .and()
                    .equal(KnowledgeChunkEntity_.region, "US", QueryBuilder.StringOrder.CASE_SENSITIVE)
                    .build()) {
                assertEquals(0, rejectedScopeQuery.count());
            }

            // 三种输入格式必须能保留可还原的 SourceLocator，而不是只证明数据库可以打开。
            KnowledgeChunkEntity pdfChild;
            try (Query<KnowledgeChunkEntity> query = chunks.query()
                    .equal(KnowledgeChunkEntity_.chunkId, "pdf-child", QueryBuilder.StringOrder.CASE_SENSITIVE)
                    .build()) {
                pdfChild = query.findFirst();
            }
            assertNotNull(pdfChild);
            assertEquals("PDF", pdfChild.sourceFormat);
            assertEquals(12, pdfChild.pdfPageStart);
            assertEquals(12, pdfChild.pdfPageEnd);

            KnowledgeChunkEntity htmlChild;
            try (Query<KnowledgeChunkEntity> query = chunks.query()
                    .equal(KnowledgeChunkEntity_.chunkId, "html-child", QueryBuilder.StringOrder.CASE_SENSITIVE)
                    .build()) {
                htmlChild = query.findFirst();
            }
            assertNotNull(htmlChild);
            assertEquals("STATIC_HTML", htmlChild.sourceFormat);
            assertEquals("fixture-brake-fluid", htmlChild.htmlElementId);

            KnowledgeChunkEntity markdownChild;
            try (Query<KnowledgeChunkEntity> query = chunks.query()
                    .equal(KnowledgeChunkEntity_.chunkId, "md-child", QueryBuilder.StringOrder.CASE_SENSITIVE)
                    .build()) {
                markdownChild = query.findFirst();
            }
            assertNotNull(markdownChild);
            assertEquals("MARKDOWN", markdownChild.sourceFormat);
            assertEquals(41, markdownChild.sourceLineStart);
            assertEquals(45, markdownChild.sourceLineEnd);
        }
    }

    private static float[] vector() {
        float[] vector = new float[1024];
        for (int index = 0; index < vector.length; index++) {
            vector[index] = 0.01f + index / 100_000f;
        }
        return vector;
    }

    private static void copyAsset(Context context, String assetPath, File destination) throws Exception {
        try (InputStream input = context.getAssets().open(assetPath);
             FileOutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[8 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            output.getFD().sync();
        }
    }
}

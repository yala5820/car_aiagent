package com.hirain.aiagent.rag.retrieval;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import com.hirain.aiagent.rag.config.RagRetrievalConfig;
import com.hirain.aiagent.rag.model.VehicleProfile;
import com.hirain.aiagent.rag.store.MyObjectBox;
import com.hirain.aiagent.rag.store.ObjectBoxKnowledgeStoreGateway;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.List;
import org.junit.Test;

/** 使用离线唯一 Fixture 验证 Dense/BM25 都在 RRF 前执行严格 Metadata 过滤。 */
public class ObjectBoxLocalSearchInstrumentedTest {
    @Test public void searchesDenseAndLexicalWithMatchingProfileOnly() throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File directory = new File(target.getCacheDir(), "rag-local-search-" + System.nanoTime()); assertTrue(directory.mkdirs());
        copy(InstrumentationRegistry.getInstrumentation().getContext(), "rag/knowledge_db/data.mdb", new File(directory, "data.mdb"));
        try (ObjectBoxKnowledgeStoreGateway gateway = new ObjectBoxKnowledgeStoreGateway(MyObjectBox.builder().androidContext(target).directory(directory).build())) {
            VehicleProfile matching = new VehicleProfile("DEMO_MODEL", "2026", "CN", "DEMO_VERSION", "DEFAULT", 0);
            List<DenseCandidate> dense = new ObjectBoxDenseSearcher(new MetadataEligibilityPolicy()).search(gateway, vector(), matching, 3);
            assertFalse("dense search returned no candidates", dense.isEmpty()); assertTrue(dense.get(0).distance() >= 0d);
            List<RankedCandidate> lexical = new ObjectBoxLexicalSearcher(new MetadataEligibilityPolicy(), RagRetrievalConfig.v1()).search(gateway, new CjkLatinLexicalAnalyzer().analyze("制动液"), matching, 3);
            assertFalse("lexical search returned no candidates", lexical.isEmpty());
            VehicleProfile mismatching = new VehicleProfile("DEMO_MODEL", "2026", "US", "DEMO_VERSION", "DEFAULT", 0);
            assertTrue(new ObjectBoxDenseSearcher(new MetadataEligibilityPolicy()).search(gateway, vector(), mismatching, 3).isEmpty());
            assertTrue(new ObjectBoxLexicalSearcher(new MetadataEligibilityPolicy(), RagRetrievalConfig.v1()).search(gateway, new CjkLatinLexicalAnalyzer().analyze("制动液"), mismatching, 3).isEmpty());
        }
    }
    private static float[] vector(){float[] value=new float[1024];for(int index=0;index<value.length;index++)value[index]=0.01f+index/100_000f;return value;}
    private static void copy(Context context,String source,File destination)throws Exception{try(InputStream input=context.getAssets().open(source);FileOutputStream output=new FileOutputStream(destination)){byte[] buffer=new byte[8192];for(int count;(count=input.read(buffer))!=-1;)output.write(buffer,0,count);output.getFD().sync();}}
}

package com.hirain.aiagent.rag.store;
import org.junit.Test;
import java.nio.file.Files;
import static org.junit.Assert.*;
/** 文件摘要不匹配必须在 Store 打开前失败。 */
public class FileDigestVerifierTest {
 @Test public void rejectsChangedFile() throws Exception { var file=Files.createTempFile("rag-db",".mdb"); Files.write(file,new byte[]{1,2,3}); var expected=new KnowledgeBundleManifest.DataFile("data.mdb",3,"039058c6f2c0cb492c533b0a4d14ef77cc0f0787f9b6a22f1f3f6a2c1d5a6a8c"); assertFalse(new FileDigestVerifier().verify(file,expected).valid()); }
}

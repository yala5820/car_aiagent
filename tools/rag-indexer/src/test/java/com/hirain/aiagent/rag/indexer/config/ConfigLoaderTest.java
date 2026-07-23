package com.hirain.aiagent.rag.indexer.config;

import com.hirain.aiagent.rag.indexer.cli.CliCommandException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/** 验证最重要的配置安全不变量和确定性指纹。 */
final class ConfigLoaderTest {
 @TempDir Path dir;
 @Test void shouldFingerprintIndependentOfRootFieldOrder() throws Exception {
  assertEquals(load(valid()).fingerprint(),load(reordered()).fingerprint());
 }
 @Test void shouldRejectNetworkEnabledHtml() throws Exception {
  CliCommandException e=assertThrows(CliCommandException.class,()->load(valid().replace("\"networkAccess\":false","\"networkAccess\":true")));
  assertEquals("CONFIG_HTML_MODE_INVALID",e.reasonCode());
 }
 @Test void shouldRejectUnknownNestedParserField() throws Exception {
  CliCommandException e=assertThrows(CliCommandException.class,()->load(valid().replace("\"mode\":\"STATIC_DOM_ONLY\"","\"mode\":\"STATIC_DOM_ONLY\",\"typo\":true")));
  assertEquals("CONFIG_JSON_UNKNOWN_FIELD",e.reasonCode());
 }
 @Test void shouldApplyDocumentAndPageStrategyOverride() throws Exception {
  RagBuildConfig config=load(valid().replace("\"tableStrategyOverrides\":[]","\"tableStrategyOverrides\":[{\"documentId\":\"manual\",\"pageStart\":2,\"pageEnd\":3,\"strategy\":\"LATTICE\"}]"));
  assertEquals("AUTO",config.pdfParserConfig().tableStrategyFor("manual",1));
  assertEquals("LATTICE",config.pdfParserConfig().tableStrategyFor("manual",2));
 }
 @Test void shouldLoadStrictV2ChunkingConfig() throws Exception {
  String v2="{\"configVersion\":2,\"tokenEstimatorVersion\":\"rag-token-estimator-v2\",\"parentIdealMinTokens\":150,\"parentSoftMaxTokens\":1200,\"parentHardMaxTokens\":2000,\"childIdealMinTokens\":160,\"childTargetTokens\":256,\"childSoftMaxTokens\":384,\"childHardMaxTokens\":512,\"fallbackOverlapMinRatio\":0.05,\"fallbackOverlapMaxRatio\":0.1,\"tableRowsPerChild\":20,\"semanticStrategyVersion\":\"structural-semantic-v2\"}";
  ChunkingConfig chunking=load(valid().replace("\"chunking\":{}","\"chunking\":"+v2)).chunkingConfig();
  assertEquals(2,chunking.configVersion()); assertEquals(512,chunking.childHardMaxTokens());
 }
 private RagBuildConfig load(String json)throws Exception {Path f=dir.resolve("c.json");Files.writeString(f,json);return new ConfigLoader().load(f);}
 private static String valid(){return "{\"schemaVersion\":1,\"parser\":{\"pdf\":{\"tableStrategy\":\"AUTO\",\"tableStrategyOverrides\":[]},\"html\":{\"mode\":\"STATIC_DOM_ONLY\",\"networkAccess\":false,\"scriptExecution\":false,\"contentRootSelector\":\"\",\"excludeSelectors\":[]},\"markdown\":{\"syntax\":\"COMMONMARK\",\"extensions\":[\"GFM_TABLE\"]},\"limits\":{}},\"chunking\":{},\"embedding\":{\"provider\":\"DashScope\",\"model\":\"text-embedding-v4\",\"dimension\":1024,\"templateVersion\":1},\"objectBox\":{},\"lexical\":{\"algorithm\":\"BM25\",\"analyzerVersion\":1},\"qualityGate\":{\"state\":\"TEST_ONLY\"}}";}
 private static String reordered(){return "{\"qualityGate\":{\"state\":\"TEST_ONLY\"},\"lexical\":{\"analyzerVersion\":1,\"algorithm\":\"BM25\"},\"objectBox\":{},\"embedding\":{\"templateVersion\":1,\"dimension\":1024,\"model\":\"text-embedding-v4\",\"provider\":\"DashScope\"},\"chunking\":{},\"parser\":{\"markdown\":{\"extensions\":[\"GFM_TABLE\"],\"syntax\":\"COMMONMARK\"},\"html\":{\"scriptExecution\":false,\"networkAccess\":false,\"mode\":\"STATIC_DOM_ONLY\",\"contentRootSelector\":\"\",\"excludeSelectors\":[]},\"pdf\":{\"tableStrategyOverrides\":[],\"tableStrategy\":\"AUTO\"},\"limits\":{}},\"schemaVersion\":1}";}
}

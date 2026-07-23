package com.hirain.aiagent.rag.indexer.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hirain.aiagent.rag.indexer.cli.CliCommandException;
import com.hirain.aiagent.rag.indexer.cli.CliExitCode;
import com.hirain.aiagent.rag.indexer.util.DeterministicJson;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Set;

/** G102 构建配置读取器：只接受 V1 已定义域并锁定跨端不变量。 */
public final class ConfigLoader {
 private static final ObjectMapper MAPPER=new ObjectMapper();
 private static final Set<String> ROOT=Set.of("schemaVersion","parser","chunking","embedding","objectBox","lexical","qualityGate");
 public RagBuildConfig load(Path path) {
  try { JsonNode root=MAPPER.readTree(path.toFile()); object(root); fields(root,ROOT); unknown(root,ROOT);
   if(root.path("schemaVersion").asInt(-1)!=1) fail("CONFIG_VERSION_INVALID");
   JsonNode parser=root.get("parser"), chunking=root.get("chunking"), embedding=root.get("embedding"), lexical=root.get("lexical"), quality=root.get("qualityGate");
   object(parser); object(chunking); object(embedding); object(lexical); object(quality); fields(parser,Set.of("pdf","html","markdown","limits")); object(parser.get("pdf"));
   unknown(parser,Set.of("pdf","html","markdown","limits"));
   object(parser.get("html")); object(parser.get("markdown"));
   unknown(parser.get("html"),Set.of("mode","networkAccess","scriptExecution","contentRootSelector","excludeSelectors","embeddedDataExtraction"));
   unknown(parser.get("markdown"),Set.of("syntax","extensions"));
   unknown(parser.get("pdf"),Set.of("tableStrategy","tableStrategyOverrides"));
   unknown(chunking,Set.of("maxChildTokens","overlapTokens","tableRowsPerChild"));
   unknown(embedding,Set.of("provider","model","dimension","templateVersion","batchSize","maxRetries"));
   unknown(lexical,Set.of("algorithm","analyzerVersion","languages","tokenization","k1","b"));
   unknown(quality,Set.of("state","thresholds"));
   if(!"STATIC_DOM_ONLY".equals(parser.path("html").path("mode").asText()) || parser.path("html").path("networkAccess").asBoolean(true) || parser.path("html").path("scriptExecution").asBoolean(true)) fail("CONFIG_HTML_MODE_INVALID");
   if(!"COMMONMARK".equals(parser.path("markdown").path("syntax").asText()) || !parser.path("markdown").path("extensions").toString().contains("GFM_TABLE")) fail("CONFIG_MARKDOWN_MODE_INVALID");
   if(!"DashScope".equals(embedding.path("provider").asText())||!"text-embedding-v4".equals(embedding.path("model").asText())||embedding.path("dimension").asInt()!=1024||embedding.path("templateVersion").asInt()!=1) fail("CONFIG_EMBEDDING_INVALID");
   if(!"BM25".equals(lexical.path("algorithm").asText())||lexical.path("analyzerVersion").asInt()!=1) fail("CONFIG_LEXICAL_INVALID");
   String state=quality.path("state").asText(); if(!Set.of("TEST_ONLY","APPROVED").contains(state)) fail("CONFIG_QUALITY_GATE_INVALID");
   PdfParserConfig pdfConfig=pdfConfig(parser.get("pdf")); HtmlParserConfig htmlConfig=htmlConfig(parser.get("html")); MarkdownParserConfig markdownConfig=markdownConfig(parser.get("markdown")); ChunkingConfig chunkingConfig=chunkingConfig(chunking);
   JsonNode canonical=DeterministicJson.sort(root); RagBuildConfig config=new RagBuildConfig(canonical,ConfigFingerprint.of(canonical),pdfConfig,htmlConfig,markdownConfig,chunkingConfig); new ConfigValidator().validate(config); return config;
  } catch(IOException e){ fail("CONFIG_JSON_INVALID"); throw new AssertionError(e); }
 }
 private static void object(JsonNode n){if(n==null||!n.isObject())fail("CONFIG_JSON_INVALID");}
 private static void fields(JsonNode n,Set<String>s){for(String f:s)if(!n.has(f))fail("CONFIG_JSON_REQUIRED");}
 private static void unknown(JsonNode n,Set<String>s){Iterator<String> i=n.fieldNames();while(i.hasNext())if(!s.contains(i.next()))fail("CONFIG_JSON_UNKNOWN_FIELD");}
 private static void fail(String r){throw new CliCommandException(CliExitCode.ARGUMENT_OR_CONFIG_ERROR,r);}
 private static PdfParserConfig pdfConfig(JsonNode node){
  String strategy=node.path("tableStrategy").asText();
  if(!Set.of("AUTO","LATTICE","STREAM").contains(strategy))fail("CONFIG_PDF_TABLE_STRATEGY_INVALID");
  JsonNode overrides=node.path("tableStrategyOverrides"); if(!overrides.isArray())fail("CONFIG_PDF_TABLE_STRATEGY_INVALID");
  java.util.List<PdfTableStrategyOverride> values=new java.util.ArrayList<>();
  for(JsonNode override:overrides){object(override);unknown(override,Set.of("documentId","pageStart","pageEnd","strategy"));
   if(!override.has("documentId")||!override.has("pageStart")||!override.has("pageEnd")||!override.has("strategy"))fail("CONFIG_PDF_TABLE_STRATEGY_INVALID");
   try{values.add(new PdfTableStrategyOverride(override.path("documentId").asText(),override.path("pageStart").asInt(0),override.path("pageEnd").asInt(0),override.path("strategy").asText()));}catch(IllegalArgumentException e){fail("CONFIG_PDF_TABLE_STRATEGY_INVALID");}
  }
  return new PdfParserConfig(strategy,values);
 }
 private static HtmlParserConfig htmlConfig(JsonNode node){
  JsonNode excludes=node.path("excludeSelectors"); if(!excludes.isArray())fail("CONFIG_HTML_MODE_INVALID");
  java.util.List<String> selectors=new java.util.ArrayList<>(); for(JsonNode value:excludes){if(!value.isTextual()||value.asText().length()>100||!value.asText().matches("[A-Za-z0-9_.#-]+"))fail("CONFIG_HTML_MODE_INVALID");selectors.add(value.asText());}
  String root=node.path("contentRootSelector").asText(""); if(root.length()>100||(!root.isEmpty()&&!root.matches("[A-Za-z0-9_.#-]+")))fail("CONFIG_HTML_MODE_INVALID");
  String extraction=node.path("embeddedDataExtraction").asText("NONE"); if(!Set.of("NONE","TESLA_SERVICE_CENTERS_V1").contains(extraction))fail("CONFIG_HTML_MODE_INVALID");
  return new HtmlParserConfig(node.path("mode").asText(),node.path("networkAccess").asBoolean(),node.path("scriptExecution").asBoolean(),root,selectors,extraction);
 }
 private static MarkdownParserConfig markdownConfig(JsonNode node){
  java.util.List<String> extensions=new java.util.ArrayList<>(); for(JsonNode value:node.path("extensions")){if(!value.isTextual())fail("CONFIG_MARKDOWN_MODE_INVALID");extensions.add(value.asText());}
  return new MarkdownParserConfig(node.path("syntax").asText(),extensions);
 }
 private static ChunkingConfig chunkingConfig(JsonNode node){
  ChunkingConfig defaults=ChunkingConfig.v1Default();
  try{return new ChunkingConfig(node.path("maxChildTokens").asInt(defaults.maxChildTokens()),node.path("overlapTokens").asInt(defaults.overlapTokens()),node.path("tableRowsPerChild").asInt(defaults.tableRowsPerChild()));}catch(IllegalArgumentException e){fail("CONFIG_CHUNKING_INVALID");throw new AssertionError(e);}
 }
}

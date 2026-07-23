package com.hirain.aiagent.rag.store;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 仅接受共享 Manifest V1；未知字段一律拒绝，避免 Android 单侧宽松兼容造成错误激活。 */
public final class KnowledgeBundleManifestParser {
    private static final Set<String> ROOT = Set.of("formatVersion","bundleId","bundleVersion","knowledgeScopeId","builderVersion","builtAtEpochMs","objectBoxVersion","schemaFingerprint","sourceLocatorSchemaVersion","dataFile","embedding","hnsw","scope","parsers","lexical","chunking","corpus");
    public KnowledgeBundleManifest parse(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject(); requireFields(root, ROOT); rejectUnknown(root, ROOT);
        int format = integer(root,"formatVersion"); int locator = integer(root,"sourceLocatorSchemaVersion");
        if (format != 1 || locator != 1) fail("MANIFEST_VERSION_UNSUPPORTED");
        JsonObject data = object(root,"dataFile", Set.of("name","sizeBytes","sha256"));
        JsonObject embedding = object(root,"embedding", Set.of("provider","model","dimension","distanceType","templateVersion"));
        JsonObject hnsw = object(root,"hnsw", Set.of("configFingerprint","neighborsPerNode","indexingSearchCount","reparationBacklinkProbability","vectorCacheHintSizeKB","flags")); JsonObject scope = object(root,"scope", Set.of("vehicleModel","modelYear","region","softwareVersion","configurationCode"));
        JsonObject parsers = object(root,"parsers", Set.of("configHash","supportedSourceFormats","pdf","html","markdown")); JsonObject lexical = object(root,"lexical", Set.of("analyzerVersion","languages","algorithm","tokenization")); JsonObject chunking = object(root,"chunking", Set.of("configVersion","configHash")); JsonObject corpus = object(root,"corpus", Set.of("corpusHash","documentCount","sourceFormatCounts","parentChunkCount","childChunkCount","lexicalTermCount"));
        object(parsers,"pdf", Set.of("tableStrategy","tableStrategyOverrides")); JsonObject html = optionalObject(parsers,"html", Set.of("contentRootSelector","excludeSelectors","mode","networkAccess","scriptExecution"), Set.of("embeddedDataExtraction")); object(parsers,"markdown", Set.of("extensions","syntax"));
        if (html.has("embeddedDataExtraction") && !Set.of("NONE", "TESLA_SERVICE_CENTERS_V1").contains(text(html, "embeddedDataExtraction"))) fail("MANIFEST_HTML_EXTRACTION_UNSUPPORTED");
        String dataHash=text(data,"sha256"); String schema=text(root,"schemaFingerprint"); String hnswHash=text(hnsw,"configFingerprint");
        if (!"data.mdb".equals(text(data,"name")) || number(data,"sizeBytes") < 1 || !dataHash.matches("[0-9a-f]{64}") || !schema.matches("sha256:[0-9a-f]{64}") || !hnswHash.matches("sha256:[0-9a-f]{64}")) fail("MANIFEST_PROTOCOL_INVALID");
        if (!"DashScope".equals(text(embedding,"provider")) || !"text-embedding-v4".equals(text(embedding,"model")) || integer(embedding,"dimension") != 1024 || !"COSINE".equals(text(embedding,"distanceType")) || integer(embedding,"templateVersion") != 1) fail("MANIFEST_EMBEDDING_INCOMPATIBLE");
        if (integer(hnsw,"neighborsPerNode") != KnowledgeStoreContract.HNSW_NEIGHBORS_PER_NODE || integer(hnsw,"indexingSearchCount") != KnowledgeStoreContract.HNSW_INDEXING_SEARCH_COUNT || Float.compare(floating(hnsw,"reparationBacklinkProbability"), KnowledgeStoreContract.HNSW_REPARATION_BACKLINK_PROBABILITY) != 0 || integer(hnsw,"vectorCacheHintSizeKB") != KnowledgeStoreContract.HNSW_VECTOR_CACHE_HINT_SIZE_KB || !arrayText(hnsw,"flags").equals(KnowledgeStoreContract.HNSW_FLAGS)) fail("MANIFEST_HNSW_INCOMPATIBLE");
        List<String> formats=arrayText(parsers,"supportedSourceFormats"); if (!formats.equals(List.of("PDF","STATIC_HTML","MARKDOWN"))) fail("MANIFEST_FORMAT_UNSUPPORTED");
        if (integer(lexical,"analyzerVersion") != 1 || !"BM25".equals(text(lexical,"algorithm")) || integer(chunking,"configVersion") != 1 || !text(parsers,"configHash").matches("sha256:[0-9a-f]{64}") || !text(chunking,"configHash").matches("sha256:[0-9a-f]{64}") || !text(corpus,"corpusHash").matches("sha256:[0-9a-f]{64}")) fail("MANIFEST_PROTOCOL_INVALID");
        Map<String,String> scopeValues=mapText(scope); Map<String,Long> counts=mapLong(object(corpus,"sourceFormatCounts",null));
        return new KnowledgeBundleManifest(format,text(root,"bundleId"),text(root,"bundleVersion"),text(root,"knowledgeScopeId"),text(root,"builderVersion"),number(root,"builtAtEpochMs"),text(root,"objectBoxVersion"),schema,locator,new KnowledgeBundleManifest.DataFile("data.mdb",number(data,"sizeBytes"),dataHash),new KnowledgeBundleManifest.Embedding("DashScope","text-embedding-v4",1024,"COSINE",1),new KnowledgeBundleManifest.Hnsw(hnswHash,integer(hnsw,"neighborsPerNode"),integer(hnsw,"indexingSearchCount"),floating(hnsw,"reparationBacklinkProbability"),integer(hnsw,"vectorCacheHintSizeKB"),arrayText(hnsw,"flags")),scopeValues,text(parsers,"configHash"),formats,integer(lexical,"analyzerVersion"),text(chunking,"configHash"),new KnowledgeBundleManifest.Corpus(text(corpus,"corpusHash"),number(corpus,"documentCount"),counts,number(corpus,"parentChunkCount"),number(corpus,"childChunkCount"),number(corpus,"lexicalTermCount")));
    }
    private static JsonObject object(JsonObject root,String name,Set<String> allowed){JsonElement value=root.get(name);if(value==null||!value.isJsonObject())fail("MANIFEST_INVALID");JsonObject object=value.getAsJsonObject();if(allowed!=null){requireFields(object,allowed);rejectUnknown(object,allowed);}return object;}
    /**
     * 新增 HTML 静态数据提取策略时保留旧 Bundle 的兼容性：字段缺失等价于 NONE，
     * 但一旦声明就必须处于白名单，不能让 Android 对未知解析语义静默放行。
     */
    private static JsonObject optionalObject(JsonObject root,String name,Set<String> required,Set<String> optional){JsonObject object=object(root,name,null);requireFields(object,required);Set<String> allowed=new HashSet<>(required);allowed.addAll(optional);rejectUnknown(object,allowed);return object;}
    private static String text(JsonObject root,String name){JsonElement value=root.get(name);if(value==null||!value.isJsonPrimitive()||value.getAsString().isBlank())fail("MANIFEST_INVALID");return value.getAsString();}
    private static int integer(JsonObject root,String name){long value=number(root,name);if(value>Integer.MAX_VALUE)fail("MANIFEST_INVALID");return (int)value;}
    private static float floating(JsonObject root,String name){JsonElement value=root.get(name);if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber())fail("MANIFEST_INVALID");float result=value.getAsFloat();if(!Float.isFinite(result))fail("MANIFEST_INVALID");return result;}
    private static long number(JsonObject root,String name){JsonElement value=root.get(name);if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber()||value.getAsLong()<0)fail("MANIFEST_INVALID");return value.getAsLong();}
    private static List<String> arrayText(JsonObject root,String name){JsonElement value=root.get(name);if(value==null||!value.isJsonArray())fail("MANIFEST_INVALID");java.util.ArrayList<String> output=new java.util.ArrayList<>();for(JsonElement item:value.getAsJsonArray()){if(!item.isJsonPrimitive())fail("MANIFEST_INVALID");output.add(item.getAsString());}return List.copyOf(output);}
    private static Map<String,String> mapText(JsonObject root){Map<String,String> result=new LinkedHashMap<>();for(Map.Entry<String,JsonElement> entry:root.entrySet())result.put(entry.getKey(),text(root,entry.getKey()));return result;}
    private static Map<String,Long> mapLong(JsonObject root){Map<String,Long> result=new LinkedHashMap<>();for(Map.Entry<String,JsonElement> entry:root.entrySet())result.put(entry.getKey(),number(root,entry.getKey()));return result;}
    private static void requireFields(JsonObject root,Set<String> fields){for(String field:fields)if(!root.has(field))fail("MANIFEST_REQUIRED_FIELD_MISSING");}
    private static void rejectUnknown(JsonObject root,Set<String> fields){for(String field:root.keySet())if(!fields.contains(field))fail("MANIFEST_UNKNOWN_FIELD");}
    private static void fail(String reason){throw new IllegalArgumentException(reason);}
}

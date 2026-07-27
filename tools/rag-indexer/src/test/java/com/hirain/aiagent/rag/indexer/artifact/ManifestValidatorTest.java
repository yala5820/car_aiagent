package com.hirain.aiagent.rag.indexer.artifact;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import com.hirain.aiagent.rag.store.KnowledgeStoreContract;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class ManifestValidatorTest {
    @Test
    void shouldAcceptSharedV2ContractAndRejectWrongEmbedding() {
        assertDoesNotThrow(() -> new ManifestValidator().validate(manifest("DashScope")));
        assertThrows(IllegalArgumentException.class, () -> new ManifestValidator().validate(manifest("other")));
    }
    static KnowledgeBundleManifest manifest(String provider) {
        String hash = "sha256:" + "a".repeat(64);
        return new KnowledgeBundleManifest(2,"bundle","1","scope","builder",0,"5.4.0",hash,1,
                new BundleFileHasher.FileHash(1,"a".repeat(64)), Map.of("provider",provider,"model","text-embedding-v4","dimension",1024,"distanceType","COSINE","templateVersion",2),
                hnsw(hash), Map.of("vehicleModel","V1","modelYear","2026","region","CN","softwareVersion","1","configurationCode","base"),
                Map.of("configHash",hash,"supportedSourceFormats",List.of("PDF","STATIC_HTML","MARKDOWN"),"pdf",Map.of(),"html",Map.of(),"markdown",Map.of()),
                Map.of("analyzerVersion",2,"languages",List.of("zh","en"),"algorithm","BM25","tokenization","CJK_BIGRAM_TRIGRAM_WITH_EXACT_LATIN"), Map.of("configVersion",1,"configHash",hash), Map.of("corpusHash",hash));
    }
    private static Map<String, Object> hnsw(String fingerprint) {
        var result = new java.util.LinkedHashMap<>(KnowledgeStoreContract.hnswManifestDetails());
        result.put("configFingerprint", fingerprint); return Map.copyOf(result);
    }
}

package com.hirain.aiagent.rag.indexer.corpus;

import java.util.List;

/** 通过 Loader 后的 Corpus 不可变快照。 */
public record CorpusDefinition(int schemaVersion, BundleDefinition bundle, List<CorpusDocumentDefinition> documents) {
    public CorpusDefinition { documents = List.copyOf(documents); }
}

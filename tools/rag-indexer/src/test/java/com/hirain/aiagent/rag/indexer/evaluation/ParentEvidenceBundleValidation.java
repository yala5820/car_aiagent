package com.hirain.aiagent.rag.indexer.evaluation;

import com.hirain.aiagent.rag.indexer.store.ObjectBoxStoreFactory;
import com.hirain.aiagent.rag.store.entity.KnowledgeChunkEntity;
import io.objectbox.BoxStore;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/** 只读校验 Eval V2 Ground Truth 中的 Parent/Child ID 是否存在于指定 Bundle。 */
public final class ParentEvidenceBundleValidation {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("bundle and dataset required");
        RetrievalEvaluationDatasetV2 dataset = new RetrievalEvaluationLoaderV2().load(Path.of(args[1]));
        try (BoxStore store = new ObjectBoxStoreFactory().openReadOnlyExisting(Path.of(args[0]))) {
            Set<String> parents = new HashSet<>(); Set<String> children = new HashSet<>();
            for (KnowledgeChunkEntity entity : store.boxFor(KnowledgeChunkEntity.class).getAll()) {
                if ("PARENT".equals(entity.chunkLevel)) parents.add(entity.chunkId);
                if ("CHILD".equals(entity.chunkLevel)) children.add(entity.chunkId);
            }
            for (var value : dataset.cases()) for (var set : value.acceptableEvidenceSets()) {
                if (!parents.containsAll(set.requiredParentIds())) throw new IllegalArgumentException("EVALUATION_V2_PARENT_UNKNOWN");
                if (!children.containsAll(set.optionalLocatorChildIds())) throw new IllegalArgumentException("EVALUATION_V2_CHILD_UNKNOWN");
            }
            System.out.println("EVALUATION_V2_GROUND_TRUTH_VALID parents=" + parents.size() + " cases=" + dataset.cases().size());
        }
    }
}

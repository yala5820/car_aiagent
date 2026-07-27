package com.hirain.aiagent.rag.indexer.evaluation;

import com.hirain.aiagent.rag.indexer.store.ObjectBoxStoreFactory;
import com.hirain.aiagent.rag.store.entity.KnowledgeChunkEntity;
import io.objectbox.BoxStore;
import java.nio.file.Path;
import java.util.*;

/** 仅用于本地人工建立 Eval V2 Ground Truth 的只读 Parent 目录查询工具。 */
public final class ParentCatalogLookup {
    public static void main(String[] args) {
        if (args.length < 2) throw new IllegalArgumentException("bundle and query required");
        Path bundle = Path.of(args[0]);
        String query = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        try (BoxStore store = new ObjectBoxStoreFactory().openReadOnlyExisting(bundle)) {
            List<KnowledgeChunkEntity> parents = store.boxFor(KnowledgeChunkEntity.class).getAll().stream()
                    .filter(value -> "PARENT".equals(value.chunkLevel)).toList();
            Set<String> terms = terms(query);
            parents.stream().map(value -> new Scored(value, score(value, terms)))
                    .filter(value -> value.score > 0)
                    .sorted(Comparator.comparingInt(Scored::score).reversed().thenComparing(value -> value.entity.chunkId))
                    .limit(12)
                    .forEach(value -> System.out.println(value.entity.chunkId + "\t" + value.score + "\t" + value.entity.headingPath + "\t" + preview(value.entity.content)));
        }
    }

    private static Set<String> terms(String query) {
        Set<String> values = new LinkedHashSet<>();
        String text = query == null ? "" : query;
        if (text.length() >= 2) for (int index = 0; index + 1 < text.length(); index++) values.add(text.substring(index, index + 2));
        return values;
    }

    private static int score(KnowledgeChunkEntity value, Set<String> terms) {
        String text = ((value.headingPath == null ? "" : value.headingPath) + " " + (value.content == null ? "" : value.content));
        int score = 0; for (String term : terms) if (text.contains(term)) score++;
        return score;
    }

    private static String preview(String value) {
        String text = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return text.length() <= 180 ? text : text.substring(0, 180);
    }

    private record Scored(KnowledgeChunkEntity entity, int score) { }
}

package com.hirain.aiagent.rag.store;

import java.nio.file.Path;

/** RAG 文件布局的唯一入口，版本键必须先校验，避免 Manifest 内容影响宿主路径。 */
public final class KnowledgeStorageLayout {
    private final Path root;
    public KnowledgeStorageLayout(Path filesDirectory) { this.root = filesDirectory.resolve("rag").normalize(); }
    public Path root() { return root; }
    public Path stores() { return root.resolve("stores"); }
    public Path staging() { return root.resolve("staging"); }
    public Path activePointer() { return root.resolve("active.json"); }
    public Path activePointerTemporary() { return root.resolve("active.json.tmp"); }
    public Path versionDirectory(String bundleVersion) { return stores().resolve(safeKey(bundleVersion)); }
    public Path stagingDirectory(String installId) { return staging().resolve(safeKey(installId)); }
    public static String safeKey(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}") || value.equals(".") || value.equals("..") || value.contains("..")) throw new IllegalArgumentException("BUNDLE_PATH_INVALID");
        return value;
    }
}

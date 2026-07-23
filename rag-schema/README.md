# RAG 共享 Schema 规范源

`rag-schema/` 是离线索引器与 Android Runtime 共同消费的唯一协议源，包含 ObjectBox Entity、不可删除的 Meta Model、Manifest JSON Schema 和跨端 Golden。禁止在 `tools/rag-indexer` 或 `app` 维护第二份 Entity、UID 或手工复制的 `default.json`。

## 目录与所有权

- `src/main/java/`：四个 ObjectBox Entity 与跨端常量/指纹算法。
- `objectbox-models/default.json`：ObjectBox 5.4.0 生成的 Entity/Property/Index UID；必须随源码提交，删除后重新生成会破坏已构建 Bundle 的兼容性。
- `objectbox-models/default.sha256`：当前 `default.json` 的原始字节 SHA-256；Android 构建用它拒绝未经 `rag-schema` 负责人审阅的 Meta Model 漂移。
- `contracts/`：交付给 Android 的 Manifest JSON Schema。
- `test-vectors/`：Embedding 输入、Scope、SourceLocator 和 Fingerprint 的共同 Golden。

离线 CLI 在 `RAG-G003` 通过外部 `sourceSets` 直接编译本目录；ObjectBox Processor 的 `objectbox.modelPath` 指向本目录。Android 端将在其 Phase 0 使用相同源码和 Meta Model，而不是复制文件。

## 固定协议

- ObjectBox：5.4.0；四个 Entity：`KnowledgeStoreMetadataEntity`、`KnowledgeDocumentEntity`、`KnowledgeChunkEntity`、`LexicalTermEntity`。
- Embedding：DashScope `text-embedding-v4`、1024 维、`COSINE`。Parent `embedding` 允许为 `null`；Child 的向量有效性由离线写入前校验。
- Locator 数值空值：HTML/Markdown 不适用的 PDF 页码、PDF 不适用的行号均以 `0` 入库；映射回领域/引用时必须恢复为“不适用”，不得展示为第 0 页或第 0 行。
- 当前 Meta Model 规范化 SHA-256：`sha256:453278ef0c1d17c8f799f2af9dc4142a802ae87bd8db381e0392bf5eb1606b4c`。

## Schema Fingerprint 算法

输入文件集合 V1 仅包含 `objectbox-models/default.json`。以 UTF-8 读取，移除可选 BOM，将 CRLF/CR 统一为 LF，保留其余字符后计算 SHA-256 小写十六进制，并加 `sha256:` 前缀。实现位于 `SchemaFingerprint`；两端不得使用平台默认编码或不同的 JSON 重排策略。

Schema、Entity、Property、UID 或 HNSW 配置发生变化时，必须回退到调度文档的 `RAG-G003`，重新生成 Fixture/Bundle 并执行 Android 跨端兼容 Gate；不得手工编辑 `data.mdb` 修补。

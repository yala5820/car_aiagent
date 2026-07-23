# 车辆知识 RAG 离线索引器

本目录是与 Android 主工程并列的独立 Java 17 桌面 JVM CLI Build。它负责在离线环境解析获准的 PDF、严格静态 HTML 与 Markdown，生成供 Android 只读消费的知识 Bundle；它不进入 Android Agent 请求链。

## 构建入口

必须从本目录运行 Wrapper，不能在仓库根目录把它当作 Android 子模块执行：

```powershell
cd tools\rag-indexer
.\gradlew.bat test
.\gradlew.bat installDist
.\build\install\rag-indexer\bin\rag-indexer.bat --help
.\build\install\rag-indexer\bin\rag-indexer.bat --version
```

CLI 要求 Java 17。根目录 `gradlew.bat` 仅用于 Android 工程，根 `settings.gradle.kts` 只包含 `:app`，不会包含本目录。

## 当前边界与命令

本工具已经支持获准的 PDF、严格静态 HTML 与 Markdown，生成 Android 只读消费的 ObjectBox Bundle。它始终运行在独立桌面 JVM，不进入 Android Agent 请求链；真实语料、工作目录、向量缓存和试运行输出均不得提交 Git。

```powershell
# 仅校验输入与 Parser，并产生供人工复核的无正文报告。
.\build\install\rag-indexer\bin\rag-indexer.bat validate `
  --corpus .\corpus\corpus.json --config .\rag-build.json --work-dir .\work

# 解析、Chunk、Embedding、Lexical、ObjectBox、Manifest 与 Verify；不覆盖已有输出。
.\build\install\rag-indexer\bin\rag-indexer.bat build `
  --corpus .\corpus\corpus.json --config .\rag-build.json `
  --output .\output\candidate --work-dir .\work

# 独立只读校验现有 Bundle。
.\build\install\rag-indexer\bin\rag-indexer.bat verify `
  --bundle .\output\candidate --config .\rag-build.json

# 只读计算 Dense Recall@K/MRR；不会修改 Store。
.\build\install\rag-indexer\bin\rag-indexer.bat evaluate `
  --bundle .\output\candidate --dataset .\evaluation.json --report .\work\evaluation.json
```

`validate` 会在 `work/runs/<runId>/` 生成三类审核材料：`parser-review.json` 只含文档 ID、相对路径、格式、PDF 页号/HTML 段序号、诊断码和 WARNING 块定位，不包含正文；`parser-review.html` 是仅限本机查看的折叠式预览，展示 Parser 实际保留的正文以及 PDF 被排除的重复页眉/页脚候选原文和页码；`chunk-review.html` 展示最终稳定 Child Chunk ID、来源定位、证据类型与对应 Child 正文，供人工建立或核对检索评测集。HTML 预览可包含获授权原文，必须保留在 work 目录，不得提交 Git、写入 Bundle 或发送到云端。该命令不调用 Embedding、不访问网络、不写 ObjectBox Bundle。

`TEST_ONLY` 配置只能产生不可发布开发候选。只有真实资料授权、Metadata 审核、跨格式评测、质量阈值与发布负责人确认全部完成后，才可使用 `APPROVED` 配置构建正式候选。已有输出目录一律禁止覆盖；CLI 返回 `BUILD_OUTPUT_ALREADY_EXISTS`，调用方应选择新的候选目录，而不是删除旧产物。

`chunking.maxChildTokens`、`chunking.overlapTokens`、`chunking.tableRowsPerChild` 是版本化的构建输入，并进入配置指纹；当前 V1 基线为 `256 / 32 / 20`。修改任一值必须使用新的 Bundle 输出目录、全量重建并重跑评测，不能复用旧 Bundle 或通过 CLI 参数临时覆盖。

HNSW 也是跨端锁定协议：当前 V1 显式使用 `dimensions=1024`、`COSINE`、`neighborsPerNode=30`、`indexingSearchCount=100`、`reparationBacklinkProbability=1.0`、`vectorCacheHintSizeKB=0` 和空 flags。全部参数及 ObjectBox 版本进入 HNSW 指纹与 Manifest；Android 发现候选参数与当前共享 Entity 不一致即拒绝激活。参数范围由共享 Manifest Schema 校验，选择新 Profile 必须重建 Bundle、Fixture 和跨端验证证据，不能用 CLI 参数临时覆盖。

# RAG 离线索引器运行时依赖解析记录

> 执行日期：2026-07-21  
> 执行命令：`gradlew.bat clean test dependencies --configuration runtimeClasspath --console=plain`  
> Java Toolchain：Microsoft JDK 17.0.17  
> 仓库：Maven Central 与 Gradle Plugin Portal（ObjectBox Plugin）

## 直接运行时依赖

| 坐标 | 锁定版本 | 目的 | 许可证结论 |
|---|---:|---|---|
| `io.objectbox:objectbox-java` / `objectbox-windows` | 5.4.0 | 本地 Store 与跨端 Spike | Java binding Apache-2.0；native runtime ObjectBox Binary Licence；项目负责人已批准本项目约定发布范围。 |
| `org.apache.pdfbox:pdfbox` | 2.0.37 | 文本型 PDF 结构基础 | Apache-2.0。 |
| `technology.tabula:tabula` | 1.0.5 | 文本型 PDF 表格 | MIT。其请求的 PDFBox 2.0.24 被 Gradle 统一到 2.0.37。 |
| `org.jsoup:jsoup` | 1.22.2 | 严格静态 HTML DOM | MIT。 |
| `org.commonmark:commonmark`、`commonmark-ext-gfm-tables` | 0.28.0 | CommonMark 与唯一启用的 GFM Table 扩展 | BSD-2-Clause。 |
| `com.fasterxml.jackson.core:jackson-databind` | 2.20.1 | 后续严格 JSON 配置/Manifest 处理 | Apache-2.0。 |
| `info.picocli:picocli` | 4.7.7 | 后续固定 CLI 参数层 | Apache-2.0。 |
| `com.squareup.okhttp3:okhttp` | 4.12.0 | 后续 DashScope Embedding HTTP Client | Apache-2.0；与 Android 现有 OkHttp 主版本对齐。 |
| `org.slf4j:slf4j-nop` | 1.7.32 | Tabula 的无输出日志绑定 | MIT。 |
| `org.junit.jupiter:junit-jupiter` | 5.12.2 | Gradle 标准测试执行 | EPL-2.0。 |

## 关键传递依赖及处理

| 来源 | 已解析传递依赖 | 处理 |
|---|---|---|
| PDFBox | `fontbox:2.0.37`、`commons-logging:1.2` | 由 PDFBox 2.0.37 统一解析；Apache-2.0。 |
| Tabula | `jts-core:1.18.1`、`slf4j-api:1.7.32`、Bouncy Castle 1.69、`commons-cli:1.4`、`commons-csv:1.9.0`、`gson:2.8.7` | 记录为 Tabula 1.0.5 的固定传递集合；正式安全扫描应覆盖这些旧版本。 |
| Tabula 图像组件 | `jai-imageio-core`、`jai-imageio-jpeg2000`、`jbig2-imageio` | 已在 `build.gradle.kts` 显式排除；V1 不支持扫描件/OCR 或依赖图像解码的 PDF。 |
| OkHttp | Okio 3.6.0、Kotlin stdlib 1.9.10 | 仅离线 JVM CLI 使用；不得混入 Android 的依赖解析结果。 |
| ObjectBox Plugin | processor、generator、Moshi、Tink、Protobuf 等构建期组件 | 仅由独立 CLI Plugin 解析；Android 端必须在 G005 单独复核 Plugin、AGP、R8 与 ABI。 |

## 技术验证结果

- `RagDependencySmokeTest` 在同一 Java 17 进程中完成 ObjectBox、PDFBox、Tabula、jsoup、CommonMark/GFM Table、Jackson、Picocli 与 OkHttp 的类加载。
- 最小 PDFBox 文档经 Tabula `ObjectExtractor` 与 `BasicExtractionAlgorithm` 执行成功。
- 静态 HTML `<main>`/`<table>` DOM 样本和 CommonMark GFM Table 样本均通过。
- `clean test check installDist distZip` 成功；ObjectBox Plugin 在当前 JVM Build 中能够执行且尚未生成 Entity。

## 安全扫描边界

本机未配置组织认可的依赖漏洞扫描工具，因此**未执行漏洞扫描**。本记录不宣称“无漏洞”。后续必须在具备工具时对本文件列出的直接和传递依赖执行扫描，并将发现项、数据库版本、豁免与修复结果补充到本目录。

## Phase 0 结论

许可证/发布范围已由项目负责人确认，候选版本已锁定，Java 17 Parser 共存 Smoke Test 已通过。允许进入 `RAG-G003` 创建共享 Schema；但 ObjectBox JVM/Android 同 Meta Model Store 打开、目标 ABI 与 APK/AAB/R8 验证仍是 `RAG-G004-G008` 的硬 Gate，不能提前宣称跨端兼容完成。

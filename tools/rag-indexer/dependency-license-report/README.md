# 离线索引器依赖与许可证证据目录

本目录只存放 `RAG-G002` 之后由受信 Maven 仓库解析得到的依赖树、许可证清单、校验和、漏洞扫描结果和批准记录的索引，不存放凭证、私有语料或来源不明的 Fat JAR。

当前状态为 `PENDING_TECHNICAL_VERIFICATION`：项目负责人已经批准 ObjectBox 的项目发布范围。候选依赖已锁定，下一步将由 Gradle 从受信仓库解析准确依赖树；漏洞扫描和跨端验证未完成前，本目录不得产生虚假的“已通过”报告。共同 Gate 的结论与候选说明见仓库文档：

`docs/plan_overall/rag/rag_dependency_compatibility_gate.md`

在项目负责人批准 ObjectBox 及相关候选后，才可在此目录记录版本化证据；所有报告必须说明执行命令、时间、仓库来源和未执行项。

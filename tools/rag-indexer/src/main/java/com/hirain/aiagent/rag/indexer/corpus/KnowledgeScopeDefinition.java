package com.hirain.aiagent.rag.indexer.corpus;

/** Bundle 唯一 Scope 的五个协议字段；该值不可从文件名、目录或 Front Matter 推断。 */
public record KnowledgeScopeDefinition(
        String vehicleModel,
        String modelYear,
        String region,
        String softwareVersion,
        String configurationCode
) {
    public KnowledgeScopeDefinition {
        require(vehicleModel); require(modelYear); require(region); require(softwareVersion); require(configurationCode);
    }

    private static void require(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Scope 字段不能为空");
        }
    }
}

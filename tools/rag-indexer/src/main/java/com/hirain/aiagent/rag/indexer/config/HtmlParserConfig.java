package com.hirain.aiagent.rag.indexer.config;

import java.util.List;

/** 已审核的静态 HTML Parser 配置；网络和脚本字段必须固定为 false。 */
public record HtmlParserConfig(String mode, boolean networkAccess, boolean scriptExecution,
                               String contentRootSelector, List<String> excludeSelectors,
                               String embeddedDataExtraction) {
    public HtmlParserConfig {
        excludeSelectors = List.copyOf(excludeSelectors);
    }

    /** 保持既有测试和调用方默认关闭静态脚本数据提取。 */
    public HtmlParserConfig(String mode, boolean networkAccess, boolean scriptExecution,
                            String contentRootSelector, List<String> excludeSelectors) {
        this(mode, networkAccess, scriptExecution, contentRootSelector, excludeSelectors, "NONE");
    }
}

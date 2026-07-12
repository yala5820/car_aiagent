package com.hirain.aiagent.toolgroup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * ToolGroupRegistry 与 LangChain4j ToolSpecification 的一致性校验结果。
 * <p>
 * missingToolNames：registry 中声明了但 ToolSpecification 中不存在的 toolName。
 * ungroupedToolNames：ToolSpecification 中存在但 registry 中未覆盖的 toolName。
 * valid() 仅当两个列表都为空时返回 true。
 */
public final class ToolGroupRegistryValidationResult {
    private final List<String> missingToolNames;
    private final List<String> ungroupedToolNames;
    private final boolean allValid;
    private final String summary;

    private ToolGroupRegistryValidationResult(List<String> missingToolNames,
                                               List<String> ungroupedToolNames) {
        this.missingToolNames = Collections.unmodifiableList(new ArrayList<>(
                missingToolNames != null ? missingToolNames : List.of()));
        this.ungroupedToolNames = Collections.unmodifiableList(new ArrayList<>(
                ungroupedToolNames != null ? ungroupedToolNames : List.of()));
        this.allValid = this.missingToolNames.isEmpty() && this.ungroupedToolNames.isEmpty();
        this.summary = "valid=" + this.allValid
                + "; missingToolNames=[" + String.join(",", this.missingToolNames) + "]"
                + "; ungroupedToolNames=[" + String.join(",", this.ungroupedToolNames) + "]";
    }

    /** 空差异 = 校验通过。 */
    public static ToolGroupRegistryValidationResult empty() {
        return new ToolGroupRegistryValidationResult(List.of(), List.of());
    }

    /** 存在差异 = 校验不通过。 */
    public static ToolGroupRegistryValidationResult of(
            List<String> missing, List<String> ungrouped) {
        return new ToolGroupRegistryValidationResult(missing, ungrouped);
    }

    public List<String> missingToolNames() { return missingToolNames; }
    public List<String> ungroupedToolNames() { return ungroupedToolNames; }
    public boolean valid() { return allValid; }
    public String summary() { return summary; }
}

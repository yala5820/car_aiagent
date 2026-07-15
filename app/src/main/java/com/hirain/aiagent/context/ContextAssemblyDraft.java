package com.hirain.aiagent.context;

import java.util.List;

/**
 * Assembler 的结构候选，保留 Contribution 来源信息，不能直接发送给模型。
 */
final class ContextAssemblyDraft {
    private final ContextFrame frame;

    ContextAssemblyDraft(ContextFrame frame) {
        this.frame = frame;
    }

    ContextFrame frame() { return frame; }
    List<ContextContribution> contributions() {
        return frame != null ? frame.contributions() : List.of();
    }
}

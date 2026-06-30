package com.hirain.aiagent.core.terminator;

import com.hirain.aiagent.core.AgentLoopContext;
import com.hirain.aiagent.core.component.LoopTerminator;

import java.util.List;

import dev.langchain4j.model.chat.response.ChatResponse;

/**
 * 组合多个终止条件。
 * <ul>
 *   <li>{@link Mode#ANY_MATCH} — 任一条件满足即终止（OR）</li>
 *   <li>{@link Mode#ALL_MATCH} — 全部条件满足才终止（AND）</li>
 * </ul>
 */
public class CompositeTerminator implements LoopTerminator {

    public enum Mode { ANY_MATCH, ALL_MATCH }

    private final List<LoopTerminator> terminators;
    private final Mode mode;

    public CompositeTerminator(Mode mode, List<LoopTerminator> terminators) {
        this.mode = mode;
        this.terminators = terminators;
    }

    public CompositeTerminator(LoopTerminator... terminators) {
        this(Mode.ANY_MATCH, List.of(terminators));
    }

    @Override
    public boolean shouldStop(AgentLoopContext ctx, ChatResponse response) {
        return switch (mode) {
            case ANY_MATCH -> terminators.stream().anyMatch(t -> t.shouldStop(ctx, response));
            case ALL_MATCH -> terminators.stream().allMatch(t -> t.shouldStop(ctx, response));
        };
    }
}

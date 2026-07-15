package com.hirain.aiagent.safety.confirmation;

/** 严格解析二次确认文本；模糊表达不会被解释为授权。 */
public final class ConfirmationTextParser {

    public enum Command {
        CONFIRM,
        CANCEL,
        NONE
    }

    public Command parse(String text) {
        String normalized = text != null ? text.trim() : "";
        if ("确认执行".equals(normalized)) return Command.CONFIRM;
        if ("取消执行".equals(normalized)) return Command.CANCEL;
        return Command.NONE;
    }
}

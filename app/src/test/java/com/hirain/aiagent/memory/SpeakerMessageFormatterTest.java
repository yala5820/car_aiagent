package com.hirain.aiagent.memory;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class SpeakerMessageFormatterTest {

    @Test
    public void formatsUserMessageWithSpeakerId() {
        assertEquals("[speaker=user_a] 打开空调",
                SpeakerMessageFormatter.formatUserMessage("user_a", "打开空调"));
    }

    @Test
    public void blankSpeakerFallsBackToDefaultUser() {
        assertEquals("[speaker=default_user] 你好",
                SpeakerMessageFormatter.formatUserMessage("", "你好"));
    }

    @Test
    public void nullTextBecomesEmptySpeakerLine() {
        assertEquals("[speaker=user_a] ",
                SpeakerMessageFormatter.formatUserMessage("user_a", null));
    }
}

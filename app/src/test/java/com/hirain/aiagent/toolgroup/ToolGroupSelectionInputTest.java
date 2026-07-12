package com.hirain.aiagent.toolgroup;

import com.hirain.aiagent.intentrouter.IntentConfidence;
import com.hirain.aiagent.intentrouter.IntentResult;
import com.hirain.aiagent.intentrouter.IntentTag;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class ToolGroupSelectionInputTest {

    @Test
    public void of_usesIntentAndUserInput() {
        IntentResult intent = IntentResult.of(IntentTag.CHAT, IntentConfidence.LOW,
                List.of(), "你好", "TEXT", "test");

        ToolGroupSelectionInput input = ToolGroupSelectionInput.of(intent, "你好");

        assertEquals(intent, input.intentResult());
        assertEquals("你好", input.userInput());
        assertEquals("TEXT", input.inputType());
        assertEquals("chat", input.personaId());
        assertNull(input.userId());
        assertNull(input.sessionId());
    }

    @Test
    public void builder_defaultsInputTypeAndPersona() {
        IntentResult intent = IntentResult.of(IntentTag.CHAT, IntentConfidence.LOW,
                List.of(), "你好", "TEXT", "test");

        ToolGroupSelectionInput input = ToolGroupSelectionInput.builder()
                .intentResult(intent)
                .userInput("你好")
                .build();

        assertEquals(intent, input.intentResult());
        assertEquals("你好", input.userInput());
        assertEquals("TEXT", input.inputType());
        assertEquals("chat", input.personaId());
        assertNull(input.userId());
        assertNull(input.sessionId());
    }

    @Test
    public void builder_preservesUserSessionPersona() {
        IntentResult intent = IntentResult.of(IntentTag.CHAT, IntentConfidence.LOW,
                List.of(), "你好", "TEXT", "test");

        ToolGroupSelectionInput input = ToolGroupSelectionInput.builder()
                .intentResult(intent)
                .userInput("你好")
                .userId("user-1")
                .sessionId("sess-99")
                .personaId("assistant")
                .build();

        assertEquals("user-1", input.userId());
        assertEquals("sess-99", input.sessionId());
        assertEquals("assistant", input.personaId());
    }
}

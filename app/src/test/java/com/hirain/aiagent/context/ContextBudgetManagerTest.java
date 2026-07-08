package com.hirain.aiagent.context;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ContextBudgetManagerTest {

    @Test
    public void trimSection_limitsCharsAndMarksTruncated() {
        ContextBudgetManager manager = ContextBudgetManager.defaultBudget();

        ContextBudgetManager.TrimmedText trimmed =
                manager.trim("abcdef", 4);

        assertEquals("abcd", trimmed.text());
        assertTrue(trimmed.truncated());
    }

    @Test
    public void estimateTokens_usesCoarseCharBasedEstimate() {
        ContextBudgetManager manager = ContextBudgetManager.defaultBudget();

        assertEquals(5, manager.estimateTokens("0123456789"));
    }
}

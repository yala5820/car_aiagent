package com.hirain.aiagent.rag.indexer.parser.table;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

final class TableValidatorTest {
    @Test
    void shouldRejectInconsistentRowsInsteadOfGuessingAColumn() {
        TableValidator validator = new TableValidator();

        assertNull(validator.validate(List.of("项目", "值"), List.of(List.of("油量", "3L"))));
        assertEquals("TABLE_COLUMN_COUNT_MISMATCH", validator.validate(List.of("项目", "值"), List.of(List.of("油量"))));
    }
}

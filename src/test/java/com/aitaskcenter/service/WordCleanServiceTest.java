package com.aitaskcenter.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WordCleanServiceTest {
    @Test
    void buildsParameterizedFiltersWithoutEmbeddingUserInput() {
        WordCleanService.QueryFilter filter = WordCleanService.buildFilter("Apple", 3, 25, 100, 199);

        assertTrue(filter.sql().contains("LIKE ?"));
        assertTrue(filter.sql().contains("wc.pep_difficulty = ?"));
        assertTrue(filter.sql().contains("wc.source_difficulty = ?"));
        assertFalse(filter.sql().contains("Apple"));
        assertEquals("%apple%", filter.args().get(0));
        assertEquals(9, filter.args().size());
    }

    @Test
    void restrictsSortingToKnownColumns() {
        assertEquals("wc.frequency DESC NULLS LAST, wc.id ASC", WordCleanService.orderBy("frequency", "desc"));
        assertEquals("wc.id ASC", WordCleanService.orderBy("drop table word_clean", "desc"));
    }
}

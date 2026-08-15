package com.aitaskcenter.model;

import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class ImagePersistenceIndexTest {
    @Test
    void declaresIndexesForImageRunQueriesAndGalleries() {
        assertIndex(ImageRun.class, "created_at");
        assertIndex(ImageRun.class, "status,created_at");
        assertIndex(ImageShot.class, "run_id,shot_sequence");
        assertIndex(ImageAsset.class, "run_id,created_at");
    }

    private static void assertIndex(Class<?> type, String columns) {
        assertTrue(Arrays.stream(type.getAnnotation(Table.class).indexes())
                .map(Index::columnList)
                .anyMatch(columns::equals), type.getSimpleName() + " missing index " + columns);
    }
}

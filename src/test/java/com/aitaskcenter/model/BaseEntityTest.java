package com.aitaskcenter.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class BaseEntityTest {
    @Test
    void normalizesLifecycleTimestampsToPostgresMicrosecondPrecision() {
        OffsetDateTime nanosecondTimestamp = OffsetDateTime.parse("2026-08-12T18:40:23.619721086Z");

        OffsetDateTime normalized = BaseEntity.normalizeTimestamp(nanosecondTimestamp);

        assertEquals(OffsetDateTime.parse("2026-08-12T18:40:23.619721Z"), normalized);
    }
}

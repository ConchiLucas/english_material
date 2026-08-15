package com.aitaskcenter.dto;

import java.time.OffsetDateTime;

public record MinioConfigView(
        boolean enabled,
        String endpoint,
        String accessKeyId,
        boolean useSsl,
        String bucketName,
        String basePath,
        boolean secretConfigured,
        OffsetDateTime updatedAt) {
}

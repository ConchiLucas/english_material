package com.aitaskcenter.dto;

import java.time.OffsetDateTime;

public record MinioConfigRequest(
        boolean enabled,
        String endpoint,
        String accessKeyId,
        String secretAccessKey,
        boolean useSsl,
        String bucketName,
        String basePath,
        OffsetDateTime updatedAt) {
}

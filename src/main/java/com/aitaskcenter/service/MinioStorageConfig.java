package com.aitaskcenter.service;

record MinioStorageConfig(
        boolean enabled,
        String endpoint,
        String accessKeyId,
        String secretAccessKey,
        boolean useSsl,
        String bucketName,
        String basePath) {
}

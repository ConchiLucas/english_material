package com.aitaskcenter.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aitaskcenter.dto.MinioConfigRequest;
import com.aitaskcenter.dto.MinioConfigView;
import com.aitaskcenter.model.MinioConfig;
import com.aitaskcenter.repository.ImageAssetRepository;
import com.aitaskcenter.repository.MinioConfigRepository;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

class MinioConfigServiceTest {
    private static final String SECRET = "minio-secret-never-return";
    private static final OffsetDateTime UPDATED_AT = OffsetDateTime.parse("2026-08-16T09:30:00+08:00");

    private MinioConfigRepository repository;
    private ImageAssetRepository imageAssetRepository;
    private MinioConnectionVerifier verifier;
    private MinioConfigService service;

    @BeforeEach
    void setUp() {
        repository = Mockito.mock(MinioConfigRepository.class);
        imageAssetRepository = Mockito.mock(ImageAssetRepository.class);
        verifier = Mockito.mock(MinioConnectionVerifier.class);
        service = new MinioConfigService(repository, imageAssetRepository, verifier);
        when(repository.saveAndFlush(any(MinioConfig.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void returnsSafeDefaultsWhenNoConfigurationExists() {
        when(repository.findByConfigKey("default")).thenReturn(Optional.empty());

        MinioConfigView result = service.get();

        assertFalse(result.enabled());
        assertEquals("", result.endpoint());
        assertEquals("", result.accessKeyId());
        assertFalse(result.useSsl());
        assertEquals("english-material", result.bucketName());
        assertEquals("image-story", result.basePath());
        assertFalse(result.secretConfigured());
        assertNull(result.updatedAt());
    }

    @Test
    void savesTrimmedConfigurationWithoutReturningSecret() {
        when(repository.findByConfigKey("default")).thenReturn(Optional.empty());
        MinioConfigRequest request = new MinioConfigRequest(
                true, "  minio.internal:9000  ", "  english-app  ", SECRET,
                true, " english-material ", " image-story ", null);

        MinioConfigView result = service.save(request);

        ArgumentCaptor<MinioConfig> capture = ArgumentCaptor.forClass(MinioConfig.class);
        verify(repository).saveAndFlush(capture.capture());
        MinioConfig saved = capture.getValue();
        assertEquals("default", saved.getConfigKey());
        assertEquals("minio.internal:9000", saved.getEndpoint());
        assertEquals("english-app", saved.getAccessKeyId());
        assertEquals(SECRET, saved.getSecretAccessKey());
        assertEquals("english-material", saved.getBucketName());
        assertEquals("image-story", saved.getBasePath());
        assertTrue(result.secretConfigured());
        assertFalse(result.toString().contains(SECRET));
        verify(verifier).verify(any(MinioStorageConfig.class));
    }

    @Test
    void testsUsingSavedSecretWithoutPersisting() {
        MinioConfig existing = configured();
        when(repository.findByConfigKey("default")).thenReturn(Optional.of(existing));

        service.test(new MinioConfigRequest(
                true, "minio.internal:9000", "english-app", "",
                false, "english-material", "image-story", UPDATED_AT));

        ArgumentCaptor<MinioStorageConfig> capture = ArgumentCaptor.forClass(MinioStorageConfig.class);
        verify(verifier).verify(capture.capture());
        assertEquals(SECRET, capture.getValue().secretAccessKey());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void blankSecretPreservesPreviouslySavedSecret() {
        MinioConfig existing = configured();
        when(repository.findByConfigKey("default")).thenReturn(Optional.of(existing));

        service.save(new MinioConfigRequest(
                true, "minio.internal:9000", "english-app", "   ",
                false, "english-material", "image-story", UPDATED_AT));

        ArgumentCaptor<MinioConfig> capture = ArgumentCaptor.forClass(MinioConfig.class);
        verify(repository).saveAndFlush(capture.capture());
        assertEquals(SECRET, capture.getValue().getSecretAccessKey());
    }

    @Test
    void rejectsStaleTimestampBeforeWriting() {
        MinioConfig existing = configured();
        when(repository.findByConfigKey("default")).thenReturn(Optional.of(existing));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> service.save(
                new MinioConfigRequest(true, "minio.internal:9000", "english-app", "",
                        false, "english-material", "image-story", UPDATED_AT.minusNanos(1_000))));

        assertEquals("MinIO 配置已被更新，请刷新后重试", error.getMessage());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void rejectsUnsafeEndpointBucketAndBasePathWithoutLeakingInput() {
        when(repository.findByConfigKey("default")).thenReturn(Optional.empty());
        String unsafeEndpoint = "http://user:password@minio.internal:9000/private?token=secret";

        IllegalArgumentException endpointError = assertThrows(IllegalArgumentException.class, () -> service.save(
                new MinioConfigRequest(true, unsafeEndpoint, "english-app", SECRET,
                        false, "english-material", "image-story", null)));
        IllegalArgumentException bucketError = assertThrows(IllegalArgumentException.class, () -> service.save(
                new MinioConfigRequest(true, "minio.internal:9000", "english-app", SECRET,
                        false, "English_Material", "image-story", null)));
        IllegalArgumentException pathError = assertThrows(IllegalArgumentException.class, () -> service.save(
                new MinioConfigRequest(true, "minio.internal:9000", "english-app", SECRET,
                        false, "english-material", "../image-story", null)));

        assertEquals("MinIO Endpoint 格式无效", endpointError.getMessage());
        assertEquals("MinIO Bucket 名称格式无效", bucketError.getMessage());
        assertEquals("MinIO 基础路径格式无效", pathError.getMessage());
        assertFalse(endpointError.getMessage().contains("password"));
        assertFalse(endpointError.getMessage().contains("token"));
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void rejectsEndpointChangesWhileImageAssetsExist() {
        MinioConfig existing = configured();
        when(repository.findByConfigKey("default")).thenReturn(Optional.of(existing));
        when(imageAssetRepository.count()).thenReturn(1L);

        IllegalArgumentException endpoint = assertThrows(IllegalArgumentException.class, () -> service.save(
                new MinioConfigRequest(true, "other-minio.internal:9000", "english-app", "",
                        false, "english-material", "image-story", UPDATED_AT)));
        IllegalArgumentException ssl = assertThrows(IllegalArgumentException.class, () -> service.save(
                new MinioConfigRequest(true, "minio.internal:9000", "english-app", "",
                        true, "english-material", "image-story", UPDATED_AT)));
        assertEquals("已有图片资产时不能修改 MinIO 存储位置", endpoint.getMessage());
        assertEquals(endpoint.getMessage(), ssl.getMessage());
        verify(verifier, never()).verify(any());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void allowsCredentialBucketAndBasePathChangesWhileImageAssetsExist() {
        MinioConfig existing = configured();
        when(repository.findByConfigKey("default")).thenReturn(Optional.of(existing));
        when(imageAssetRepository.count()).thenReturn(1L);

        service.save(new MinioConfigRequest(
                true, "minio.internal:9000", "rotated-access", "rotated-secret",
                false, "other-english-material", "other-story", UPDATED_AT));

        verify(verifier).verify(any(MinioStorageConfig.class));
        verify(repository).saveAndFlush(existing);
        assertEquals("other-english-material", existing.getBucketName());
        assertEquals("other-story", existing.getBasePath());
    }

    private MinioConfig configured() {
        MinioConfig config = new MinioConfig();
        config.setConfigKey("default");
        config.setEnabled(true);
        config.setEndpoint("minio.internal:9000");
        config.setAccessKeyId("english-app");
        config.setSecretAccessKey(SECRET);
        config.setUseSsl(false);
        config.setBucketName("english-material");
        config.setBasePath("image-story");
        ReflectionTestUtils.setField(config, "updatedAt", UPDATED_AT);
        return config;
    }
}

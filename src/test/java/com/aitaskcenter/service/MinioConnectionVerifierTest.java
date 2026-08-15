package com.aitaskcenter.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MinioConnectionVerifierTest {
    private static final String SECRET = "verifier-secret-never-leak";
    private static final String ENDPOINT = "private-minio.internal:9000";
    private static final MinioStorageConfig CONFIG = new MinioStorageConfig(
            true, ENDPOINT, "english-app", SECRET, false,
            "english-material", "image-story");

    private MinioClientFactory factory;
    private MinioClientFactory.Client client;
    private MinioConnectionVerifier verifier;

    @BeforeEach
    void setUp() {
        factory = mock(MinioClientFactory.class);
        client = mock(MinioClientFactory.Client.class);
        verifier = new MinioConnectionVerifier(factory);
        when(factory.create(CONFIG)).thenReturn(client);
    }

    @Test
    void createsMissingPrivateBucketThenWritesReadsAndDeletesProbe() throws Exception {
        when(client.bucketExists("english-material")).thenReturn(false);
        when(client.getObject(eq("english-material"), anyString()))
                .thenReturn(new ByteArrayInputStream(new byte[] {0x45, 0x4d}));

        verifier.verify(CONFIG);

        verify(client).makeBucket("english-material");
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(client).putObject(eq("english-material"), key.capture(), eq(new byte[] {0x45, 0x4d}),
                eq("application/octet-stream"), eq(true));
        assertFalse(key.getValue().contains(".."));
        verify(client).getObject("english-material", key.getValue());
        verify(client).removeObject("english-material", key.getValue());
    }

    @Test
    void reusesExistingBucketWithoutChangingPolicy() throws Exception {
        when(client.bucketExists("english-material")).thenReturn(true);
        when(client.getObject(eq("english-material"), anyString()))
                .thenReturn(new ByteArrayInputStream(new byte[] {0x45, 0x4d}));

        verifier.verify(CONFIG);

        verify(client, never()).makeBucket(anyString());
    }

    @Test
    void rejectsIncorrectProbeContentAndStillDeletesProbe() throws Exception {
        when(client.bucketExists("english-material")).thenReturn(true);
        when(client.getObject(eq("english-material"), anyString()))
                .thenReturn(new ByteArrayInputStream(new byte[] {0x00}));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> verifier.verify(CONFIG));

        assertEquals("MinIO 写入后读取校验失败", error.getMessage());
        verify(client).removeObject(eq("english-material"), anyString());
    }

    @Test
    void returnsBoundedErrorWithoutEndpointOrSecret() throws Exception {
        when(client.bucketExists("english-material"))
                .thenThrow(new IllegalStateException("connect " + ENDPOINT + " with " + SECRET));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> verifier.verify(CONFIG));

        assertEquals("MinIO 连接或权限验证失败", error.getMessage());
        assertFalse(error.getMessage().contains(ENDPOINT));
        assertFalse(error.getMessage().contains(SECRET));
    }
}

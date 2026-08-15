package com.aitaskcenter.service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class MinioConnectionVerifier {
    private static final byte[] PROBE = new byte[] {0x45, 0x4d};
    private final MinioClientFactory factory;

    public MinioConnectionVerifier(MinioClientFactory factory) {
        this.factory = factory;
    }

    public void verify(MinioStorageConfig config) {
        if (config == null || !config.enabled()) throw new IllegalArgumentException("请先启用 MinIO 配置");
        String key = config.basePath() + "/.readiness/" + UUID.randomUUID();
        MinioClientFactory.Client client = factory.create(config);
        boolean written = false;
        RuntimeException failure = null;
        try {
            if (!client.bucketExists(config.bucketName())) client.makeBucket(config.bucketName());
            client.putObject(config.bucketName(), key, PROBE, "application/octet-stream", true);
            written = true;
            byte[] actual;
            try (InputStream input = client.getObject(config.bucketName(), key)) {
                actual = readAtMost(input, PROBE.length + 1);
            }
            if (!Arrays.equals(PROBE, actual)) {
                throw new IllegalArgumentException("MinIO 写入后读取校验失败");
            }
        } catch (IllegalArgumentException ex) {
            failure = ex;
        } catch (Exception ex) {
            failure = new IllegalArgumentException("MinIO 连接或权限验证失败");
        } finally {
            if (written) {
                try {
                    client.removeObject(config.bucketName(), key);
                } catch (Exception cleanup) {
                    if (failure == null) failure = new IllegalArgumentException("MinIO 探测对象清理失败");
                }
            }
        }
        if (failure != null) throw failure;
    }

    private static byte[] readAtMost(InputStream input, int limit) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream(limit);
        byte[] buffer = new byte[32];
        while (output.size() < limit) {
            int remaining = limit - output.size();
            int count = input.read(buffer, 0, Math.min(buffer.length, remaining));
            if (count < 0) break;
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }
}

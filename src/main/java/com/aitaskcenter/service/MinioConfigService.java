package com.aitaskcenter.service;

import com.aitaskcenter.dto.MinioConfigRequest;
import com.aitaskcenter.dto.MinioConfigView;
import com.aitaskcenter.model.MinioConfig;
import com.aitaskcenter.repository.MinioConfigRepository;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MinioConfigService {
    static final String DEFAULT_KEY = "default";
    static final String DEFAULT_BUCKET = "english-material";
    static final String DEFAULT_BASE_PATH = "image-story";
    private static final String STALE_MESSAGE = "MinIO 配置已被更新，请刷新后重试";
    private static final Pattern ENDPOINT = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9.-]{0,252}[A-Za-z0-9])?:[0-9]{1,5}");
    private static final Pattern BUCKET = Pattern.compile("[a-z0-9](?:[a-z0-9.-]{1,61}[a-z0-9])?");
    private static final Pattern PATH_SEGMENT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,62}");

    private final MinioConfigRepository repository;
    private final MinioConnectionVerifier verifier;

    public MinioConfigService(MinioConfigRepository repository, MinioConnectionVerifier verifier) {
        this.repository = repository;
        this.verifier = verifier;
    }

    @Transactional(readOnly = true)
    public MinioConfigView get() {
        return repository.findByConfigKey(DEFAULT_KEY)
                .map(this::toView)
                .orElseGet(this::defaultView);
    }

    @Transactional
    public MinioConfigView save(MinioConfigRequest request) {
        Optional<MinioConfig> found = repository.findByConfigKey(DEFAULT_KEY);
        MinioConfig current = found.orElseGet(MinioConfig::new);
        if (found.isPresent()) requireCurrentTimestamp(current.getUpdatedAt(), request == null ? null : request.updatedAt());
        Normalized normalized = normalize(request, found.map(MinioConfig::getSecretAccessKey).orElse(""));
        if (found.isPresent() && storageLocationChanged(current, normalized)) {
            throw new IllegalArgumentException("MinIO Endpoint 与 SSL 保存后不能修改");
        }
        if (normalized.enabled()) verifier.verify(normalized.toStorageConfig());
        current.setConfigKey(DEFAULT_KEY);
        current.setEnabled(normalized.enabled());
        current.setEndpoint(normalized.endpoint());
        current.setAccessKeyId(normalized.accessKeyId());
        current.setSecretAccessKey(normalized.secretAccessKey());
        current.setUseSsl(normalized.useSsl());
        current.setBucketName(normalized.bucketName());
        current.setBasePath(normalized.basePath());
        return toView(repository.saveAndFlush(current));
    }

    @Transactional(readOnly = true)
    public void test(MinioConfigRequest request) {
        String savedSecret = repository.findByConfigKey(DEFAULT_KEY)
                .map(MinioConfig::getSecretAccessKey)
                .orElse("");
        Normalized normalized = normalize(request, savedSecret);
        verifier.verify(normalized.toStorageConfig());
    }

    @Transactional(readOnly = true)
    MinioStorageConfig requireEnabled() {
        MinioConfig config = repository.findByConfigKey(DEFAULT_KEY)
                .orElseThrow(() -> new IllegalArgumentException("尚未配置 MinIO"));
        MinioStorageConfig resolved = new MinioStorageConfig(config.isEnabled(), config.getEndpoint(),
                config.getAccessKeyId(), config.getSecretAccessKey(), config.isUseSsl(),
                config.getBucketName(), config.getBasePath());
        if (!resolved.enabled()) throw new IllegalArgumentException("MinIO 配置未启用");
        return resolved;
    }

    Normalized normalize(MinioConfigRequest request, String savedSecret) {
        if (request == null) throw new IllegalArgumentException("MinIO 配置不能为空");
        String endpoint = clean(request.endpoint());
        String accessKeyId = clean(request.accessKeyId());
        String incomingSecret = request.secretAccessKey() == null ? "" : request.secretAccessKey().trim();
        String secret = incomingSecret.isEmpty() ? clean(savedSecret) : incomingSecret;
        String bucket = clean(request.bucketName()).toLowerCase(Locale.ROOT);
        String basePath = clean(request.basePath());
        if (bucket.isEmpty()) bucket = DEFAULT_BUCKET;
        if (basePath.isEmpty()) basePath = DEFAULT_BASE_PATH;

        if (!endpoint.isEmpty()) validateEndpoint(endpoint);
        validateBucket(bucket);
        validateBasePath(basePath);
        if (accessKeyId.length() > 160 || secret.length() > 512) {
            throw new IllegalArgumentException("MinIO 凭据格式无效");
        }
        if (request.enabled() && (endpoint.isEmpty() || accessKeyId.isEmpty() || secret.isEmpty())) {
            throw new IllegalArgumentException("启用 MinIO 时必须填写连接地址和凭据");
        }
        return new Normalized(request.enabled(), endpoint, accessKeyId, secret,
                request.useSsl(), bucket, basePath);
    }

    private static void validateEndpoint(String endpoint) {
        if (endpoint.length() > 255 || !ENDPOINT.matcher(endpoint).matches()) {
            throw new IllegalArgumentException("MinIO Endpoint 格式无效");
        }
        int colon = endpoint.lastIndexOf(':');
        try {
            int port = Integer.parseInt(endpoint.substring(colon + 1));
            if (port < 1 || port > 65_535) throw new NumberFormatException();
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("MinIO Endpoint 格式无效");
        }
    }

    private static void validateBucket(String bucket) {
        if (bucket.length() < 3 || bucket.length() > 63 || !BUCKET.matcher(bucket).matches()
                || bucket.contains("..") || bucket.matches("[0-9]+(?:\\.[0-9]+){3}")) {
            throw new IllegalArgumentException("MinIO Bucket 名称格式无效");
        }
    }

    private static void validateBasePath(String basePath) {
        if (basePath.length() > 240 || basePath.startsWith("/") || basePath.endsWith("/")) {
            throw new IllegalArgumentException("MinIO 基础路径格式无效");
        }
        for (String segment : basePath.split("/", -1)) {
            if (segment.equals(".") || segment.equals("..") || !PATH_SEGMENT.matcher(segment).matches()) {
                throw new IllegalArgumentException("MinIO 基础路径格式无效");
            }
        }
    }

    private static void requireCurrentTimestamp(OffsetDateTime current, OffsetDateTime requested) {
        if (current != null && (requested == null || !current.toInstant().equals(requested.toInstant()))) {
            throw new IllegalArgumentException(STALE_MESSAGE);
        }
    }

    private static boolean storageLocationChanged(MinioConfig current, Normalized requested) {
        return !clean(current.getEndpoint()).equals(requested.endpoint())
                || current.isUseSsl() != requested.useSsl();
    }

    private MinioConfigView toView(MinioConfig config) {
        return new MinioConfigView(config.isEnabled(), config.getEndpoint(), config.getAccessKeyId(),
                config.isUseSsl(), config.getBucketName(), config.getBasePath(),
                !clean(config.getSecretAccessKey()).isEmpty(), config.getUpdatedAt());
    }

    private MinioConfigView defaultView() {
        return new MinioConfigView(false, "", "", false,
                DEFAULT_BUCKET, DEFAULT_BASE_PATH, false, null);
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }

    record Normalized(boolean enabled, String endpoint, String accessKeyId, String secretAccessKey,
                      boolean useSsl, String bucketName, String basePath) {
        MinioStorageConfig toStorageConfig() {
            return new MinioStorageConfig(enabled, endpoint, accessKeyId, secretAccessKey,
                    useSsl, bucketName, basePath);
        }
    }
}

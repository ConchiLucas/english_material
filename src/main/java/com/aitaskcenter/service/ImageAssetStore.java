package com.aitaskcenter.service;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.springframework.stereotype.Service;

@Service
public class ImageAssetStore {
    private static final long MAX_BYTES = 25L * 1024 * 1024;
    private static final int MAX_DIMENSION = 8_192;
    private static final long MAX_PIXELS = 40_000_000L;
    private static final int MAX_RUN_ID_LENGTH = 64;
    private static final int MAX_ASSET_KEY_LENGTH = 120;
    private static final Map<String, String> MIME_TO_EXTENSION = Map.of(
            "image/png", "png",
            "image/jpeg", "jpg");

    private final MinioConfigService configService;
    private final MinioConnectionVerifier verifier;
    private final MinioClientFactory clientFactory;

    public ImageAssetStore(
            MinioConfigService configService,
            MinioConnectionVerifier verifier,
            MinioClientFactory clientFactory) {
        this.configService = configService;
        this.verifier = verifier;
        this.clientFactory = clientFactory;
    }

    public void assertWritable() {
        verifier.verify(configService.requireEnabled());
    }

    public StoredAsset store(String runId, String assetKey, String declaredMime, byte[] bytes) {
        validateSegment(runId, MAX_RUN_ID_LENGTH, "runId");
        validateSegment(assetKey, MAX_ASSET_KEY_LENGTH, "assetKey");
        String mime = normalizeMime(declaredMime);
        validateBytes(bytes);
        ImageMetadata image = inspect(bytes);
        if (!mime.equals(image.mime())) {
            throw new IllegalArgumentException("声明的图片 MIME 与实际内容不一致");
        }
        MinioStorageConfig config = configService.requireEnabled();
        String relativePath = runId + "/" + assetKey + "." + MIME_TO_EXTENSION.get(mime);
        String objectKey = objectKey(config, relativePath);
        MinioClientFactory.Client client = clientFactory.create(config);
        boolean uploaded = false;
        try {
            client.putObject(config.bucketName(), objectKey, bytes, mime, true);
            uploaded = true;
            MinioClientFactory.ObjectMetadata metadata = client.statObject(config.bucketName(), objectKey);
            if (metadata == null || metadata.size() != bytes.length || !mime.equals(metadata.contentType())) {
                throw new IllegalStateException("MinIO 图片对象元数据校验失败");
            }
            return new StoredAsset(config.bucketName() + "/" + objectKey,
                    mime, image.width(), image.height(), sha256(bytes));
        } catch (MinioClientFactory.ObjectAlreadyExistsException exception) {
            throw duplicate();
        } catch (Exception exception) {
            if (uploaded) {
                try {
                    removeUploadedObjectIfMatching(client, config.bucketName(), objectKey, sha256(bytes));
                } catch (Exception cleanup) {
                    exception.addSuppressed(cleanup);
                }
            }
            throw new IllegalStateException("保存图片资产失败", exception);
        }
    }

    public byte[] read(String relativePath, String expectedSha256) {
        StoredLocation location = validateStoredLocation(relativePath);
        validateSha256(expectedSha256);
        MinioStorageConfig config = configService.requireEnabled();
        try (InputStream input = clientFactory.create(config)
                .getObject(location.bucket(), location.objectKey())) {
            byte[] content = readBounded(input);
            if (!sha256(content).equals(expectedSha256)) {
                throw new IllegalArgumentException("图片资产哈希不匹配");
            }
            inspect(content);
            return content;
        } catch (MinioClientFactory.ObjectMissingException exception) {
            throw new IllegalArgumentException("图片资产不存在");
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("读取图片资产失败", exception);
        }
    }

    public void delete(String relativePath, String expectedSha256) {
        StoredLocation location = validateStoredLocation(relativePath);
        validateSha256(expectedSha256);
        MinioStorageConfig config = configService.requireEnabled();
        MinioClientFactory.Client client = clientFactory.create(config);
        try (InputStream input = client.getObject(location.bucket(), location.objectKey())) {
            byte[] content = readBounded(input);
            if (!sha256(content).equals(expectedSha256)) {
                throw new IllegalArgumentException("图片资产哈希不匹配，拒绝删除");
            }
            inspect(content);
            client.removeObject(location.bucket(), location.objectKey());
        } catch (MinioClientFactory.ObjectMissingException exception) {
            throw new IllegalArgumentException("图片资产不存在");
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("删除图片资产失败", exception);
        }
    }

    private static String objectKey(MinioStorageConfig config, String relativePath) {
        return config.basePath() + "/" + relativePath;
    }

    private static void removeUploadedObjectIfMatching(
            MinioClientFactory.Client client, String bucket, String objectKey, String expectedSha256)
            throws Exception {
        try (InputStream input = client.getObject(bucket, objectKey)) {
            byte[] stored = readBounded(input);
            if (sha256(stored).equals(expectedSha256)) client.removeObject(bucket, objectKey);
        }
    }

    private static byte[] readBounded(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int count;
        while ((count = input.read(buffer)) >= 0) {
            if (count == 0) continue;
            total += count;
            if (total > MAX_BYTES) throw new IllegalArgumentException("图片资产大小不安全");
            output.write(buffer, 0, count);
        }
        if (total == 0) throw new IllegalArgumentException("图片资产大小不安全");
        return output.toByteArray();
    }

    private static void validateSegment(String value, int limit, String fieldName) {
        if (value == null || value.isBlank() || value.length() > limit
                || value.contains("/") || value.contains("\\") || value.contains("..")
                || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IllegalArgumentException(fieldName + " 不是安全的存储路径片段");
        }
    }

    private static StoredLocation validateStoredLocation(String value) {
        if (value == null || value.isBlank() || value.length() > 500
                || value.startsWith("/") || value.contains("\\")) {
            throw new IllegalArgumentException("图片存储位置不能为空或不安全");
        }
        String[] parts = value.split("/", -1);
        if (parts.length < 4) throw new IllegalArgumentException("图片存储位置格式无效");
        String bucket = parts[0];
        if (bucket.length() < 3 || bucket.length() > 63
                || !bucket.matches("[a-z0-9](?:[a-z0-9.-]{1,61}[a-z0-9])?")
                || bucket.contains("..")) {
            throw new IllegalArgumentException("图片 Bucket 格式无效");
        }
        for (int index = 1; index < parts.length - 2; index++) {
            validateSegment(parts[index], 63, "basePath");
        }
        validateSegment(parts[parts.length - 2], MAX_RUN_ID_LENGTH, "runId");
        validateSegment(parts[parts.length - 1], MAX_ASSET_KEY_LENGTH + 5, "fileName");
        return new StoredLocation(bucket, String.join("/", java.util.Arrays.copyOfRange(parts, 1, parts.length)));
    }

    private static void validateSha256(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("图片哈希格式不安全");
        }
    }

    private static String normalizeMime(String declaredMime) {
        String mime = declaredMime == null ? "" : declaredMime.trim().toLowerCase(Locale.ROOT);
        if (!MIME_TO_EXTENSION.containsKey(mime)) throw new IllegalArgumentException("不支持的图片 MIME 类型");
        return mime;
    }

    private static void validateBytes(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) {
            throw new IllegalArgumentException("图片字节大小不安全");
        }
    }

    private static ImageMetadata inspect(byte[] bytes) {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (input == null) throw new IllegalArgumentException("图片内容无法识别");
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IllegalArgumentException("图片内容无法识别");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > MAX_DIMENSION || height > MAX_DIMENSION
                        || (long) width * height > MAX_PIXELS) {
                    throw new IllegalArgumentException("图片尺寸不安全");
                }
                BufferedImage decoded = reader.read(0);
                if (decoded == null) throw new IllegalArgumentException("图片内容无法解码");
                String mime = switch (reader.getFormatName().toLowerCase(Locale.ROOT)) {
                    case "png" -> "image/png";
                    case "jpeg", "jpg" -> "image/jpeg";
                    default -> throw new IllegalArgumentException("不支持的图片内容类型");
                };
                return new ImageMetadata(mime, width, height);
            } finally {
                reader.dispose();
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("图片内容无法解码", exception);
        }
    }

    private static IllegalStateException duplicate() {
        return new IllegalStateException("图片资产已存在，不能覆盖审计记录");
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder value = new StringBuilder(digest.length * 2);
            for (byte part : digest) value.append(String.format("%02x", part));
            return value.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private record ImageMetadata(String mime, int width, int height) { }

    private record StoredLocation(String bucket, String objectKey) { }

    public record StoredAsset(String relativePath, String mime, int width, int height, String sha256) { }
}

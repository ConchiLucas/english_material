package com.aitaskcenter.service;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ImageAssetStore {
    private static final long MAX_BYTES = 25L * 1024 * 1024;
    private static final int MAX_DIMENSION = 8_192;
    private static final long MAX_PIXELS = 40_000_000L;
    private static final int MAX_SEGMENT_LENGTH = 120;
    private static final Map<String, String> MIME_TO_EXTENSION = Map.of(
            "image/png", "png",
            "image/jpeg", "jpg",
            "image/webp", "webp");

    private final Path storageRoot;

    public ImageAssetStore(@Value("${image-story.storage-root}") String storageRoot) {
        if (storageRoot == null || storageRoot.isBlank()) {
            throw new IllegalArgumentException("图片存储目录不能为空");
        }
        this.storageRoot = Path.of(storageRoot).toAbsolutePath().normalize();
    }

    public StoredAsset store(String runId, String assetKey, String declaredMime, byte[] bytes) {
        validateSegment(runId, "runId");
        validateSegment(assetKey, "assetKey");
        String mime = normalizeMime(declaredMime);
        validateBytes(bytes);
        ImageMetadata image = inspect(bytes);
        if (!mime.equals(image.mime())) {
            throw new IllegalArgumentException("声明的图片 MIME 与实际内容不一致");
        }

        String relativePath = runId + "/" + assetKey + "." + MIME_TO_EXTENSION.get(mime);
        Path temporary = null;
        try {
            Files.createDirectories(storageRoot);
            Path realRoot = storageRoot.toRealPath();
            Path target = storageRoot.resolve(relativePath).normalize();
            if (!target.startsWith(storageRoot)) {
                throw new IllegalArgumentException("图片路径必须位于存储目录内");
            }
            Path parent = target.getParent();
            Files.createDirectories(parent);
            Path realParent = parent.toRealPath();
            if (!realParent.startsWith(realRoot)) {
                throw new IllegalArgumentException("图片路径不能通过符号链接离开存储目录");
            }
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("图片资产已存在，不能覆盖审计记录");
            }

            temporary = Files.createTempFile(realParent, "." + assetKey + "-", ".tmp");
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                throw new IllegalStateException("当前文件系统不支持原子保存图片资产", ex);
            } catch (java.nio.file.FileAlreadyExistsException ex) {
                throw new IllegalStateException("图片资产已存在，不能覆盖审计记录", ex);
            }
            temporary = null;
            return new StoredAsset(relativePath, mime, image.width(), image.height(), sha256(bytes));
        } catch (IOException ex) {
            throw new IllegalStateException("保存图片资产失败", ex);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // The write failure is retained as the primary outcome.
                }
            }
        }
    }

    public byte[] read(String databaseRelativePath) {
        Path relative = validateRelativePath(databaseRelativePath);
        try {
            if (!Files.exists(storageRoot, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("图片资产不存在");
            }
            Path realRoot = storageRoot.toRealPath();
            Path target = storageRoot.resolve(relative).normalize();
            if (!target.startsWith(storageRoot) || hasSymbolicLinkComponent(relative)) {
                throw new IllegalArgumentException("图片路径不安全");
            }
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("图片资产不存在");
            }
            Path realTarget = target.toRealPath();
            if (!realTarget.startsWith(realRoot)) {
                throw new IllegalArgumentException("图片路径不安全");
            }
            long size = Files.size(realTarget);
            if (size < 1 || size > MAX_BYTES) {
                throw new IllegalArgumentException("图片资产大小不安全");
            }
            return Files.readAllBytes(realTarget);
        } catch (IOException ex) {
            throw new IllegalArgumentException("读取图片资产失败", ex);
        }
    }

    private static void validateSegment(String value, String fieldName) {
        if (value == null || value.isBlank() || value.length() > MAX_SEGMENT_LENGTH
                || value.contains("/") || value.contains("\\") || value.contains("..")
                || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IllegalArgumentException(fieldName + " 不是安全的存储路径片段");
        }
    }

    private static String normalizeMime(String declaredMime) {
        String mime = declaredMime == null ? "" : declaredMime.trim().toLowerCase(Locale.ROOT);
        if (!MIME_TO_EXTENSION.containsKey(mime)) {
            throw new IllegalArgumentException("不支持的图片 MIME 类型");
        }
        return mime;
    }

    private static void validateBytes(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) {
            throw new IllegalArgumentException("图片字节大小不安全");
        }
    }

    private static ImageMetadata inspect(byte[] bytes) {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (input == null) {
                throw new IllegalArgumentException("图片内容无法识别");
            }
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new IllegalArgumentException("图片内容无法识别");
            }
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
                if (decoded == null) {
                    throw new IllegalArgumentException("图片内容无法解码");
                }
                String mime = switch (reader.getFormatName().toLowerCase(Locale.ROOT)) {
                    case "png" -> "image/png";
                    case "jpeg", "jpg" -> "image/jpeg";
                    case "webp" -> "image/webp";
                    default -> throw new IllegalArgumentException("不支持的图片内容类型");
                };
                return new ImageMetadata(mime, width, height);
            } finally {
                reader.dispose();
            }
        } catch (IOException ex) {
            throw new IllegalArgumentException("图片内容无法解码", ex);
        }
    }

    private Path validateRelativePath(String databaseRelativePath) {
        if (databaseRelativePath == null || databaseRelativePath.isBlank()) {
            throw new IllegalArgumentException("图片相对路径不能为空");
        }
        Path relative = Path.of(databaseRelativePath);
        if (relative.isAbsolute() || relative.getNameCount() == 0) {
            throw new IllegalArgumentException("图片路径必须是相对路径");
        }
        for (Path part : relative) {
            if ("..".equals(part.toString())) {
                throw new IllegalArgumentException("图片路径不允许穿越目录");
            }
        }
        return relative;
    }

    private boolean hasSymbolicLinkComponent(Path relative) {
        Path current = storageRoot;
        for (Path part : relative) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) {
                return true;
            }
        }
        return false;
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder value = new StringBuilder(digest.length * 2);
            for (byte part : digest) {
                value.append(String.format("%02x", part));
            }
            return value.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    private record ImageMetadata(String mime, int width, int height) { }

    public record StoredAsset(String relativePath, String mime, int width, int height, String sha256) { }
}

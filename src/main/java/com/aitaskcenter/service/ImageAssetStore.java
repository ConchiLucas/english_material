package com.aitaskcenter.service;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ImageAssetStore {
    private static final long MAX_BYTES = 25L * 1024 * 1024;
    private static final int MAX_DIMENSION = 8_192;
    private static final long MAX_PIXELS = 40_000_000L;
    private static final int MAX_RUN_ID_LENGTH = 64;
    private static final int MAX_ASSET_KEY_LENGTH = 120;
    private static final Map<String, String> MIME_TO_EXTENSION = Map.of("image/png", "png", "image/jpeg", "jpg");
    private final Path storageRoot;

    @Autowired
    public ImageAssetStore(@Value("${image-story.storage-root}") String storageRoot) {
        if (storageRoot == null || storageRoot.isBlank()) throw new IllegalArgumentException("图片存储目录不能为空");
        this.storageRoot = Path.of(storageRoot).toAbsolutePath().normalize();
    }

    public StoredAsset store(String runId, String assetKey, String declaredMime, byte[] bytes) {
        validateSegment(runId, MAX_RUN_ID_LENGTH, "runId");
        validateSegment(assetKey, MAX_ASSET_KEY_LENGTH, "assetKey");
        String mime = normalizeMime(declaredMime);
        validateBytes(bytes);
        ImageMetadata image = inspect(bytes);
        if (!mime.equals(image.mime())) throw new IllegalArgumentException("声明的图片 MIME 与实际内容不一致");
        Path temporary = Path.of("." + assetKey + "-" + UUID.randomUUID() + ".tmp");
        Path target = Path.of(assetKey + "." + MIME_TO_EXTENSION.get(mime));
        try (SecureDirectoryStream<Path> root = openSecureRoot(); SecureDirectoryStream<Path> run = openRun(root, runId, true)) {
            if (exists(run, target)) throw duplicate();
            writeAndSync(run, temporary, bytes);
            beforePublish();
            publishWithoutReplace(run, temporary, target);
            return new StoredAsset(runId + "/" + target, mime, image.width(), image.height(), sha256(bytes));
        } catch (IOException ex) {
            throw new IllegalStateException("保存图片资产失败", ex);
        } finally {
            deleteTemporary(runId, temporary);
        }
    }

    public byte[] read(String relativePath, String expectedSha256) {
        Path relative = validateRelativePath(relativePath);
        validateSha256(expectedSha256);
        try (SecureDirectoryStream<Path> root = openSecureRoot();
                SecureDirectoryStream<Path> run = openRun(root, relative.getName(0).toString(), false)) {
            Path fileName = relative.getName(1);
            BasicFileAttributes attributes = run.getFileAttributeView(fileName, BasicFileAttributeView.class,
                    LinkOption.NOFOLLOW_LINKS).readAttributes();
            if (!attributes.isRegularFile() || attributes.size() < 1 || attributes.size() > MAX_BYTES) {
                throw new IllegalArgumentException("图片资产大小或类型不安全");
            }
            try (SeekableByteChannel channel = run.newByteChannel(fileName, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
                long size = channel.size();
                if (size < 1 || size > MAX_BYTES) throw new IllegalArgumentException("图片资产大小不安全");
                byte[] content = readBounded(channel, size);
                if (!sha256(content).equals(expectedSha256)) throw new IllegalArgumentException("图片资产哈希不匹配");
                return content;
            }
        } catch (NoSuchFileException ex) {
            throw new IllegalArgumentException("图片资产不存在", ex);
        } catch (IOException ex) {
            throw new IllegalArgumentException("读取图片资产失败", ex);
        }
    }

    void beforePublish() { }

    private SecureDirectoryStream<Path> openSecureRoot() throws IOException {
        Files.createDirectories(storageRoot);
        if (Files.isSymbolicLink(storageRoot)) throw new IllegalArgumentException("图片存储目录不能是符号链接");
        DirectoryStream<Path> stream = Files.newDirectoryStream(storageRoot);
        if (stream instanceof SecureDirectoryStream<?> secure) {
            @SuppressWarnings("unchecked") SecureDirectoryStream<Path> result = (SecureDirectoryStream<Path>) secure;
            return result;
        }
        stream.close();
        throw new IllegalStateException("当前文件系统不支持安全图片目录操作");
    }

    private SecureDirectoryStream<Path> openRun(SecureDirectoryStream<Path> root, String runId, boolean create) throws IOException {
        Path run = Path.of(runId);
        if (Files.isSymbolicLink(storageRoot.resolve(run))) {
            throw new IllegalArgumentException("图片路径不能通过符号链接离开存储目录");
        }
        try {
            return root.newDirectoryStream(run, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException missing) {
            if (!create) throw missing;
            try { Files.createDirectory(storageRoot.resolve(run)); } catch (FileAlreadyExistsException ignored) { }
            return root.newDirectoryStream(run, LinkOption.NOFOLLOW_LINKS);
        }
    }

    private static boolean exists(SecureDirectoryStream<Path> directory, Path fileName) throws IOException {
        try {
            directory.getFileAttributeView(fileName, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS).readAttributes();
            return true;
        } catch (NoSuchFileException missing) { return false; }
    }

    private static void writeAndSync(SecureDirectoryStream<Path> directory, Path fileName, byte[] bytes) throws IOException {
        try (SeekableByteChannel channel = directory.newByteChannel(fileName,
                Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS))) {
            writeFully(channel, ByteBuffer.wrap(bytes));
            force(channel);
        }
    }

    private static void publishWithoutReplace(SecureDirectoryStream<Path> directory, Path temporary, Path target) throws IOException {
        boolean createdTarget = false;
        try {
            try (SeekableByteChannel source = directory.newByteChannel(temporary,
                    Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
                    SeekableByteChannel destination = directory.newByteChannel(target,
                            Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS))) {
                createdTarget = true;
                ByteBuffer buffer = ByteBuffer.allocate(8192);
                while (source.read(buffer) >= 0) {
                    buffer.flip();
                    writeFully(destination, buffer);
                    buffer.clear();
                }
                force(destination);
            }
        } catch (FileAlreadyExistsException ex) {
            throw duplicate();
        } catch (IOException | RuntimeException ex) {
            if (createdTarget) {
                try { directory.deleteFile(target); } catch (IOException ignored) { }
            }
            throw ex;
        }
    }

    private void deleteTemporary(String runId, Path temporary) {
        try (SecureDirectoryStream<Path> root = openSecureRoot(); SecureDirectoryStream<Path> run = openRun(root, runId, false)) {
            run.deleteFile(temporary);
        } catch (NoSuchFileException ignored) {
        } catch (IOException | IllegalStateException ignored) {
        }
    }

    private static void force(SeekableByteChannel channel) throws IOException {
        if (!(channel instanceof FileChannel fileChannel)) throw new IllegalStateException("当前文件系统不支持安全图片文件同步");
        fileChannel.force(true);
    }

    private static void writeFully(SeekableByteChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) channel.write(buffer);
    }

    private static byte[] readBounded(SeekableByteChannel channel, long size) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream((int) size);
        ByteBuffer buffer = ByteBuffer.allocate(8192);
        while (output.size() < size) {
            int count = channel.read(buffer);
            if (count < 0) break;
            buffer.flip(); output.write(buffer.array(), 0, count); buffer.clear();
        }
        if (output.size() != size) throw new IOException("图片文件读取不完整");
        return output.toByteArray();
    }

    private static void validateSegment(String value, int limit, String fieldName) {
        if (value == null || value.isBlank() || value.length() > limit || value.contains("/") || value.contains("\\")
                || value.contains("..") || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) throw new IllegalArgumentException(fieldName + " 不是安全的存储路径片段");
    }

    private static Path validateRelativePath(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("图片相对路径不能为空");
        Path path = Path.of(value);
        if (path.isAbsolute() || path.getNameCount() != 2) throw new IllegalArgumentException("图片路径必须恰好包含批次和文件名");
        validateSegment(path.getName(0).toString(), MAX_RUN_ID_LENGTH, "runId");
        validateSegment(path.getName(1).toString(), MAX_ASSET_KEY_LENGTH + 5, "fileName");
        return path;
    }

    private static void validateSha256(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("图片哈希格式不安全");
    }

    private static String normalizeMime(String declaredMime) {
        String mime = declaredMime == null ? "" : declaredMime.trim().toLowerCase(Locale.ROOT);
        if (!MIME_TO_EXTENSION.containsKey(mime)) throw new IllegalArgumentException("不支持的图片 MIME 类型");
        return mime;
    }

    private static void validateBytes(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) throw new IllegalArgumentException("图片字节大小不安全");
    }

    private static ImageMetadata inspect(byte[] bytes) {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (input == null) throw new IllegalArgumentException("图片内容无法识别");
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IllegalArgumentException("图片内容无法识别");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > MAX_DIMENSION || height > MAX_DIMENSION || (long) width * height > MAX_PIXELS) throw new IllegalArgumentException("图片尺寸不安全");
                BufferedImage decoded = reader.read(0);
                if (decoded == null) throw new IllegalArgumentException("图片内容无法解码");
                String mime = switch (reader.getFormatName().toLowerCase(Locale.ROOT)) {
                    case "png" -> "image/png";
                    case "jpeg", "jpg" -> "image/jpeg";
                    default -> throw new IllegalArgumentException("不支持的图片内容类型");
                };
                return new ImageMetadata(mime, width, height);
            } finally { reader.dispose(); }
        } catch (IOException ex) { throw new IllegalArgumentException("图片内容无法解码", ex); }
    }

    private static IllegalStateException duplicate() { return new IllegalStateException("图片资产已存在，不能覆盖审计记录"); }
    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder value = new StringBuilder(digest.length * 2);
            for (byte part : digest) value.append(String.format("%02x", part));
            return value.toString();
        } catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 不可用", ex); }
    }
    private record ImageMetadata(String mime, int width, int height) { }
    public record StoredAsset(String relativePath, String mime, int width, int height, String sha256) { }
}

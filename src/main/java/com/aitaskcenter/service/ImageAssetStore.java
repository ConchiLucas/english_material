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
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
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
    private static final ConcurrentMap<Path, ReentrantLock> PORTABLE_ROOT_LOCKS = new ConcurrentHashMap<>();
    private final Path storageRoot;
    private final boolean allowPortableStorage;

    @Autowired
    public ImageAssetStore(
            @Value("${image-story.storage-root}") String storageRoot,
            @Value("${image-story.allow-portable-storage:false}") boolean allowPortableStorage) {
        if (storageRoot == null || storageRoot.isBlank()) throw new IllegalArgumentException("图片存储目录不能为空");
        this.storageRoot = Path.of(storageRoot).toAbsolutePath().normalize();
        this.allowPortableStorage = allowPortableStorage;
    }

    public ImageAssetStore(String storageRoot) {
        this(storageRoot, false);
    }

    public void assertWritable() {
        if (usePortableStorage()) {
            assertPortableWritable();
            return;
        }
        Path probe = Path.of(".readiness-" + UUID.randomUUID() + ".tmp");
        SecureDirectoryStream<Path> root = null;
        boolean created = false;
        Exception failure = null;
        try {
            root = openSecureRoot();
            try (SeekableByteChannel channel = root.newByteChannel(probe,
                    Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS))) {
                created = true;
                writeFully(channel, ByteBuffer.wrap(new byte[] {0}));
                force(channel);
            }
            root.deleteFile(probe);
            created = false;
        } catch (Exception exception) {
            failure = exception;
        } finally {
            if (created && root != null) {
                try {
                    root.deleteFile(probe);
                } catch (Exception cleanupFailure) {
                    if (failure == null) failure = cleanupFailure;
                    else failure.addSuppressed(cleanupFailure);
                }
            }
            if (root != null) {
                try {
                    root.close();
                } catch (Exception closeFailure) {
                    if (failure == null) failure = closeFailure;
                    else failure.addSuppressed(closeFailure);
                }
            }
        }
        if (failure != null) throw new IllegalStateException("图片存储目录不可写", failure);
    }

    public StoredAsset store(String runId, String assetKey, String declaredMime, byte[] bytes) {
        validateSegment(runId, MAX_RUN_ID_LENGTH, "runId");
        validateSegment(assetKey, MAX_ASSET_KEY_LENGTH, "assetKey");
        String mime = normalizeMime(declaredMime);
        validateBytes(bytes);
        ImageMetadata image = inspect(bytes);
        if (!mime.equals(image.mime())) throw new IllegalArgumentException("声明的图片 MIME 与实际内容不一致");
        if (usePortableStorage()) return storePortable(runId, assetKey, mime, bytes, image);
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
        if (usePortableStorage()) return readPortable(relative, expectedSha256);
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

    public void delete(String relativePath, String expectedSha256) {
        Path relative = validateRelativePath(relativePath);
        validateSha256(expectedSha256);
        if (usePortableStorage()) {
            deletePortable(relative, expectedSha256);
            return;
        }
        try (SecureDirectoryStream<Path> root = openSecureRoot();
                SecureDirectoryStream<Path> run = openRun(root, relative.getName(0).toString(), false)) {
            Path fileName = relative.getName(1);
            BasicFileAttributes attributes = run.getFileAttributeView(fileName, BasicFileAttributeView.class,
                    LinkOption.NOFOLLOW_LINKS).readAttributes();
            if (!attributes.isRegularFile() || attributes.size() < 1 || attributes.size() > MAX_BYTES) {
                throw new IllegalArgumentException("图片资产大小或类型不安全");
            }
            byte[] content;
            try (SeekableByteChannel channel = run.newByteChannel(fileName,
                    Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
                long size = channel.size();
                if (size < 1 || size > MAX_BYTES) throw new IllegalArgumentException("图片资产大小不安全");
                content = readBounded(channel, size);
            }
            if (!sha256(content).equals(expectedSha256)) {
                throw new IllegalArgumentException("图片资产哈希不匹配，拒绝删除");
            }
            run.deleteFile(fileName);
        } catch (NoSuchFileException ex) {
            throw new IllegalArgumentException("图片资产不存在", ex);
        } catch (IOException ex) {
            throw new IllegalArgumentException("删除图片资产失败", ex);
        }
    }

    void beforePublish() { }

    private boolean usePortableStorage() {
        if (!allowPortableStorage) return false;
        Path filesystemRoot = storageRoot.getRoot();
        if (filesystemRoot == null) return false;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(filesystemRoot)) {
            return !(stream instanceof SecureDirectoryStream<?>);
        } catch (IOException ex) {
            throw new IllegalStateException("无法确认图片存储文件系统能力", ex);
        }
    }

    private void assertPortableWritable() {
        withPortableLock(() -> {
            Path root = validatePortableRoot();
            Path probe = root.resolve(".readiness-" + UUID.randomUUID() + ".tmp");
            boolean created = false;
            try {
                try (SeekableByteChannel channel = Files.newByteChannel(probe,
                        Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS))) {
                    created = true;
                    writeFully(channel, ByteBuffer.wrap(new byte[] {0}));
                    force(channel);
                }
                validatePortableRoot();
                Files.delete(probe);
                created = false;
                return null;
            } finally {
                if (created) Files.deleteIfExists(probe);
            }
        }, "图片存储目录不可写");
    }

    private StoredAsset storePortable(
            String runId, String assetKey, String mime, byte[] bytes, ImageMetadata image) {
        return withPortableLock(() -> {
            Path run = openPortableRun(runId, true);
            Path temporary = run.resolve("." + assetKey + "-" + UUID.randomUUID() + ".tmp");
            Path target = run.resolve(assetKey + "." + MIME_TO_EXTENSION.get(mime));
            try {
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw duplicate();
                try (SeekableByteChannel channel = Files.newByteChannel(temporary,
                        Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS))) {
                    writeFully(channel, ByteBuffer.wrap(bytes));
                    force(channel);
                }
                beforePublish();
                validatePortableRun(runId, run);
                try {
                    Files.createLink(target, temporary);
                } catch (FileAlreadyExistsException ex) {
                    throw duplicate();
                }
                return new StoredAsset(runId + "/" + target.getFileName(), mime,
                        image.width(), image.height(), sha256(bytes));
            } finally {
                validatePortableRun(runId, run);
                Files.deleteIfExists(temporary);
            }
        }, "保存图片资产失败");
    }

    private byte[] readPortable(Path relative, String expectedSha256) {
        return withPortableLock(() -> {
            Path run = openPortableRun(relative.getName(0).toString(), false);
            Path file = run.resolve(relative.getName(1).toString());
            validatePortableAsset(file);
            try (SeekableByteChannel channel = Files.newByteChannel(file,
                    Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
                long size = channel.size();
                if (size < 1 || size > MAX_BYTES) throw new IllegalArgumentException("图片资产大小不安全");
                byte[] content = readBounded(channel, size);
                if (!sha256(content).equals(expectedSha256)) throw new IllegalArgumentException("图片资产哈希不匹配");
                return content;
            }
        }, "读取图片资产失败");
    }

    private void deletePortable(Path relative, String expectedSha256) {
        withPortableLock(() -> {
            String runId = relative.getName(0).toString();
            Path run = openPortableRun(runId, false);
            Path file = run.resolve(relative.getName(1).toString());
            BasicFileAttributes before = validatePortableAsset(file);
            byte[] content;
            try (SeekableByteChannel channel = Files.newByteChannel(file,
                    Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
                long size = channel.size();
                if (size < 1 || size > MAX_BYTES) throw new IllegalArgumentException("图片资产大小不安全");
                content = readBounded(channel, size);
            }
            if (!sha256(content).equals(expectedSha256)) {
                throw new IllegalArgumentException("图片资产哈希不匹配，拒绝删除");
            }
            validatePortableRun(runId, run);
            BasicFileAttributes after = validatePortableAsset(file);
            if (before.size() != after.size() || !Objects.equals(before.fileKey(), after.fileKey())) {
                throw new IllegalArgumentException("图片资产在删除前已发生变化");
            }
            Files.delete(file);
            return null;
        }, "删除图片资产失败");
    }

    private Path openPortableRun(String runId, boolean create) throws IOException {
        Path root = validatePortableRoot();
        Path run = root.resolve(runId).normalize();
        if (!root.equals(run.getParent())) throw new IllegalArgumentException("图片路径不能离开存储目录");
        if (!Files.exists(run, LinkOption.NOFOLLOW_LINKS)) {
            if (!create) throw new NoSuchFileException(run.toString());
            try {
                Files.createDirectory(run);
            } catch (FileAlreadyExistsException ignored) { }
            root = validatePortableRoot();
            run = root.resolve(runId).normalize();
            if (!root.equals(run.getParent())) throw new IllegalArgumentException("图片路径不能离开存储目录");
        }
        validatePortableRun(runId, run);
        return run;
    }

    private void validatePortableRun(String runId, Path run) throws IOException {
        Path root = validatePortableRoot();
        Path expected = root.resolve(runId).normalize();
        if (!expected.equals(run) || !root.equals(expected.getParent())) {
            throw new IllegalArgumentException("图片路径不能离开存储目录");
        }
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(expected, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException missing) {
            throw missing;
        }
        if (attributes.isSymbolicLink() || !attributes.isDirectory()) {
            throw new IllegalArgumentException("图片路径不能通过符号链接离开存储目录");
        }
    }

    private BasicFileAttributes validatePortableAsset(Path file) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes.isSymbolicLink() || !attributes.isRegularFile()
                || attributes.size() < 1 || attributes.size() > MAX_BYTES) {
            throw new IllegalArgumentException("图片资产大小或类型不安全");
        }
        return attributes;
    }

    private Path validatePortableRoot() throws IOException {
        if (!storageRoot.isAbsolute() || !storageRoot.equals(storageRoot.normalize())) {
            throw new IllegalArgumentException("图片存储目录必须是规范绝对路径");
        }
        Path current = storageRoot.getRoot();
        if (current == null) throw new IllegalStateException("图片存储目录没有文件系统根目录");
        validatePortableDirectory(current, false);
        for (Path segment : current.relativize(storageRoot)) {
            current = current.resolve(segment);
            validatePortableDirectory(current, current.equals(storageRoot));
        }
        return current;
    }

    private static void validatePortableDirectory(Path directory, boolean configuredRoot) throws IOException {
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException missing) {
            if (configuredRoot) throw new IllegalStateException("图片存储目录不存在", missing);
            throw missing;
        }
        if (attributes.isSymbolicLink() || !attributes.isDirectory()) {
            throw new IllegalArgumentException("图片存储目录不能包含符号链接或非目录");
        }
    }

    private <T> T withPortableLock(IoSupplier<T> operation, String failureMessage) {
        ReentrantLock lock = PORTABLE_ROOT_LOCKS.computeIfAbsent(storageRoot, ignored -> new ReentrantLock());
        lock.lock();
        try {
            return operation.get();
        } catch (NoSuchFileException ex) {
            throw new IllegalArgumentException("图片资产不存在", ex);
        } catch (IllegalArgumentException | IllegalStateException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new IllegalStateException(failureMessage, ex);
        } finally {
            lock.unlock();
        }
    }

    @FunctionalInterface
    private interface IoSupplier<T> {
        T get() throws IOException;
    }

    private SecureDirectoryStream<Path> openSecureRoot() throws IOException {
        Path filesystemRoot = storageRoot.getRoot();
        if (filesystemRoot == null) throw new IllegalStateException("图片存储目录没有文件系统根目录");
        DirectoryStream<Path> stream = Files.newDirectoryStream(filesystemRoot);
        if (!(stream instanceof SecureDirectoryStream<?> secure)) {
            stream.close();
            throw new IllegalStateException("当前文件系统不支持安全图片目录操作");
        }
        @SuppressWarnings("unchecked") SecureDirectoryStream<Path> current = (SecureDirectoryStream<Path>) secure;
        try {
            for (Path segment : filesystemRoot.relativize(storageRoot)) {
                SecureDirectoryStream<Path> next;
                try {
                    next = current.newDirectoryStream(segment, LinkOption.NOFOLLOW_LINKS);
                } catch (NoSuchFileException missing) {
                    throw new IllegalStateException("图片存储目录不存在", missing);
                } catch (IOException unsafe) {
                    throw new IllegalArgumentException("图片存储目录不能包含符号链接或非目录", unsafe);
                }
                current.close();
                current = next;
            }
            return current;
        } catch (IOException | RuntimeException ex) {
            try {
                current.close();
            } catch (IOException closeFailure) {
                ex.addSuppressed(closeFailure);
            }
            throw ex;
        }
    }

    private SecureDirectoryStream<Path> openRun(SecureDirectoryStream<Path> root, String runId, boolean create) throws IOException {
        Path run = Path.of(runId);
        try {
            return root.newDirectoryStream(run, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException missing) {
            if (!create) throw missing;
            try { Files.createDirectory(storageRoot.resolve(run)); } catch (FileAlreadyExistsException ignored) { }
            try {
                return root.newDirectoryStream(run, LinkOption.NOFOLLOW_LINKS);
            } catch (IOException unsafe) {
                throw new IllegalArgumentException("图片路径不能通过符号链接离开存储目录", unsafe);
            }
        } catch (IOException unsafe) {
            throw new IllegalArgumentException("图片路径不能通过符号链接离开存储目录", unsafe);
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

package com.aitaskcenter.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.context.support.TestPropertySourceUtils;

class ImageAssetStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void springContextUsesTheConfiguredProductionConstructor() throws Exception {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            Path storageRoot = Files.createDirectory(testRoot().resolve("spring-storage"));
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(
                    context,
                    "image-story.storage-root=" + storageRoot,
                    "image-story.allow-portable-storage=true");
            context.register(ImageAssetStore.class);
            context.refresh();

            ImageAssetStore store = context.getBean(ImageAssetStore.class);
            assertNotNull(store);
            store.assertWritable();
        }
    }

    @Test
    void strictModeRejectsFilesystemsWithoutSecureDirectoryStreams() throws Exception {
        assumeTrue(!supportsSecureDirectoryStreams(), "当前文件系统支持 SecureDirectoryStream");
        ImageAssetStore store = new ImageAssetStore(storageRoot().toString(), false);

        IllegalStateException error = assertThrows(IllegalStateException.class, store::assertWritable);

        assertTrue(error.getCause().getMessage().contains("不支持安全图片目录操作"));
    }

    @Test
    void verifiesWritableStorageAndLeavesNoProbeFile() throws Exception {
        Path storageRoot = storageRoot();
        ImageAssetStore store = portableStore(storageRoot);

        store.assertWritable();
        store.assertWritable();

        try (var files = Files.list(storageRoot)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void storesAndReadsPngAtDeterministicRelativePathWithVerifiedMetadata() throws Exception {
        byte[] png = png(16, 9);
        ImageAssetStore store = portableStore(storageRoot());

        ImageAssetStore.StoredAsset stored = store.store("run-123", "shot-001", "image/png", png);

        assertEquals("run-123/shot-001.png", stored.relativePath());
        assertEquals("image/png", stored.mime());
        assertEquals(16, stored.width());
        assertEquals(9, stored.height());
        assertEquals(sha256(png), stored.sha256());
        assertArrayEquals(png, store.read(stored.relativePath(), stored.sha256()));
    }

    @Test
    void rejectsUnsafeSegmentsAndDatabasePathsOutsideStorageRoot() throws Exception {
        ImageAssetStore store = portableStore(storageRoot());
        byte[] png = png(2, 2);

        assertThrows(IllegalArgumentException.class, () -> store.store("../run", "asset", "image/png", png));
        assertThrows(IllegalArgumentException.class, () -> store.store("run", "a/b", "image/png", png));
        assertThrows(IllegalArgumentException.class, () -> store.store("run", "asset..copy", "image/png", png));
        assertThrows(IllegalArgumentException.class, () -> store.read("../secret.png", sha256(png)));
        assertThrows(IllegalArgumentException.class, () -> store.read(tempDir.resolve("secret.png").toString(), sha256(png)));
    }

    @Test
    void rejectsDeclaredMimeThatDoesNotMatchDecodedImage() throws Exception {
        ImageAssetStore store = portableStore(storageRoot());

        assertThrows(IllegalArgumentException.class,
                () -> store.store("run-123", "shot-001", "image/jpeg", png(3, 2)));
        assertThrows(IllegalArgumentException.class,
                () -> store.store("run-123", "shot-002", "image/webp", png(3, 2)));
    }

    @Test
    void rejectsRunIdsLongerThanThePersistedColumn() throws Exception {
        ImageAssetStore store = portableStore(storageRoot());

        assertThrows(IllegalArgumentException.class,
                () -> store.store("r".repeat(65), "shot-001", "image/png", png(3, 2)));
    }

    @Test
    void neverOverwritesAnExistingAuditAssetAndCleansTemporaryFiles() throws Exception {
        Path storageRoot = storageRoot();
        ImageAssetStore store = portableStore(storageRoot);
        byte[] original = png(4, 3);
        ImageAssetStore.StoredAsset stored = store.store("run-123", "shot-001", "image/png", original);

        assertThrows(IllegalStateException.class,
                () -> store.store("run-123", "shot-001", "image/png", png(5, 3)));

        assertArrayEquals(original, store.read(stored.relativePath(), stored.sha256()));
        try (var files = Files.list(storageRoot.resolve("run-123"))) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().contains(".tmp")));
        }
    }

    @Test
    void deletesOnlyTheExactStoredAssetWhenPathAndHashBothMatch() throws Exception {
        ImageAssetStore store = portableStore(storageRoot());
        byte[] original = png(4, 3);
        ImageAssetStore.StoredAsset stored = store.store("run-123", "shot-001", "image/png", original);

        assertThrows(IllegalArgumentException.class,
                () -> store.delete(stored.relativePath(), "b".repeat(64)));
        assertArrayEquals(original, store.read(stored.relativePath(), stored.sha256()));

        store.delete(stored.relativePath(), stored.sha256());

        assertThrows(IllegalArgumentException.class,
                () -> store.read(stored.relativePath(), stored.sha256()));
    }

    @Test
    void publishesOnlyOneCompleteAssetWhenConcurrentWritersUseTheSameKey() throws Exception {
        byte[] first = png(4, 3);
        byte[] second = png(5, 3);
        CyclicBarrier startTogether = new CyclicBarrier(2);
        Path storageRoot = storageRoot();
        ImageAssetStore store = portableStore(storageRoot);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            List<Future<StoreAttempt>> attempts = workers.invokeAll(List.of(
                    concurrentStore(store, first, startTogether), concurrentStore(store, second, startTogether)));
            List<StoreAttempt> results = attempts.stream().map(this::await).toList();

            assertEquals(1, results.stream().filter(StoreAttempt::succeeded).count());
            StoreAttempt rejected = results.stream().filter(attempt -> !attempt.succeeded()).findFirst().orElseThrow();
            assertTrue(rejected.error() instanceof IllegalStateException);
            assertEquals("图片资产已存在，不能覆盖审计记录", rejected.error().getMessage());
            StoreAttempt winner = results.stream().filter(StoreAttempt::succeeded).findFirst().orElseThrow();
            byte[] published = store.read("run-123/shot-001.png", winner.stored().sha256());
            assertTrue(java.util.Arrays.equals(first, published) || java.util.Arrays.equals(second, published));
            assertTrue(ImageIO.read(new java.io.ByteArrayInputStream(published)) != null);
            try (var files = Files.list(storageRoot.resolve("run-123"))) {
                assertFalse(files.anyMatch(path -> path.getFileName().toString().contains(".tmp")));
            }
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void rejectsStorageDirectoriesThatResolveThroughASymlinkOutsideRoot() throws Exception {
        Path root = Files.createDirectory(testRoot().resolve("storage"));
        Path outside = Files.createDirectory(testRoot().resolve("outside"));
        Path link = root.resolve("run-123");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException | SecurityException ex) {
            assumeTrue(false, "symbolic links are unavailable on this platform");
        }
        ImageAssetStore store = portableStore(root);

        assertThrows(IllegalArgumentException.class,
                () -> store.store("run-123", "shot-001", "image/png", png(4, 3)));
    }

    @Test
    void rejectsSymlinkAssetsWithoutReadingOrDeletingTheirTargets() throws Exception {
        Path root = storageRoot();
        Path run = Files.createDirectory(root.resolve("run-123"));
        Path outside = testRoot().resolve("outside.png");
        byte[] content = png(4, 3);
        Files.write(outside, content);
        Path link = run.resolve("shot-001.png");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException | SecurityException ex) {
            assumeTrue(false, "symbolic links are unavailable on this platform");
        }
        ImageAssetStore store = portableStore(root);

        assertThrows(IllegalArgumentException.class, () -> store.read("run-123/shot-001.png", sha256(content)));
        assertThrows(IllegalArgumentException.class, () -> store.delete("run-123/shot-001.png", sha256(content)));
        assertArrayEquals(content, Files.readAllBytes(outside));
    }

    @Test
    void rejectsStorageRootThatIsASymlinkWithoutWritingOutsideIt() throws Exception {
        Path outside = Files.createDirectory(testRoot().resolve("outside"));
        Path link = testRoot().resolve("storage-link");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException | SecurityException ex) {
            assumeTrue(false, "symbolic links are unavailable on this platform");
        }
        ImageAssetStore store = portableStore(link);

        assertThrows(IllegalArgumentException.class,
                () -> store.store("run-123", "shot-001", "image/png", png(4, 3)));
        assertFalse(Files.exists(outside.resolve("run-123")));
    }

    @Test
    void rejectsStorageRootWithAnIntermediateSymlinkWithoutWritingOutsideIt() throws Exception {
        Path outside = Files.createDirectory(testRoot().resolve("outside"));
        Files.createDirectory(outside.resolve("image-story"));
        Path link = testRoot().resolve("storage-parent-link");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException | SecurityException ex) {
            assumeTrue(false, "symbolic links are unavailable on this platform");
        }
        ImageAssetStore store = portableStore(link.resolve("image-story"));

        assertThrows(IllegalArgumentException.class,
                () -> store.store("run-123", "shot-001", "image/png", png(4, 3)));
        assertFalse(Files.exists(outside.resolve("image-story/run-123")));
    }

    @Test
    void rejectsAMissingConfiguredStorageRoot() throws Exception {
        ImageAssetStore store = portableStore(testRoot().resolve("missing-storage"));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> store.store("run-123", "shot-001", "image/png", png(4, 3)));

        assertTrue(error.getMessage().contains("图片存储目录不存在"));
    }

    @Test
    void rejectsTamperedBytesAndOversizedFilesAgainstThePersistedHash() throws Exception {
        Path storageRoot = storageRoot();
        ImageAssetStore store = portableStore(storageRoot);
        ImageAssetStore.StoredAsset stored = store.store("run-123", "shot-001", "image/png", png(4, 3));

        Files.write(storageRoot.resolve(stored.relativePath()), png(5, 3));
        assertThrows(IllegalArgumentException.class, () -> store.read(stored.relativePath(), stored.sha256()));
        Files.write(storageRoot.resolve("run-123/oversized.png"), new byte[26 * 1024 * 1024]);
        assertThrows(IllegalArgumentException.class,
                () -> store.read("run-123/oversized.png", "a".repeat(64)));
    }

    private static byte[] png(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0xff3f51b5);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "png", output));
        return output.toByteArray();
    }

    private Path storageRoot() throws IOException {
        return Files.createDirectory(testRoot().resolve("storage"));
    }

    private Path testRoot() throws IOException {
        return tempDir.toRealPath();
    }

    private static ImageAssetStore portableStore(Path storageRoot) {
        return new ImageAssetStore(storageRoot.toString(), true);
    }

    private static boolean supportsSecureDirectoryStreams() throws IOException {
        try (var stream = Files.newDirectoryStream(Path.of("/"))) {
            return stream instanceof SecureDirectoryStream<?>;
        }
    }

    private static Callable<StoreAttempt> concurrentStore(
            ImageAssetStore store, byte[] bytes, CyclicBarrier startTogether) {
        return () -> {
            try {
                awaitBarrier(startTogether);
                return new StoreAttempt(store.store("run-123", "shot-001", "image/png", bytes), null);
            } catch (Exception ex) {
                return new StoreAttempt(null, ex);
            }
        };
    }

    private static void awaitBarrier(CyclicBarrier barrier) {
        try {
            barrier.await();
        } catch (Exception ex) {
            throw new IllegalStateException("测试发布栅栏失败", ex);
        }
    }

    private StoreAttempt await(Future<StoreAttempt> future) {
        try {
            return future.get();
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder value = new StringBuilder(digest.length * 2);
        for (byte part : digest) {
            value.append(String.format("%02x", part));
        }
        return value.toString();
    }

    private record StoreAttempt(ImageAssetStore.StoredAsset stored, Exception error) {
        boolean succeeded() {
            return stored != null;
        }
    }
}

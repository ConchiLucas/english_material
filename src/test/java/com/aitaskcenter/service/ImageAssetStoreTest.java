package com.aitaskcenter.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

class ImageAssetStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void storesAndReadsPngAtDeterministicRelativePathWithVerifiedMetadata() throws Exception {
        byte[] png = png(16, 9);
        ImageAssetStore store = new ImageAssetStore(tempDir.toString());

        ImageAssetStore.StoredAsset stored = store.store("run-123", "shot-001", "image/png", png);

        assertEquals("run-123/shot-001.png", stored.relativePath());
        assertEquals("image/png", stored.mime());
        assertEquals(16, stored.width());
        assertEquals(9, stored.height());
        assertEquals(sha256(png), stored.sha256());
        assertArrayEquals(png, store.read(stored.relativePath()));
    }

    @Test
    void rejectsUnsafeSegmentsAndDatabasePathsOutsideStorageRoot() throws Exception {
        ImageAssetStore store = new ImageAssetStore(tempDir.toString());
        byte[] png = png(2, 2);

        assertThrows(IllegalArgumentException.class, () -> store.store("../run", "asset", "image/png", png));
        assertThrows(IllegalArgumentException.class, () -> store.store("run", "a/b", "image/png", png));
        assertThrows(IllegalArgumentException.class, () -> store.store("run", "asset..copy", "image/png", png));
        assertThrows(IllegalArgumentException.class, () -> store.read("../secret.png"));
        assertThrows(IllegalArgumentException.class, () -> store.read(tempDir.resolve("secret.png").toString()));
    }

    @Test
    void rejectsDeclaredMimeThatDoesNotMatchDecodedImage() throws Exception {
        ImageAssetStore store = new ImageAssetStore(tempDir.toString());

        assertThrows(IllegalArgumentException.class,
                () -> store.store("run-123", "shot-001", "image/jpeg", png(3, 2)));
    }

    @Test
    void neverOverwritesAnExistingAuditAssetAndCleansTemporaryFiles() throws Exception {
        ImageAssetStore store = new ImageAssetStore(tempDir.toString());
        byte[] original = png(4, 3);
        ImageAssetStore.StoredAsset stored = store.store("run-123", "shot-001", "image/png", original);

        assertThrows(IllegalStateException.class,
                () -> store.store("run-123", "shot-001", "image/png", png(5, 3)));

        assertArrayEquals(original, store.read(stored.relativePath()));
        try (var files = Files.list(tempDir.resolve("run-123"))) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().contains(".tmp")));
        }
    }

    @Test
    void publishesOnlyOneCompleteAssetWhenConcurrentWritersUseTheSameKey() throws Exception {
        ImageAssetStore store = new ImageAssetStore(tempDir.toString());
        byte[] first = png(4, 3);
        byte[] second = png(5, 3);
        CyclicBarrier start = new CyclicBarrier(2);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            List<Future<StoreAttempt>> attempts = workers.invokeAll(List.of(
                    concurrentStore(store, start, first), concurrentStore(store, start, second)));
            List<StoreAttempt> results = attempts.stream().map(this::await).toList();

            assertEquals(1, results.stream().filter(StoreAttempt::succeeded).count());
            StoreAttempt rejected = results.stream().filter(attempt -> !attempt.succeeded()).findFirst().orElseThrow();
            assertTrue(rejected.error() instanceof IllegalStateException);
            assertEquals("图片资产已存在，不能覆盖审计记录", rejected.error().getMessage());
            byte[] published = store.read("run-123/shot-001.png");
            assertTrue(java.util.Arrays.equals(first, published) || java.util.Arrays.equals(second, published));
            assertTrue(ImageIO.read(new java.io.ByteArrayInputStream(published)) != null);
            try (var files = Files.list(tempDir.resolve("run-123"))) {
                assertFalse(files.anyMatch(path -> path.getFileName().toString().contains(".tmp")));
            }
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void rejectsStorageDirectoriesThatResolveThroughASymlinkOutsideRoot() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("storage"));
        Path outside = Files.createDirectory(tempDir.resolve("outside"));
        Path link = root.resolve("run-123");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException | SecurityException ex) {
            assumeTrue(false, "symbolic links are unavailable on this platform");
        }
        ImageAssetStore store = new ImageAssetStore(root.toString());

        assertThrows(IllegalArgumentException.class,
                () -> store.store("run-123", "shot-001", "image/png", png(4, 3)));
    }

    private static byte[] png(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0xff3f51b5);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "png", output));
        return output.toByteArray();
    }

    private static Callable<StoreAttempt> concurrentStore(
            ImageAssetStore store, CyclicBarrier start, byte[] bytes) {
        return () -> {
            start.await();
            try {
                return new StoreAttempt(store.store("run-123", "shot-001", "image/png", bytes), null);
            } catch (Exception ex) {
                return new StoreAttempt(null, ex);
            }
        };
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

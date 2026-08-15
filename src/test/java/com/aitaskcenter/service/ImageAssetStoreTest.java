package com.aitaskcenter.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ImageAssetStoreTest {
    private static final String SECRET = "asset-store-secret-never-leak";
    private static final String ENDPOINT = "private-minio.internal:9000";
    private static final MinioStorageConfig CONFIG = new MinioStorageConfig(
            true, ENDPOINT, "english-app", SECRET, false,
            "english-material", "image-story");

    private MinioConfigService configService;
    private MinioConnectionVerifier verifier;
    private MinioClientFactory factory;
    private MinioClientFactory.Client client;
    private ImageAssetStore store;

    @BeforeEach
    void setUp() {
        configService = mock(MinioConfigService.class);
        verifier = mock(MinioConnectionVerifier.class);
        factory = mock(MinioClientFactory.class);
        client = mock(MinioClientFactory.Client.class);
        when(configService.requireEnabled()).thenReturn(CONFIG);
        when(factory.create(CONFIG)).thenReturn(client);
        store = new ImageAssetStore(configService, verifier, factory);
    }

    @Test
    void verifiesConfiguredMinioStorageBeforeStartingARun() {
        store.assertWritable();

        verify(verifier).verify(CONFIG);
    }

    @Test
    void storesAndReadsPngAtDeterministicRelativePathWithVerifiedMetadata() throws Exception {
        byte[] png = png(16, 9);
        when(client.statObject("english-material", "image-story/run-123/shot-001.png"))
                .thenReturn(new MinioClientFactory.ObjectMetadata(png.length, "image/png"));
        when(client.getObject("english-material", "image-story/run-123/shot-001.png"))
                .thenReturn(new ByteArrayInputStream(png));

        ImageAssetStore.StoredAsset stored = store.store("run-123", "shot-001", "image/png", png);

        assertEquals("english-material/image-story/run-123/shot-001.png", stored.relativePath());
        assertEquals("image/png", stored.mime());
        assertEquals(16, stored.width());
        assertEquals(9, stored.height());
        assertEquals(sha256(png), stored.sha256());
        verify(client).putObject("english-material", "image-story/run-123/shot-001.png", png,
                "image/png", true);
        assertArrayEquals(png, store.read(stored.relativePath(), stored.sha256()));
    }

    @Test
    void readsFromThePersistedBucketAndObjectKeyAfterTheDefaultLocationChanges() throws Exception {
        byte[] png = png(16, 9);
        MinioStorageConfig changed = new MinioStorageConfig(
                true, ENDPOINT, "english-app", SECRET, false,
                "other-bucket", "other-prefix");
        MinioClientFactory.Client changedClient = mock(MinioClientFactory.Client.class);
        when(configService.requireEnabled()).thenReturn(CONFIG, changed);
        when(factory.create(changed)).thenReturn(changedClient);
        when(client.statObject("english-material", "image-story/run-123/shot-001.png"))
                .thenReturn(new MinioClientFactory.ObjectMetadata(png.length, "image/png"));
        when(changedClient.getObject("english-material", "image-story/run-123/shot-001.png"))
                .thenReturn(new ByteArrayInputStream(png));

        ImageAssetStore.StoredAsset stored = store.store("run-123", "shot-001", "image/png", png);

        assertArrayEquals(png, store.read(stored.relativePath(), stored.sha256()));
        verify(changedClient).getObject("english-material", "image-story/run-123/shot-001.png");
        verify(changedClient, never()).getObject("other-bucket", "other-prefix/run-123/shot-001.png");
    }

    @Test
    void removesAnUploadedObjectWhenPostUploadMetadataVerificationFails() throws Exception {
        byte[] png = png(4, 3);
        String key = "image-story/run-123/shot-001.png";
        when(client.statObject("english-material", key))
                .thenReturn(new MinioClientFactory.ObjectMetadata(png.length + 1L, "image/png"));
        when(client.getObject("english-material", key)).thenReturn(new ByteArrayInputStream(png));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> store.store("run-123", "shot-001", "image/png", png));

        assertEquals("保存图片资产失败", error.getMessage());
        verify(client).removeObject("english-material", key);
    }

    @Test
    void neverDeletesAReplacedObjectDuringPostUploadCompensation() throws Exception {
        byte[] uploaded = png(4, 3);
        byte[] replacement = png(5, 3);
        String key = "image-story/run-123/shot-001.png";
        when(client.statObject("english-material", key))
                .thenReturn(new MinioClientFactory.ObjectMetadata(uploaded.length + 1L, "image/png"));
        when(client.getObject("english-material", key)).thenReturn(new ByteArrayInputStream(replacement));

        assertThrows(IllegalStateException.class,
                () -> store.store("run-123", "shot-001", "image/png", uploaded));

        verify(client, never()).removeObject("english-material", key);
    }

    @Test
    void rejectsUnsafeKeysBeforeCallingMinio() throws Exception {
        byte[] png = png(2, 2);

        assertThrows(IllegalArgumentException.class, () -> store.store("../run", "asset", "image/png", png));
        assertThrows(IllegalArgumentException.class, () -> store.store("run", "a/b", "image/png", png));
        assertThrows(IllegalArgumentException.class, () -> store.store("run", "asset..copy", "image/png", png));
        assertThrows(IllegalArgumentException.class, () -> store.read("../secret.png", sha256(png)));
        assertThrows(IllegalArgumentException.class, () -> store.read("run/asset.png/extra", sha256(png)));
    }

    @Test
    void rejectsDeclaredMimeThatDoesNotMatchDecodedImage() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> store.store("run-123", "shot-001", "image/jpeg", png(3, 2)));
        assertThrows(IllegalArgumentException.class,
                () -> store.store("run-123", "shot-002", "image/webp", png(3, 2)));
    }

    @Test
    void usesAtomicCreateOnlyAndNeverOverwritesAnExistingAuditAsset() throws Exception {
        byte[] png = png(4, 3);
        doThrow(new MinioClientFactory.ObjectAlreadyExistsException()).when(client)
                .putObject(eq("english-material"), eq("image-story/run-123/shot-001.png"),
                        eq(png), eq("image/png"), eq(true));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> store.store("run-123", "shot-001", "image/png", png));

        assertEquals("图片资产已存在，不能覆盖审计记录", error.getMessage());
    }

    @Test
    void rejectsTamperedOrOversizedObjectsAgainstPersistedHash() throws Exception {
        byte[] original = png(4, 3);
        when(client.getObject("english-material", "image-story/run-123/shot-001.png"))
                .thenReturn(new ByteArrayInputStream(png(5, 3)));
        assertThrows(IllegalArgumentException.class,
                () -> store.read("english-material/image-story/run-123/shot-001.png", sha256(original)));

        when(client.getObject("english-material", "image-story/run-123/oversized.png"))
                .thenReturn(new RepeatingInputStream(26L * 1024 * 1024));
        assertThrows(IllegalArgumentException.class,
                () -> store.read("english-material/image-story/run-123/oversized.png", "a".repeat(64)));
    }

    @Test
    void deletesOnlyAfterReadingAndMatchingTheExactStoredHash() throws Exception {
        byte[] png = png(4, 3);
        String key = "image-story/run-123/shot-001.png";
        when(client.getObject("english-material", key))
                .thenReturn(new ByteArrayInputStream(png), new ByteArrayInputStream(png));

        assertThrows(IllegalArgumentException.class,
                () -> store.delete("english-material/image-story/run-123/shot-001.png", "b".repeat(64)));
        store.delete("english-material/image-story/run-123/shot-001.png", sha256(png));

        verify(client).removeObject("english-material", key);
    }

    @Test
    void returnsBoundedErrorsWithoutEndpointOrSecret() throws Exception {
        byte[] png = png(4, 3);
        when(client.getObject("english-material", "image-story/run-123/shot-001.png"))
                .thenThrow(new IOException("connect " + ENDPOINT + " using " + SECRET));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> store.read("english-material/image-story/run-123/shot-001.png", sha256(png)));

        assertEquals("读取图片资产失败", error.getMessage());
        assertFalse(error.getMessage().contains(ENDPOINT));
        assertFalse(error.getMessage().contains(SECRET));
    }

    @Test
    void publishesOnlyOneCompleteAssetWhenConcurrentWritersUseTheSameKey() throws Exception {
        AtomicFakeClient atomicClient = new AtomicFakeClient();
        when(factory.create(CONFIG)).thenReturn(atomicClient);
        ImageAssetStore concurrentStore = new ImageAssetStore(configService, verifier, factory);
        byte[] first = png(4, 3);
        byte[] second = png(5, 3);
        CyclicBarrier startTogether = new CyclicBarrier(2);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            List<Future<StoreAttempt>> attempts = workers.invokeAll(List.of(
                    concurrentStore(concurrentStore, first, startTogether),
                    concurrentStore(concurrentStore, second, startTogether)));
            List<StoreAttempt> results = attempts.stream().map(this::await).toList();

            assertEquals(1, results.stream().filter(StoreAttempt::succeeded).count());
            StoreAttempt rejected = results.stream().filter(result -> !result.succeeded()).findFirst().orElseThrow();
            assertEquals("图片资产已存在，不能覆盖审计记录", rejected.error().getMessage());
            StoreAttempt winner = results.stream().filter(StoreAttempt::succeeded).findFirst().orElseThrow();
            assertArrayEquals(atomicClient.bytes("image-story/run-123/shot-001.png"),
                    concurrentStore.read(winner.stored().relativePath(), winner.stored().sha256()));
        } finally {
            workers.shutdownNow();
        }
    }

    private static byte[] png(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0xff3f51b5);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "png", output));
        return output.toByteArray();
    }

    private static Callable<StoreAttempt> concurrentStore(
            ImageAssetStore store, byte[] bytes, CyclicBarrier startTogether) {
        return () -> {
            try {
                startTogether.await();
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
        for (byte part : digest) value.append(String.format("%02x", part));
        return value.toString();
    }

    private record StoreAttempt(ImageAssetStore.StoredAsset stored, Exception error) {
        boolean succeeded() { return stored != null; }
    }

    private static final class AtomicFakeClient implements MinioClientFactory.Client {
        private final Map<String, byte[]> objects = new ConcurrentHashMap<>();

        @Override public boolean bucketExists(String bucket) { return true; }
        @Override public void makeBucket(String bucket) { }

        @Override
        public void putObject(String bucket, String key, byte[] bytes, String contentType, boolean createOnly)
                throws Exception {
            byte[] previous = objects.putIfAbsent(key, Arrays.copyOf(bytes, bytes.length));
            if (previous != null) throw new MinioClientFactory.ObjectAlreadyExistsException();
        }

        @Override
        public InputStream getObject(String bucket, String key) throws Exception {
            byte[] bytes = objects.get(key);
            if (bytes == null) throw new MinioClientFactory.ObjectMissingException();
            return new ByteArrayInputStream(bytes);
        }

        @Override
        public MinioClientFactory.ObjectMetadata statObject(String bucket, String key) throws Exception {
            byte[] bytes = objects.get(key);
            if (bytes == null) throw new MinioClientFactory.ObjectMissingException();
            return new MinioClientFactory.ObjectMetadata(bytes.length, "image/png");
        }

        @Override public void removeObject(String bucket, String key) { objects.remove(key); }
        byte[] bytes(String key) { return objects.get(key); }
    }

    private static final class RepeatingInputStream extends InputStream {
        private long remaining;
        private RepeatingInputStream(long remaining) { this.remaining = remaining; }
        @Override public int read() { return remaining-- > 0 ? 0 : -1; }
        @Override public int read(byte[] bytes, int offset, int length) {
            if (remaining <= 0) return -1;
            int count = (int) Math.min(length, remaining);
            Arrays.fill(bytes, offset, offset + count, (byte) 0);
            remaining -= count;
            return count;
        }
    }
}

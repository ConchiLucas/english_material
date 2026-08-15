package com.aitaskcenter.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aitaskcenter.model.ImageAsset;
import com.aitaskcenter.repository.ImageAssetRepository;
import com.aitaskcenter.service.ImageAssetStore;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ImageAssetControllerTest {
    private static final byte[] PNG_BYTES = new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47};
    private ImageAssetRepository repository;
    private ImageAssetStore store;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        repository = mock(ImageAssetRepository.class);
        store = mock(ImageAssetStore.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new ImageAssetController(repository, store))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void servesStoredAssetWithImmutableCaching() throws Exception {
        ImageAsset asset = asset();
        when(repository.findById(42L)).thenReturn(Optional.of(asset));
        when(store.read("image-1/final.png", "a".repeat(64))).thenReturn(PNG_BYTES);

        mockMvc.perform(get("/api/image-assets/42/content"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().string("ETag", "\"" + "a".repeat(64) + "\""))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("public"),
                        org.hamcrest.Matchers.containsString("max-age=31536000"),
                        org.hamcrest.Matchers.containsString("immutable"))))
                .andExpect(content().bytes(PNG_BYTES));

        verify(store).read("image-1/final.png", "a".repeat(64));
    }

    @Test
    void returnsRealHttp404ForUnknownOrUnreadableAssetsWithoutLeakingPaths() throws Exception {
        when(repository.findById(404L)).thenReturn(Optional.empty());
        ImageAsset asset = asset();
        asset.setRelativePath("private/absolute-secret.png");
        when(repository.findById(43L)).thenReturn(Optional.of(asset));
        when(store.read("private/absolute-secret.png", "a".repeat(64)))
                .thenThrow(new IllegalArgumentException("读取失败 /Users/conchi/private/absolute-secret.png"));

        mockMvc.perform(get("/api/image-assets/404/content"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));
        mockMvc.perform(get("/api/image-assets/43/content"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));
        mockMvc.perform(get("/api/image-assets/not-a-number/content"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));
    }

    private ImageAsset asset() {
        ImageAsset asset = new ImageAsset();
        asset.setId(42L);
        asset.setRelativePath("image-1/final.png");
        asset.setMime("image/png");
        asset.setSha256("a".repeat(64));
        return asset;
    }
}

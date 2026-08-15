package com.aitaskcenter.controller;

import com.aitaskcenter.model.ImageAsset;
import com.aitaskcenter.repository.ImageAssetRepository;
import com.aitaskcenter.service.ImageAssetStore;
import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/image-assets")
public class ImageAssetController {
    private final ImageAssetRepository repository;
    private final ImageAssetStore store;

    public ImageAssetController(ImageAssetRepository repository, ImageAssetStore store) {
        this.repository = repository;
        this.store = store;
    }

    @GetMapping("/{assetId}/content")
    public ResponseEntity<byte[]> content(@PathVariable String assetId) {
        if (assetId == null || !assetId.matches("[1-9][0-9]{0,18}")) return ResponseEntity.notFound().build();
        long id;
        try {
            id = Long.parseLong(assetId);
        } catch (NumberFormatException exception) {
            return ResponseEntity.notFound().build();
        }
        ImageAsset asset = repository.findById(id).orElse(null);
        if (asset == null || !("image/png".equals(asset.getMime()) || "image/jpeg".equals(asset.getMime()))) {
            return ResponseEntity.notFound().build();
        }
        try {
            byte[] bytes = store.read(asset.getRelativePath(), asset.getSha256());
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(asset.getMime()))
                    .eTag(asset.getSha256())
                    .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
                    .body(bytes);
        } catch (RuntimeException exception) {
            return ResponseEntity.notFound().build();
        }
    }
}

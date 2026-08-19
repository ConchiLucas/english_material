package com.aitaskcenter.repository;

import com.aitaskcenter.model.ImageAsset;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ImageAssetRepository extends JpaRepository<ImageAsset, Long> {
    List<ImageAsset> findAllByRunIdOrderByCreatedAtAsc(String runId);

    List<ImageAsset> findAllByRunIdOrderByAssetTypeAscAssetKeyAsc(String runId);

    List<ImageAsset> findAllByRunIdOrderByAssetTypeAscAssetKeyAsc(String runId, Pageable pageable);

    List<ImageAsset> findAllByRunIdInAndAssetTypeOrderByRunIdAscAssetKeyAsc(
            Collection<String> runIds,
            String assetType);
}

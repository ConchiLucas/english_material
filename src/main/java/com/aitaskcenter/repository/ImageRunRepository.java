package com.aitaskcenter.repository;

import com.aitaskcenter.model.ImageRun;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ImageRunRepository extends JpaRepository<ImageRun, Long> {
    Optional<ImageRun> findByRunId(String runId);

    List<ImageRun> findAllByOrderByCreatedAtDesc();

    List<ImageRun> findAllByOrderByCreatedAtDesc(Pageable pageable);

    List<ImageRun> findAllByStatusIn(Collection<String> statuses);

    List<ImageRun> findAllByStatusInOrderByCreatedAtDesc(Collection<String> statuses);

    @Query("""
            select imageRun from ImageRun imageRun
            where imageRun.status = :status
              and exists (
                select asset.id from ImageAsset asset
                where asset.runId = imageRun.runId and asset.assetType = 'FINAL'
              )
            order by imageRun.createdAt desc, imageRun.id desc
            """)
    Page<ImageRun> findCompletedImageResults(@Param("status") String status, Pageable pageable);
}

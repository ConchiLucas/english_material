package com.aitaskcenter.repository;

import com.aitaskcenter.model.ImageShot;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ImageShotRepository extends JpaRepository<ImageShot, Long> {
    List<ImageShot> findAllByRunIdOrderBySequenceAsc(String runId);

    List<ImageShot> findAllByRunIdOrderBySequenceAsc(String runId, Pageable pageable);
}

package com.aitaskcenter.repository;

import com.aitaskcenter.model.ImageRun;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ImageRunRepository extends JpaRepository<ImageRun, Long> {
    Optional<ImageRun> findByRunId(String runId);

    List<ImageRun> findAllByOrderByCreatedAtDesc();

    List<ImageRun> findAllByStatusIn(Collection<String> statuses);

    List<ImageRun> findAllByStatusInOrderByCreatedAtDesc(Collection<String> statuses);
}

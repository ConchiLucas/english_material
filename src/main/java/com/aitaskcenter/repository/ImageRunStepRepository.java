package com.aitaskcenter.repository;

import com.aitaskcenter.model.ImageRunStep;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ImageRunStepRepository extends JpaRepository<ImageRunStep, Long> {
    List<ImageRunStep> findAllByRunIdOrderBySequenceAsc(String runId);

    List<ImageRunStep> findAllByRunIdOrderBySequenceAsc(String runId, Pageable pageable);
}

package com.aitaskcenter.repository;

import com.aitaskcenter.model.StoryRun;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoryRunRepository extends JpaRepository<StoryRun, Long> {
    Optional<StoryRun> findByRunId(String runId);

    List<StoryRun> findAllByOrderByCreatedAtDesc();
}

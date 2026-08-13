package com.aitaskcenter.repository;

import com.aitaskcenter.model.StoryRunStep;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoryRunStepRepository extends JpaRepository<StoryRunStep, Long> {
    List<StoryRunStep> findAllByRunIdOrderBySequenceAsc(String runId);
}

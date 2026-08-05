package com.aitaskcenter.repository;

import com.aitaskcenter.model.AgentTestRun;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentTestRunRepository extends JpaRepository<AgentTestRun, Long> {
    List<AgentTestRun> findTop100ByOrderByCreatedAtDesc();
}

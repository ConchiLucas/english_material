package com.aitaskcenter.repository;

import com.aitaskcenter.model.StoryAgentConfig;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoryAgentConfigRepository extends JpaRepository<StoryAgentConfig, Long> {
    Optional<StoryAgentConfig> findByAgentKey(String agentKey);

    List<StoryAgentConfig> findAllByOrderByAgentKeyAsc();
}

package com.aitaskcenter.repository;

import com.aitaskcenter.model.StoryAgentPromptVersion;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoryAgentPromptVersionRepository extends JpaRepository<StoryAgentPromptVersion, Long> {
    List<StoryAgentPromptVersion> findByAgentKeyOrderByVersionDesc(String agentKey);

    Optional<StoryAgentPromptVersion> findByAgentKeyAndVersion(String agentKey, int version);
}

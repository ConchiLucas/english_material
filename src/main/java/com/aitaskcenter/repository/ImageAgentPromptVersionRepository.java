package com.aitaskcenter.repository;

import com.aitaskcenter.model.ImageAgentPromptVersion;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ImageAgentPromptVersionRepository extends JpaRepository<ImageAgentPromptVersion, Long> {
    List<ImageAgentPromptVersion> findByAgentKeyOrderByPromptVersionDesc(String agentKey);

    Optional<ImageAgentPromptVersion> findByAgentKeyAndPromptVersion(String agentKey, int promptVersion);
}

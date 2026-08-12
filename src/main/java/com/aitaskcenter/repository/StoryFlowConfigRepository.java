package com.aitaskcenter.repository;

import com.aitaskcenter.model.StoryFlowConfig;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoryFlowConfigRepository extends JpaRepository<StoryFlowConfig, Long> {
    Optional<StoryFlowConfig> findByConfigKey(String configKey);
}

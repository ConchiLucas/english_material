package com.aitaskcenter.repository;

import com.aitaskcenter.model.ImageAgentConfig;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ImageAgentConfigRepository extends JpaRepository<ImageAgentConfig, Long> {
    Optional<ImageAgentConfig> findByAgentKey(String agentKey);

    List<ImageAgentConfig> findAllByOrderByAgentKeyAsc();
}

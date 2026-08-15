package com.aitaskcenter.repository;

import com.aitaskcenter.model.ImageFlowConfig;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ImageFlowConfigRepository extends JpaRepository<ImageFlowConfig, Long> {
    Optional<ImageFlowConfig> findByFlowKey(String flowKey);
}

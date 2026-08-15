package com.aitaskcenter.repository;

import com.aitaskcenter.model.MinioConfig;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MinioConfigRepository extends JpaRepository<MinioConfig, Long> {
    Optional<MinioConfig> findByConfigKey(String configKey);
}

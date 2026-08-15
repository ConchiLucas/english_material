package com.aitaskcenter.repository;

import com.aitaskcenter.model.ImageStylePreset;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ImageStylePresetRepository extends JpaRepository<ImageStylePreset, Long> {
    Optional<ImageStylePreset> findByPresetKey(String presetKey);

    List<ImageStylePreset> findAllByOrderByBuiltInDescNameAsc();

    List<ImageStylePreset> findByEnabledTrueOrderByBuiltInDescNameAsc();
}

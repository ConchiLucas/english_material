package com.aitaskcenter.config;

import com.aitaskcenter.model.ImageRun;
import com.aitaskcenter.repository.ImageRunRepository;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;

@Component
public class ImageRunRecoveryInitializer implements ApplicationRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(ImageRunRecoveryInitializer.class);
    private static final List<String> ACTIVE_STATUSES = List.of(
            "QUEUED", "PLANNING", "GENERATING_REFERENCES", "GENERATING_SHOTS", "COMPOSITING");
    private static final String RESTART_ERROR = "应用重启，图片批次无法继续";

    private final ImageRunRepository runRepository;

    public ImageRunRecoveryInitializer(ImageRunRepository runRepository) {
        this.runRepository = runRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<ImageRun> activeRuns = runRepository.findAllByStatusIn(ACTIVE_STATUSES);
        if (activeRuns.isEmpty()) {
            return;
        }
        OffsetDateTime finishedAt = OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS);
        for (ImageRun activeRun : activeRuns) {
            activeRun.setStatus("FAILED");
            activeRun.setErrorMessage(RESTART_ERROR);
            activeRun.setFinishedAt(finishedAt);
            try {
                runRepository.saveAndFlush(activeRun);
            } catch (ObjectOptimisticLockingFailureException ex) {
                LOGGER.warn("图片批次重启恢复遇到并发更新，保留较新的批次状态");
            }
        }
    }
}

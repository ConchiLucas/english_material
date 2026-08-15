package com.aitaskcenter.config;

import com.aitaskcenter.model.ImageRun;
import com.aitaskcenter.model.ImageRunStep;
import com.aitaskcenter.repository.ImageRunRepository;
import com.aitaskcenter.repository.ImageRunStepRepository;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.domain.PageRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class ImageRunRecoveryInitializer implements ApplicationRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(ImageRunRecoveryInitializer.class);
    private static final List<String> ACTIVE_STATUSES = List.of(
            "QUEUED", "PLANNING", "GENERATING_REFERENCES", "GENERATING_SHOTS", "COMPOSITING");
    private static final String RESTART_ERROR = "服务重启，图片批次已中断";
    private static final int MAX_STEPS_PER_RUN = 12;

    private final ImageRunRepository runRepository;
    private final ImageRunStepRepository stepRepository;
    private final TransactionTemplate recoveryTransaction;

    public ImageRunRecoveryInitializer(
            ImageRunRepository runRepository,
            ImageRunStepRepository stepRepository,
            PlatformTransactionManager transactionManager) {
        this.runRepository = runRepository;
        this.stepRepository = stepRepository;
        this.recoveryTransaction = new TransactionTemplate(transactionManager);
        this.recoveryTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public void run(ApplicationArguments args) {
        List<ImageRun> activeRuns = runRepository.findAllByStatusIn(ACTIVE_STATUSES);
        if (activeRuns.isEmpty()) {
            return;
        }
        for (ImageRun activeRun : activeRuns) {
            try {
                recoveryTransaction.executeWithoutResult(ignored -> recover(activeRun));
            } catch (ObjectOptimisticLockingFailureException ex) {
                LOGGER.warn("图片批次重启恢复遇到并发更新，保留较新的批次状态");
            }
        }
    }

    private void recover(ImageRun activeRun) {
        OffsetDateTime finishedAt = OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS);
        List<ImageRunStep> steps = stepRepository.findAllByRunIdOrderBySequenceAsc(
                activeRun.getRunId(), PageRequest.of(0, MAX_STEPS_PER_RUN));
        for (ImageRunStep step : steps) {
            if (!"RUNNING".equals(step.getStatus())) {
                continue;
            }
            step.setStatus("FAILED");
            step.setErrorMessage(RESTART_ERROR);
            step.setFinishedAt(finishedAt);
            stepRepository.saveAndFlush(step);
        }
        activeRun.setStatus("FAILED");
        activeRun.setErrorMessage(RESTART_ERROR);
        activeRun.setFinishedAt(finishedAt);
        runRepository.saveAndFlush(activeRun);
    }
}

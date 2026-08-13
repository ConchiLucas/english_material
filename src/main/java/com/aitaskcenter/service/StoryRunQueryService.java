package com.aitaskcenter.service;

import com.aitaskcenter.dto.StoryRunDtos.RunDetail;
import com.aitaskcenter.dto.StoryRunDtos.RunStepView;
import com.aitaskcenter.dto.StoryRunDtos.RunSummary;
import com.aitaskcenter.dto.StoryRunDtos.StoryWord;
import com.aitaskcenter.model.StoryRun;
import com.aitaskcenter.model.StoryRunStep;
import com.aitaskcenter.repository.StoryRunRepository;
import com.aitaskcenter.repository.StoryRunStepRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StoryRunQueryService {
    private static final TypeReference<List<StoryWord>> WORD_LIST = new TypeReference<>() { };

    private final StoryRunRepository runRepository;
    private final StoryRunStepRepository stepRepository;
    private final ObjectMapper objectMapper;

    public StoryRunQueryService(
            StoryRunRepository runRepository,
            StoryRunStepRepository stepRepository,
            ObjectMapper objectMapper) {
        this.runRepository = runRepository;
        this.stepRepository = stepRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<RunSummary> listRuns() {
        return runRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(this::toSummary)
                .toList();
    }

    @Transactional(readOnly = true)
    public RunDetail getRun(String runId) {
        StoryRun run = runRepository.findByRunId(runId)
                .orElseThrow(() -> new IllegalArgumentException("运行批次不存在"));
        List<RunStepView> steps = stepRepository.findAllByRunIdOrderBySequenceAsc(runId).stream()
                .map(this::toStepView)
                .toList();
        return new RunDetail(
                run.getRunId(),
                parseWords(run.getInputWordsJson()),
                run.getTargetGrade(),
                run.getStatus(),
                run.getFinalStory(),
                run.getErrorMessage(),
                run.getTotalTokens(),
                run.getCreatedAt(),
                run.getStartedAt(),
                run.getFinishedAt(),
                steps);
    }

    private RunSummary toSummary(StoryRun run) {
        return new RunSummary(
                run.getRunId(),
                parseWords(run.getInputWordsJson()),
                run.getTargetGrade(),
                run.getStatus(),
                run.getTotalTokens(),
                run.getCreatedAt(),
                run.getStartedAt(),
                run.getFinishedAt());
    }

    private RunStepView toStepView(StoryRunStep step) {
        return new RunStepView(
                step.getId(),
                step.getSequence(),
                step.getQualityRound(),
                step.getAgentKey(),
                step.getAgentName(),
                step.getPromptVersion(),
                step.getProviderId(),
                step.getProviderModel(),
                step.getInputJson(),
                step.getOutputText(),
                step.getStatus(),
                step.getInputTokens(),
                step.getOutputTokens(),
                step.getTotalTokens(),
                step.getDurationMs(),
                step.getCreatedAt());
    }

    private List<StoryWord> parseWords(String json) {
        try {
            return objectMapper.readValue(json, WORD_LIST);
        } catch (Exception ex) {
            throw new IllegalArgumentException("运行批次单词快照无法读取", ex);
        }
    }
}

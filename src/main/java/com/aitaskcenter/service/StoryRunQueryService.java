package com.aitaskcenter.service;

import com.aitaskcenter.dto.StoryRunDtos.RunDetail;
import com.aitaskcenter.dto.StoryRunDtos.RunStepView;
import com.aitaskcenter.dto.StoryRunDtos.RunSummary;
import com.aitaskcenter.dto.StoryRunDtos.StoryResultItem;
import com.aitaskcenter.dto.StoryRunDtos.StoryResultPage;
import com.aitaskcenter.dto.StoryRunDtos.StoryWord;
import com.aitaskcenter.model.StoryRun;
import com.aitaskcenter.model.StoryRunStep;
import com.aitaskcenter.repository.StoryRunRepository;
import com.aitaskcenter.repository.StoryRunStepRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

@Service
public class StoryRunQueryService {
    private static final TypeReference<List<StoryWord>> WORD_LIST = new TypeReference<>() { };
    private static final Set<Integer> RESULT_PAGE_SIZES = Set.of(10, 20, 100);
    private static final Pattern FIRST_SCENE_TITLE = Pattern.compile(
            "(?m)^Scene\\s+\\d+\\s*:\\s*([^\\r\\n]+)$");

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
    public StoryResultPage listResults(int page, int pageSize) {
        if (page < 1) throw new IllegalArgumentException("页码必须从 1 开始");
        if (!RESULT_PAGE_SIZES.contains(pageSize)) {
            throw new IllegalArgumentException("每页数量只支持 10、20 或 100");
        }
        Page<StoryRun> result = runRepository.findCompletedStoryResults(
                "COMPLETED", PageRequest.of(page - 1, pageSize));
        List<StoryResultItem> items = result.getContent().stream()
                .map(this::toStoryResult)
                .toList();
        return new StoryResultPage(items, page, pageSize, result.getTotalElements(), result.getTotalPages());
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

    private StoryResultItem toStoryResult(StoryRun run) {
        List<StoryWord> words = parseWords(run.getInputWordsJson());
        return new StoryResultItem(
                run.getRunId(),
                storyTitle(run.getFinalStory()),
                clean(run.getTargetGrade()).isEmpty() ? "不限制" : clean(run.getTargetGrade()),
                words.size(),
                run.getFinalStory(),
                run.getCreatedAt());
    }

    private String storyTitle(String story) {
        Matcher matcher = FIRST_SCENE_TITLE.matcher(story == null ? "" : story);
        return matcher.find() ? matcher.group(1).trim() : "未命名故事";
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
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

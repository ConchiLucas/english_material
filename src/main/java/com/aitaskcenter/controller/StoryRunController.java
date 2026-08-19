package com.aitaskcenter.controller;

import com.aitaskcenter.dto.ApiResponse;
import com.aitaskcenter.dto.StoryRunDtos.RunDetail;
import com.aitaskcenter.dto.StoryRunDtos.RunSummary;
import com.aitaskcenter.dto.StoryRunDtos.StoryResultPage;
import com.aitaskcenter.dto.StoryRunDtos.RandomWordsRequest;
import com.aitaskcenter.dto.StoryRunDtos.StoryWord;
import com.aitaskcenter.dto.StoryRunDtos.StartRunRequest;
import com.aitaskcenter.dto.StoryRunDtos.WordLibraryView;
import com.aitaskcenter.service.StoryRunQueryService;
import com.aitaskcenter.service.StoryRunExecutionService;
import com.aitaskcenter.service.StoryWordSourceService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/story-runs")
public class StoryRunController {
    private final StoryRunQueryService queryService;
    private final StoryWordSourceService wordSourceService;
    private final StoryRunExecutionService executionService;

    public StoryRunController(
            StoryRunQueryService queryService,
            StoryWordSourceService wordSourceService,
            StoryRunExecutionService executionService) {
        this.queryService = queryService;
        this.wordSourceService = wordSourceService;
        this.executionService = executionService;
    }

    @GetMapping
    public ApiResponse<List<RunSummary>> listRuns() {
        return ApiResponse.ok(queryService.listRuns());
    }

    @GetMapping("/results")
    public ApiResponse<StoryResultPage> listResults(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {
        return ApiResponse.ok(queryService.listResults(page, pageSize));
    }

    @GetMapping("/{runId}")
    public ApiResponse<RunDetail> getRun(@PathVariable String runId) {
        return ApiResponse.ok(queryService.getRun(runId));
    }

    @GetMapping("/word-libraries")
    public ApiResponse<List<WordLibraryView>> listWordLibraries(@RequestParam Long connectionId) {
        return ApiResponse.ok(wordSourceService.listLibraries(connectionId));
    }

    @PostMapping("/random-words")
    public ApiResponse<List<StoryWord>> randomWords(@RequestBody RandomWordsRequest request) {
        return ApiResponse.ok(wordSourceService.randomWords(
                request.connectionId(), request.libraryId(), request.count()));
    }

    @PostMapping
    public ApiResponse<RunSummary> createRun(@RequestBody StartRunRequest request) {
        return ApiResponse.ok(executionService.createRun(request), "运行批次已创建");
    }
}

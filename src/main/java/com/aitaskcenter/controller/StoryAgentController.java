package com.aitaskcenter.controller;

import com.aitaskcenter.dto.ApiResponse;
import com.aitaskcenter.dto.StoryAgentDtos.AgentUpdateRequest;
import com.aitaskcenter.dto.StoryAgentDtos.AgentView;
import com.aitaskcenter.dto.StoryAgentDtos.BudgetUpdateRequest;
import com.aitaskcenter.dto.StoryAgentDtos.BudgetView;
import com.aitaskcenter.dto.StoryAgentDtos.FlowView;
import com.aitaskcenter.dto.StoryAgentDtos.PromptVersionView;
import com.aitaskcenter.service.StoryAgentService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/story-agents")
public class StoryAgentController {
    private final StoryAgentService service;

    public StoryAgentController(StoryAgentService service) {
        this.service = service;
    }

    @GetMapping("/flow")
    public ApiResponse<FlowView> getFlow() {
        return ApiResponse.ok(service.getFlow());
    }

    @PutMapping("/{agentKey}")
    public ApiResponse<AgentView> update(
            @PathVariable String agentKey,
            @RequestBody AgentUpdateRequest request) {
        return ApiResponse.ok(service.update(agentKey, request), "Prompt 已保存");
    }

    @GetMapping("/{agentKey}/versions")
    public ApiResponse<List<PromptVersionView>> versions(@PathVariable String agentKey) {
        return ApiResponse.ok(service.versions(agentKey));
    }

    @PostMapping("/{agentKey}/versions/{version}/restore")
    public ApiResponse<AgentView> restore(
            @PathVariable String agentKey,
            @PathVariable int version) {
        return ApiResponse.ok(service.restore(agentKey, version), "Prompt 版本已恢复");
    }

    @PutMapping("/flow/config")
    public ApiResponse<BudgetView> updateBudget(@RequestBody BudgetUpdateRequest request) {
        return ApiResponse.ok(service.updateBudget(request), "质量预算已保存");
    }
}

package com.aitaskcenter.controller;

import com.aitaskcenter.dto.ApiResponse;
import com.aitaskcenter.dto.ImageAgentDtos.AgentUpdateRequest;
import com.aitaskcenter.dto.ImageAgentDtos.AgentView;
import com.aitaskcenter.dto.ImageAgentDtos.FlowConfigView;
import com.aitaskcenter.dto.ImageAgentDtos.FlowUpdateRequest;
import com.aitaskcenter.dto.ImageAgentDtos.FlowView;
import com.aitaskcenter.dto.ImageAgentDtos.PromptVersionView;
import com.aitaskcenter.dto.ImageAgentDtos.RestoreVersionRequest;
import com.aitaskcenter.service.ImageAgentService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/image-agents")
public class ImageAgentController {
    private final ImageAgentService service;

    public ImageAgentController(ImageAgentService service) { this.service = service; }

    @GetMapping("/flow")
    public ApiResponse<FlowView> getFlow() { return ApiResponse.ok(service.getFlow()); }

    @PutMapping("/{agentKey}")
    public ApiResponse<AgentView> updateAgent(@PathVariable String agentKey, @RequestBody AgentUpdateRequest request) {
        return ApiResponse.ok(service.updateAgent(agentKey, request), "Prompt 已保存");
    }

    @GetMapping("/{agentKey}/versions")
    public ApiResponse<List<PromptVersionView>> versions(@PathVariable String agentKey) {
        return ApiResponse.ok(service.versions(agentKey));
    }

    @PostMapping("/{agentKey}/versions/{version}/restore")
    public ApiResponse<AgentView> restore(@PathVariable String agentKey, @PathVariable int version,
                                           @RequestBody(required = false) RestoreVersionRequest request) {
        return ApiResponse.ok(service.restoreVersion(agentKey, version,
                request == null ? null : request.updatedAt()), "Prompt 版本已恢复");
    }

    @PutMapping("/flow/config")
    public ApiResponse<FlowConfigView> updateFlow(@RequestBody FlowUpdateRequest request) {
        return ApiResponse.ok(service.updateFlow(request), "图片流程配置已保存");
    }
}

package com.aitaskcenter.controller;

import com.aitaskcenter.dto.AgentDefinitionRequest;
import com.aitaskcenter.dto.AgentTestRequest;
import com.aitaskcenter.dto.AgentTestResult;
import com.aitaskcenter.dto.ApiResponse;
import com.aitaskcenter.model.AgentDefinition;
import com.aitaskcenter.service.AgentService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agents")
public class AgentController {
    private final AgentService service;

    public AgentController(AgentService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<AgentDefinition>> list() {
        return ApiResponse.ok(service.list());
    }

    @PostMapping
    public ApiResponse<AgentDefinition> create(@RequestBody AgentDefinitionRequest request) {
        return ApiResponse.ok(service.create(request), "Agent 已创建");
    }

    @PutMapping("/{id}")
    public ApiResponse<AgentDefinition> update(@PathVariable Long id, @RequestBody AgentDefinitionRequest request) {
        return ApiResponse.ok(service.update(id, request), "Agent 已保存");
    }

    @PostMapping("/{id}/test")
    public ApiResponse<AgentTestResult> test(@PathVariable Long id, @RequestBody AgentTestRequest request) {
        return ApiResponse.ok(service.test(id, request.inputJson()), "测试完成");
    }

    @GetMapping("/runs")
    public ApiResponse<List<AgentTestResult>> runs() {
        return ApiResponse.ok(service.listRuns());
    }
}

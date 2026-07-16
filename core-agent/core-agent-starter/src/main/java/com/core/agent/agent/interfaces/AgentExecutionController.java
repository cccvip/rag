package com.core.agent.agent.interfaces;

import com.core.agent.agent.domain.Agent;
import com.core.agent.agent.domain.AgentResult;
import com.core.agent.agent.graph.domain.AgentState;
import com.core.agent.agent.graph.domain.Checkpoint;
import com.core.agent.agent.strategy.domain.ExecutionStrategy;
import com.core.agent.agent.strategy.infrastructure.PlanAndExecuteStrategy;
import com.core.agent.agent.strategy.infrastructure.ReactStrategy;
import com.core.agent.checkpoint.application.CheckpointService;
import com.core.agent.context.domain.ContextStrategy;
import com.core.agent.context.infrastructure.DefaultContextStrategy;
import com.core.agent.multiagent.application.AgentRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 执行 REST 入口。
 *
 * <p>提供运行、恢复、状态查询能力，作为多 Agent 协同中台的统一任务入口。</p>
 */
@RestController
@RequestMapping("/agents/{agentId}")
public class AgentExecutionController {

    private final Agent agent;
    private final AgentRegistry agentRegistry;
    private final CheckpointService checkpointService;
    private final ContextStrategy contextStrategy;
    private final ConcurrentHashMap<String, AgentResult> taskStore = new ConcurrentHashMap<>();

    public AgentExecutionController(Agent agent,
                                    AgentRegistry agentRegistry,
                                    CheckpointService checkpointService,
                                    ContextStrategy contextStrategy) {
        this.agent = agent;
        this.agentRegistry = agentRegistry;
        this.checkpointService = checkpointService;
        this.contextStrategy = contextStrategy != null ? contextStrategy : new DefaultContextStrategy();
    }

    @PostMapping("/run")
    public ResponseEntity<RunResponse> run(@PathVariable String agentId, @RequestBody RunRequest request) {
        if (!agentRegistry.find(agentId).isPresent()) {
            return ResponseEntity.notFound().build();
        }

        String taskId = UUID.randomUUID().toString();
        String sessionId = request.getSessionId() != null ? request.getSessionId() : taskId;
        String scene = request.getScene() != null ? request.getScene() : agent.getScene();

        AgentState initialState = AgentState.initial(taskId, agent.getTenantId(), agent.getUserId(), scene)
                .withVariable("sessionId", sessionId)
                .withVariable("query", request.getQuery())
                .withVariable("requireApproval", request.isRequireApproval());

        Agent configuredAgent = resolveAgent(request.getStrategy());
        AgentResult result = configuredAgent.run(initialState);
        taskStore.put(taskId, result);

        return ResponseEntity.ok(toResponse(taskId, result));
    }

    @PostMapping("/resume")
    public ResponseEntity<RunResponse> resume(@PathVariable String agentId,
                                              @RequestParam String checkpointToken) {
        if (!agentRegistry.find(agentId).isPresent()) {
            return ResponseEntity.notFound().build();
        }

        Checkpoint checkpoint = checkpointService.find(checkpointToken)
                .orElseThrow(() -> new CheckpointNotFoundException(checkpointToken));

        AgentResult result = agent.resume(checkpoint);
        String taskId = checkpointToken;
        taskStore.put(taskId, result);
        return ResponseEntity.ok(toResponse(taskId, result));
    }

    @GetMapping("/tasks/{taskId}/status")
    public ResponseEntity<TaskStatusResponse> status(@PathVariable String agentId, @PathVariable String taskId) {
        if (!agentRegistry.find(agentId).isPresent()) {
            return ResponseEntity.notFound().build();
        }

        AgentResult result = taskStore.get(taskId);
        if (result == null) {
            return ResponseEntity.notFound().build();
        }

        TaskStatusResponse response = new TaskStatusResponse();
        response.setTaskId(taskId);
        response.setStatus(result.isAwaitingApproval() ? "AWAITING_APPROVAL"
                : result.isCompleted() ? "COMPLETED" : "FAILED");
        response.setCheckpointToken(result.getCheckpointToken());
        response.setAnswer(result.getAnswer());
        return ResponseEntity.ok(response);
    }

    private Agent resolveAgent(String strategyName) {
        if (strategyName == null || strategyName.isBlank()) {
            return agent;
        }
        return switch (strategyName.toLowerCase()) {
            case "plan", "plan-and-execute" -> agent.withStrategy(new PlanAndExecuteStrategy(agent.getScene()));
            case "react" -> agent.withStrategy(new ReactStrategy(contextStrategy, agent.getScene()));
            default -> agent;
        };
    }

    private RunResponse toResponse(String taskId, AgentResult result) {
        RunResponse response = new RunResponse();
        response.setTaskId(taskId);
        if (result.isAwaitingApproval()) {
            response.setStatus("AWAITING_APPROVAL");
            response.setCheckpointToken(result.getCheckpointToken());
            response.setAnswer(result.getAnswer());
        } else if (result.isCompleted()) {
            response.setStatus("COMPLETED");
            response.setAnswer(result.getAnswer());
            response.setConfidence(result.getConfidence());
            response.setCitations(result.getCitations());
        } else {
            response.setStatus("FAILED");
            response.setAnswer(result.getAnswer());
            response.setErrorMessage(result.getErrorMessage());
        }
        return response;
    }
}

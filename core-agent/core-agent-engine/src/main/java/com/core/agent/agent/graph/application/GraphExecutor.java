package com.core.agent.agent.graph.application;

import com.core.agent.agent.graph.domain.AgentGraph;
import com.core.agent.agent.graph.domain.AgentState;
import com.core.agent.agent.graph.domain.Checkpoint;
import com.core.agent.agent.graph.domain.GraphResult;
import com.core.agent.agent.graph.domain.NodeContext;
import com.core.agent.checkpoint.application.CheckpointService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * 状态图执行器。
 *
 * <p>对 {@link AgentGraph} 的薄封装，负责启动执行、从 checkpoint 恢复、
 * 以及结果转换。支持在节点产生 {@link AgentState.Status#AWAITING_APPROVAL} 时自动持久化 checkpoint。</p>
 */
public class GraphExecutor {

    private static final Logger log = LoggerFactory.getLogger(GraphExecutor.class);

    private final AgentGraph<NodeContext> graph;
    private final NodeContext context;
    private final CheckpointService checkpointService;

    public GraphExecutor(AgentGraph<NodeContext> graph, NodeContext context) {
        this(graph, context, null);
    }

    public GraphExecutor(AgentGraph<NodeContext> graph, NodeContext context, CheckpointService checkpointService) {
        this.graph = graph;
        this.context = context;
        this.checkpointService = checkpointService;
    }

    /**
     * 从初始状态开始执行。
     */
    public GraphResult execute(AgentState initialState) {
        GraphResult result = graph.execute(initialState, context);
        return maybePersistCheckpoint(result);
    }

    /**
     * 从 checkpoint 恢复执行。
     */
    public GraphResult resume(Checkpoint checkpoint) {
        GraphResult result = graph.resume(checkpoint, context);
        return maybePersistCheckpoint(result);
    }

    private GraphResult maybePersistCheckpoint(GraphResult result) {
        AgentState state = result.getFinalState();
        if (state == null || state.getStatus() != AgentState.Status.AWAITING_APPROVAL) {
            return result;
        }
        if (state.getCheckpointToken() != null && !state.getCheckpointToken().isBlank()) {
            return result;
        }
        if (checkpointService == null) {
            log.warn("State is awaiting approval but no CheckpointService configured");
            return result;
        }

        String token = UUID.randomUUID().toString();
        Checkpoint checkpoint = Checkpoint.pending(token, state);
        String savedToken = checkpointService.save(checkpoint);
        AgentState updatedState = state.awaitingApproval(savedToken)
                .withVariable("checkpointToken", savedToken);
        return GraphResult.awaitingApproval(updatedState, savedToken);
    }
}

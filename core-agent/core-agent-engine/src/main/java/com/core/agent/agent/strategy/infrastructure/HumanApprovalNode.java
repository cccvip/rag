package com.core.agent.agent.strategy.infrastructure;

import com.core.agent.agent.graph.domain.AgentGraph;
import com.core.agent.agent.graph.domain.AgentNode;
import com.core.agent.agent.graph.domain.AgentState;
import com.core.agent.agent.graph.domain.Checkpoint;
import com.core.agent.agent.graph.domain.NodeContext;
import com.core.agent.checkpoint.application.CheckpointService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * 人工审批节点。
 *
 * <p>将当前状态保存为 checkpoint，并将执行置为 {@link AgentState.Status#AWAITING_APPROVAL}。
 * 审批通过/拒绝后，由外部调用 {@link AgentGraph#resume(Checkpoint, Object)} 恢复执行。</p>
 */
public class HumanApprovalNode implements AgentNode<NodeContext> {

    private static final Logger log = LoggerFactory.getLogger(HumanApprovalNode.class);

    private final String name;
    private final String approvalPrompt;
    private final CheckpointService checkpointService;

    public HumanApprovalNode(String name, String approvalPrompt, CheckpointService checkpointService) {
        this.name = name;
        this.approvalPrompt = approvalPrompt;
        this.checkpointService = checkpointService;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public AgentState invoke(AgentState state, NodeContext ctx) {
        String token = UUID.randomUUID().toString();
        Object query = state.getVariable("query");
        String contextHint = query != null ? query.toString() : "";

        Checkpoint checkpoint = Checkpoint.pending(token, state
                .withVariable("approvalPrompt", approvalPrompt)
                .withVariable("approvalContext", contextHint));

        String savedToken = checkpointService.save(checkpoint);
        log.info("Checkpoint created awaiting human approval: {}", savedToken);

        return state.awaitingApproval(savedToken)
                .withVariable("checkpointToken", savedToken);
    }
}

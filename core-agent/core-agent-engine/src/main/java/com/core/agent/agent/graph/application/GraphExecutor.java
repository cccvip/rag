package com.core.agent.agent.graph.application;

import com.core.agent.agent.graph.domain.AgentGraph;
import com.core.agent.agent.graph.domain.AgentState;
import com.core.agent.agent.graph.domain.Checkpoint;
import com.core.agent.agent.graph.domain.GraphResult;
import com.core.agent.agent.graph.domain.NodeContext;

/**
 * 状态图执行器。
 *
 * <p>对 {@link AgentGraph} 的薄封装，负责启动执行、从 checkpoint 恢复、
 * 以及结果转换。未来可扩展异步执行、分布式 checkpoint 等能力。</p>
 */
public class GraphExecutor {

    private final AgentGraph graph;
    private final NodeContext context;

    public GraphExecutor(AgentGraph graph, NodeContext context) {
        this.graph = graph;
        this.context = context;
    }

    /**
     * 从初始状态开始执行。
     */
    public GraphResult execute(AgentState initialState) {
        return graph.execute(initialState, context);
    }

    /**
     * 从 checkpoint 恢复执行。
     */
    public GraphResult resume(Checkpoint checkpoint) {
        return graph.resume(checkpoint, context);
    }
}

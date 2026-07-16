package com.core.agent.agent.strategy.infrastructure.supervisor;

import com.core.agent.agent.graph.domain.AgentNode;
import com.core.agent.agent.graph.domain.AgentState;
import com.core.agent.agent.graph.domain.NodeContext;
import com.core.agent.multiagent.domain.SubTask;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * Supervisor 分解节点：将用户查询拆分为子任务列表。
 */
public class DecomposeNode implements AgentNode<NodeContext> {

    private final String name;
    private final Function<String, List<SubTask>> decomposer;

    public DecomposeNode(String name, Function<String, List<SubTask>> decomposer) {
        this.name = name;
        this.decomposer = decomposer;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public AgentState invoke(AgentState state, NodeContext ctx) {
        String query = (String) state.getVariable("query");
        List<SubTask> subTasks = decomposer.apply(query);
        for (int i = 0; i < subTasks.size(); i++) {
            SubTask subTask = subTasks.get(i);
            if (subTask.getId() == null) {
                subTask.setId("subtask-" + (i + 1) + "-" + UUID.randomUUID().toString().substring(0, 4));
            }
        }
        return state.withVariable("subTasks", subTasks);
    }
}

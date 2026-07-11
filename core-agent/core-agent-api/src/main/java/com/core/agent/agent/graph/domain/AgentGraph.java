package com.core.agent.agent.graph.domain;

import lombok.Builder;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Agent 状态图执行引擎。
 *
 * <p>由节点（{@link AgentNode}）和边（{@link AgentEdge}）组成，
 * 支持条件路由、checkpoint、暂停/恢复。</p>
 *
 * @param <C> 节点执行上下文类型
 */
@Getter
@Builder
public class AgentGraph<C> {

    private static final Logger log = LoggerFactory.getLogger(AgentGraph.class);

    /** 图中所有节点 */
    private final Map<String, AgentNode<C>> nodes;

    /** 图中所有边 */
    private final List<AgentEdge<C>> edges;

    /** 起始节点 */
    private final String startNode;

    /** 终止节点集合 */
    private final Set<String> endNodes;

    /** 最大执行步数，防止死循环 */
    private final int maxSteps;

    /**
     * 执行状态图。
     *
     * @param initialState 初始状态
     * @param ctx          节点执行上下文
     * @return 执行结果
     */
    public GraphResult execute(AgentState initialState, C ctx) {
        AgentState state = initialState.copy();
        int steps = 0;

        while (state.getStatus() == AgentState.Status.RUNNING) {
            if (steps >= maxSteps) {
                return GraphResult.error(state,
                        "Reached max graph execution steps: " + maxSteps);
            }
            steps++;

            String currentNodeName = state.getCurrentNode();

            // 到达终止节点
            if (endNodes.contains(currentNodeName)) {
                log.debug("Reached end node: {}", currentNodeName);
                AgentNode<C> endNode = nodes.get(currentNodeName);
                if (endNode != null) {
                    state = endNode.invoke(state, ctx);
                }
                Object finalAnswer = state.getVariable("finalAnswer");
                state = finalAnswer != null
                        ? state.completed(finalAnswer.toString())
                        : state.completed("");
                break;
            }

            AgentNode<C> node = nodes.get(currentNodeName);
            if (node == null) {
                return GraphResult.error(state,
                        "Node not found: " + currentNodeName);
            }

            log.debug("Executing node: {}", currentNodeName);
            state = node.invoke(state, ctx);

            // 节点已改变状态（完成/出错/暂停/等待审批）
            if (state.getStatus() != AgentState.Status.RUNNING) {
                break;
            }

            // 路由到下一个节点
            String nextNode = routeNext(state);
            if (nextNode == null) {
                log.debug("No outgoing edge from node: {}, halting", currentNodeName);
                state = state.halted();
                break;
            }
            state = state.withCurrentNode(nextNode);
        }

        return toGraphResult(state);
    }

    /**
     * 从 checkpoint 恢复执行。
     */
    public GraphResult resume(Checkpoint checkpoint, C ctx) {
        AgentState state = checkpoint.getState().resumeFromCheckpoint();
        return execute(state, ctx);
    }

    /**
     * 根据当前状态路由到下一个节点。
     *
     * <p>优先匹配条件边，再匹配无条件边。如果多个条件边匹配，取第一条。</p>
     */
    private String routeNext(AgentState state) {
        String currentNode = state.getCurrentNode();

        AgentEdge<C> unconditionalEdge = null;
        for (AgentEdge<C> edge : edges) {
            if (!edge.matches(currentNode)) {
                continue;
            }
            if (edge.isConditional()) {
                if (edge.evaluate(state)) {
                    return edge.getTarget();
                }
            } else {
                if (unconditionalEdge == null) {
                    unconditionalEdge = edge;
                }
            }
        }
        return unconditionalEdge != null ? unconditionalEdge.getTarget() : null;
    }

    private GraphResult toGraphResult(AgentState state) {
        switch (state.getStatus()) {
            case COMPLETED -> {
                Object answer = state.getVariable("finalAnswer");
                return GraphResult.completed(state, answer != null ? answer.toString() : "");
            }
            case AWAITING_APPROVAL -> {
                return GraphResult.awaitingApproval(state, state.getCheckpointToken());
            }
            case ERROR -> {
                return GraphResult.error(state, state.getErrorMessage());
            }
            case HALTED, RUNNING -> {
                return GraphResult.halted(state);
            }
            default -> {
                return GraphResult.halted(state);
            }
        }
    }

    /**
     * 创建新的图构建器。
     */
    public static <C> GraphBuilder<C> builder() {
        return new GraphBuilder<>();
    }

    /**
     * AgentGraph 构建器。
     *
     * @param <C> 节点执行上下文类型
     */
    public static class GraphBuilder<C> {
        private final Map<String, AgentNode<C>> nodes = new HashMap<>();
        private final List<AgentEdge<C>> edges = new ArrayList<>();
        private String startNode;
        private final Set<String> endNodes = new HashSet<>();
        private int maxSteps = 100;

        public GraphBuilder<C> startNode(String nodeName) {
            this.startNode = nodeName;
            return this;
        }

        public GraphBuilder<C> addNode(AgentNode<C> node) {
            this.nodes.put(node.name(), node);
            return this;
        }

        public GraphBuilder<C> addNode(String name, AgentNode<C> node) {
            this.nodes.put(name, node);
            return this;
        }

        public GraphBuilder<C> addEdge(String source, String target) {
            this.edges.add(AgentEdge.<C>builder()
                    .source(source)
                    .target(target)
                    .build());
            return this;
        }

        public GraphBuilder<C> addConditionalEdge(String source, String target,
                                                   java.util.function.Predicate<AgentState> condition) {
            this.edges.add(AgentEdge.<C>builder()
                    .source(source)
                    .target(target)
                    .condition(condition)
                    .build());
            return this;
        }

        public GraphBuilder<C> endNode(String nodeName) {
            this.endNodes.add(nodeName);
            return this;
        }

        public GraphBuilder<C> maxSteps(int maxSteps) {
            this.maxSteps = maxSteps;
            return this;
        }

        public AgentGraph<C> build() {
            if (startNode == null || startNode.isBlank()) {
                throw new IllegalStateException("startNode is required");
            }
            if (!nodes.containsKey(startNode)) {
                throw new IllegalStateException("startNode not registered: " + startNode);
            }
            for (String endNode : endNodes) {
                if (!nodes.containsKey(endNode)) {
                    throw new IllegalStateException("endNode not registered: " + endNode);
                }
            }
            return new AgentGraph<>(new HashMap<>(nodes), new ArrayList<>(edges),
                    startNode, new HashSet<>(endNodes), maxSteps);
        }
    }
}

package com.core.agent.agent.graph.domain;

/**
 * 状态图节点接口。
 *
 * <p>每个节点接收当前状态，执行一段逻辑后返回新状态。
 * 节点是状态图中最小的执行单元。</p>
 *
 * @param <C> 节点执行上下文类型，由具体模块定义
 */
@FunctionalInterface
public interface AgentNode<C> {

    /**
     * 节点名称，用于在图中标识该节点。
     */
    default String name() {
        return getClass().getSimpleName();
    }

    /**
     * 执行节点逻辑。
     *
     * @param state 当前状态
     * @param ctx   节点执行上下文，提供工具、LLM、租户等基础设施
     * @return 执行后的新状态
     */
    AgentState invoke(AgentState state, C ctx);
}

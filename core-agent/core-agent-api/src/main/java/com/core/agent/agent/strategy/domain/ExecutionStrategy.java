package com.core.agent.agent.strategy.domain;

import com.core.agent.agent.graph.domain.AgentGraph;

/**
 * Agent 执行策略接口。
 *
 * <p>不同的策略把业务意图编译成不同的状态图：
 * ReAct、Plan-and-Execute、Reflection、Supervisor 等。</p>
 *
 * @param <C> 状态图执行上下文类型
 */
public interface ExecutionStrategy<C> {

    /**
     * 策略名称。
     */
    String name();

    /**
     * 编译生成状态图。
     *
     * @return 可执行的状态图
     */
    AgentGraph<C> compile();
}

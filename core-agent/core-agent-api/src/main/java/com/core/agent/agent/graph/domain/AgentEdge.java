package com.core.agent.agent.graph.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.function.Predicate;

/**
 * 状态图边，支持无条件边和条件边。
 *
 * @param <C> 节点执行上下文类型
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentEdge<C> {

    /** 源节点 */
    private String source;

    /** 目标节点 */
    private String target;

    /**
     * 条件函数。
     *
     * <p>为 null 表示无条件边；否则只有该函数返回 true 时才走这条边。</p>
     */
    private Predicate<AgentState> condition;

    public boolean matches(String nodeName) {
        return source.equals(nodeName);
    }

    public boolean isConditional() {
        return condition != null;
    }

    public boolean evaluate(AgentState state) {
        return condition == null || condition.test(state);
    }
}

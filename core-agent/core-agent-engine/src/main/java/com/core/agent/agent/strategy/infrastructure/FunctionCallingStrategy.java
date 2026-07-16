package com.core.agent.agent.strategy.infrastructure;

import com.core.agent.agent.graph.domain.AgentGraph;
import com.core.agent.agent.graph.domain.AgentNode;
import com.core.agent.agent.graph.domain.AgentState;
import com.core.agent.agent.graph.domain.GraphResult;
import com.core.agent.agent.graph.domain.NodeContext;
import com.core.agent.agent.strategy.domain.ExecutionStrategy;
import com.core.agent.tool.infrastructure.FunctionCallingExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 基于 Spring AI Function Calling 的执行策略。
 *
 * <p>与 ReAct / Plan-and-Execute 不同，本策略把工具调用完全交给 LLM 的
 * Function Calling 能力：LLM 自己决定调用哪个工具、传什么参数，策略只负责
 * 驱动多轮调用循环并返回最终答案。</p>
 *
 * <p>这是阶段二接入 Spring AI Function Calling 的关键策略。</p>
 */
public class FunctionCallingStrategy implements ExecutionStrategy<NodeContext> {

    private static final Logger log = LoggerFactory.getLogger(FunctionCallingStrategy.class);

    private final String systemPrompt;
    private final int maxIterations;

    public FunctionCallingStrategy() {
        this("You are a helpful assistant. Use the provided tools to answer the user's question.", 5);
    }

    public FunctionCallingStrategy(String systemPrompt, int maxIterations) {
        this.systemPrompt = systemPrompt;
        this.maxIterations = maxIterations;
    }

    @Override
    public String name() {
        return "function-calling";
    }

    @Override
    public AgentGraph<NodeContext> compile() {
        return AgentGraph.<NodeContext>builder()
                .startNode("functionCallingNode")
                .addNode("functionCallingNode", new FunctionCallingNode())
                .endNode("functionCallingNode")
                .maxSteps(maxIterations * 2 + 1)
                .build();
    }

    private class FunctionCallingNode implements AgentNode<NodeContext> {

        @Override
        public String name() {
            return "functionCallingNode";
        }

        @Override
        public AgentState invoke(AgentState state, NodeContext ctx) {
            String query = state.getVariable("query");
            if (query == null) {
                return state.error("Missing query in state");
            }

            try {
                FunctionCallingExecutor executor = new FunctionCallingExecutor(
                        ctx.getChatModel(), ctx.getToolRegistry(), maxIterations);
                String answer = executor.execute(systemPrompt, query);

                return state.completed(answer)
                        .withMessage(com.core.agent.agent.graph.domain.AgentMessage.observation(answer));
            } catch (Exception e) {
                log.error("Function calling strategy failed", e);
                return state.error("Function calling failed: " + e.getMessage());
            }
        }
    }
}

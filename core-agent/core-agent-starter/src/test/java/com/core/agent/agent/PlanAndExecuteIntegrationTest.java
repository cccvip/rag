package com.core.agent.agent;

import com.core.agent.agent.domain.Agent;
import com.core.agent.agent.domain.AgentResult;
import com.core.agent.agent.graph.domain.AgentState;
import com.core.agent.agent.graph.domain.GraphResult;
import com.core.agent.agent.graph.domain.NodeContext;
import com.core.agent.agent.strategy.domain.PlanPromptBuilder;
import com.core.agent.agent.strategy.infrastructure.PlanAndExecuteStrategy;
import com.core.agent.agent.strategy.infrastructure.PlanStep;
import com.core.agent.bootstrap.AgentApp;
import com.core.agent.bootstrap.MetricsTracker;
import com.core.agent.context.application.ContextManager;
import com.core.agent.guardrail.domain.GuardRail;
import com.core.agent.memory.application.MemoryManager;
import com.core.agent.memory.infrastructure.InMemoryMemoryManager;
import com.core.agent.memory.infrastructure.InMemoryMemoryStore;
import com.core.agent.shared.model.RiskLevel;
import com.core.agent.tool.domain.Tool;
import com.core.agent.tool.domain.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import org.springframework.ai.tokenizer.TokenCountEstimator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Plan-and-Execute 策略真实业务场景集成测试。
 *
 * <p>本测试使用真实的 DeepSeek LLM（通过 {@code DEEPSEEK_API_KEY} 环境变量），
 * 配合本地模拟的 RAG 检索工具，验证 Plan-and-Execute 在应急安全问答场景下的端到端效果。</p>
 *
 * <p>运行前请确保已设置环境变量：</p>
 * <pre>
 * export DEEPSEEK_API_KEY=your_key_here
 * </pre>
 */
@SpringBootTest(classes = AgentApp.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = ".+")
class PlanAndExecuteIntegrationTest {

    @Autowired
    private ChatModel chatModel;

    @Autowired
    private ContextManager contextManager;

    @Autowired
    private GuardRail guardRail;

    @Autowired
    private MetricsTracker metricsTracker;

    private final TokenCountEstimator tokenCountEstimator = new JTokkitTokenCountEstimator();

    /**
     * 真实业务场景：直接驱动 Plan-and-Execute 策略。
     *
     * <p>模拟用户询问“3 楼化学泄漏时如何撤离”，系统会先规划检索步骤，
     * 再调用本地模拟的检索工具，最后根据观察结果生成带引用和可信度的答案。</p>
     */
    @Test
    void shouldAnswerEmergencyQuestionWithPlanAndExecute() throws Exception {
        // 1. 准备本地模拟的 RAG 工具
        ToolRegistry registry = new ToolRegistry();
        registry.register(newEmergencyDocTool("dense_retrieve", "向量检索"));
        registry.register(newEmergencyDocTool("sparse_retrieve", "BM25 检索"));

        // 2. 构建 NodeContext
        MemoryManager memoryManager = new InMemoryMemoryManager(
                new InMemoryMemoryStore(), tokenCountEstimator, 2000);

        NodeContext.Trace trace = NodeContext.Trace.builder()
                .traceId("trace-integration-test")
                .tenantId("tenant-A")
                .userId("user-001")
                .scene("rag")
                .build();

        NodeContext ctx = NodeContext.builder()
                .chatModel(chatModel)
                .toolRegistry(registry)
                .guardRail(guardRail)
                .metricsTracker(metricsTracker)
                .memoryManager(memoryManager)
                .contextManager(contextManager)
                .maxIterations(5)
                .llmTimeoutSeconds(60)
                .toolTimeoutSeconds(30)
                .maxRetries(2)
                .enableReflection(false)
                .maxMemoryTokens(2000)
                .traceContext(trace)
                .build();

        // 3. 注入业务场景专用的 RAG prompt builder
        PlanPromptBuilder ragBuilder = new RagPlanPromptBuilder();
        PlanAndExecuteStrategy strategy = new PlanAndExecuteStrategy("rag", ragBuilder);

        // 4. 构建初始状态并执行
        AgentState initialState = AgentState.initial(
                        "trace-integration-test", "tenant-A", "user-001", "rag")
                .withVariable("sessionId", "session-integration-test")
                .withVariable("query", "3楼发生化学泄漏时，应该从哪里撤离？")
                .withCurrentNode("planNode");

        GraphResult result = strategy.compile().execute(initialState, ctx);

        // 5. 打印结果便于人工审视
        System.out.println("\n========== Plan-and-Execute 业务场景测试结果 ==========");
        System.out.println("Completed: " + result.isCompleted());
        System.out.println("Final Answer: " + result.getFinalAnswer());
        System.out.println("Confidence: " + result.getFinalState().getVariable("answerConfidence"));
        System.out.println("Citations: " + result.getFinalState().getVariable("answerCitations"));
        System.out.println("Replan Reason: " + result.getFinalState().getVariable("replanReason"));
        System.out.println("LLM Call Count: " + result.getFinalState().getVariable("llmCallCount"));
        System.out.println("=======================================================\n");

        // 6. 宽松断言：只要完成并返回非空答案即可
        assertTrue(result.isCompleted(), "状态图应正常完成");
        assertNotNull(result.getFinalAnswer(), "应返回最终答案");
        assertFalse(result.getFinalAnswer().isBlank(), "最终答案不应为空");

        String confidence = result.getFinalState().getVariable("answerConfidence");
        assertTrue(List.of("HIGH", "MEDIUM", "LOW").contains(confidence),
                "可信度应为 HIGH/MEDIUM/LOW 之一");
    }

    /**
     * 真实业务场景：通过 {@link Agent#withStrategy} 切换为 Plan-and-Execute。
     *
     * <p>验证 Agent 统一入口也能使用 Plan-and-Execute，而不必直接操作策略对象。</p>
     */
    @Test
    void shouldAnswerThroughAgentWithPlanAndExecuteStrategy() throws Exception {
        // 1. 准备工具
        ToolRegistry registry = new ToolRegistry();
        registry.register(newEmergencyDocTool("dense_retrieve", "向量检索"));

        // 2. 构造默认 Agent（内部为 ReAct）
        Agent agent = new Agent(chatModel, registry, guardRail, metricsTracker,
                new InMemoryMemoryManager(new InMemoryMemoryStore(), tokenCountEstimator, 2000),
                contextManager, 5, "tenant-A", "user-001", 2000);

        // 3. 切换为 Plan-and-Execute 策略
        PlanPromptBuilder ragBuilder = new RagPlanPromptBuilder();
        Agent planAgent = agent.withStrategy(new PlanAndExecuteStrategy("rag", ragBuilder));

        // 4. 通过 Agent 统一入口执行
        AgentResult result = planAgent.runWithResult("session-agent-test", "rag",
                "3楼发生化学泄漏时，应该从哪里撤离？");

        System.out.println("\n========== Agent.withStrategy 业务场景测试结果 ==========");
        System.out.println("Answer: " + result.getAnswer());
        System.out.println("Confidence: " + result.getConfidence());
        System.out.println("Citations: " + result.getCitations());
        System.out.println("========================================================\n");

        assertNotNull(result.getAnswer(), "应返回最终答案");
        assertFalse(result.getAnswer().isBlank(), "最终答案不应为空");
        assertTrue(List.of("HIGH", "MEDIUM", "LOW").contains(result.getConfidence()),
                "可信度应为 HIGH/MEDIUM/LOW 之一");
    }

    /**
     * 创建模拟的应急文档检索工具。
     */
    private Tool newEmergencyDocTool(String name, String description) {
        return new Tool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return description + "，返回带 [doc-xxxx] 编号的应急安全文档片段";
            }

            @Override
            public RiskLevel riskLevel() {
                return RiskLevel.LOW;
            }

            @Override
            public String execute(String input) {
                // 模拟返回固定文档，真实场景应由 RAG 服务生成
                return "[doc-1001] 3楼东侧楼梯为消防疏散通道，发生化学泄漏时应佩戴防毒面具，从东侧楼梯向下撤离。\n"
                        + "[doc-1002] 化学泄漏事故禁止乘坐电梯，应使用最近的安全楼梯，并在集合点B报到。";
            }
        };
    }

    /**
     * RAG 场景专用的 Plan-and-Execute prompt builder。
     *
     * <p>放在 starter 层（应用层），作为业务场景定制 prompt 的示例。
     * 框架层的 {@link PlanAndExecuteStrategy} 不再内置这些场景文本。</p>
     */
    private static class RagPlanPromptBuilder implements PlanPromptBuilder {

        @Override
        public String buildPlanPrompt(String query, List<PlanStep> previousPlan, NodeContext ctx) {
            StringBuilder sb = new StringBuilder();
            sb.append("你是应急安全领域的规划助手。请根据用户问题，制定一个分步计划，使用可用工具获取必要信息后回答。\n\n");
            sb.append("可用工具：\n");
            sb.append("- dense_retrieve(LOW): 向量检索，返回带 [doc-xxxx] 编号的应急安全文档片段\n");
            sb.append("- sparse_retrieve(LOW): BM25 检索，返回带 [doc-xxxx] 编号的应急安全文档片段\n\n");

            if (previousPlan != null && !previousPlan.isEmpty()) {
                sb.append("之前步骤的观察结果：\n");
                for (PlanStep step : previousPlan) {
                    if (step.getObservation() != null) {
                        sb.append("步骤 ").append(step.getStepNumber())
                                .append(" [").append(step.getToolName()).append("]: ");
                        sb.append(step.isSuccess() ? "成功" : "失败").append(" - ");
                        sb.append(step.getObservation()).append("\n");
                    }
                }
                sb.append("\n之前的计划不充分，请制定新计划补齐缺失信息。\n\n");
            }

            sb.append("用户问题：").append(query).append("\n\n");
            sb.append("请输出 JSON 数组，每个步骤包含：\n");
            sb.append("- stepNumber: 从 1 开始的整数\n");
            sb.append("- toolName: 要调用的工具名\n");
            sb.append("- toolInput: 传给工具的查询字符串\n");
            sb.append("- purpose: 该步骤的目的\n\n");
            sb.append("如果无需工具即可回答，输出空数组 []。只输出合法 JSON，不要 markdown。");
            return sb.toString();
        }

        @Override
        public String buildFinalAnswerPrompt(String query, List<PlanStep> plan) {
            StringBuilder sb = new StringBuilder();
            sb.append("你是应急安全问答助手。请根据以下观察结果回答用户问题。\n\n");
            sb.append("严格规则：\n");
            sb.append("1. 只能使用观察结果中的信息。\n");
            sb.append("2. 引用具体事实时必须使用 [doc-xxxx] 格式标注来源。\n");
            sb.append("3. 如果观察结果信息不足，请回答“资料中未明确提及”。\n");
            sb.append("4. 不要推断楼层、位置等观察结果中未明确提到的精确信息。\n");
            sb.append("5. 必须按以下格式输出：\n");
            sb.append("Confidence: HIGH | MEDIUM | LOW\n");
            sb.append("Citations: [doc-xxxx], [doc-yyyy]（无引用则写 None）\n");
            sb.append("Final Answer: 简洁准确的回答\n\n");

            sb.append("用户问题：").append(query).append("\n\n");

            if (plan != null && !plan.isEmpty()) {
                sb.append("观察结果：\n");
                for (PlanStep step : plan) {
                    sb.append("步骤 ").append(step.getStepNumber())
                            .append(" [").append(step.getToolName()).append("]: ");
                    sb.append(step.isSuccess() ? "成功" : "失败").append(" - ");
                    sb.append(step.getObservation() != null ? step.getObservation() : "无结果");
                    sb.append("\n");
                }
            } else {
                sb.append("无可用观察结果。\n");
            }

            sb.append("\nConfidence: ");
            return sb.toString();
        }
    }
}

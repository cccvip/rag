package com.core.agent.context;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import org.springframework.ai.tokenizer.TokenCountEstimator;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ContextManagerTest {

    private final TokenCountEstimator estimator = new JTokkitTokenCountEstimator();
    private final ContextManager contextManager = new ContextManager(estimator);
    private final String systemPrompt = "You are a helpful assistant.";

    @Test
    void shouldKeepAllBlocksWhenWithinBudget() {
        List<MessageBlock> blocks = List.of(
                MessageBlock.userQuestion("What is the emergency procedure?", estimator.estimate("What is the emergency procedure?")),
                MessageBlock.toolResult("[doc-1001] Chemical spill guide", estimator.estimate("[doc-1001] Chemical spill guide"))
        );

        String context = contextManager.assemble(systemPrompt, blocks, new DefaultContextStrategy(), 2000);

        assertTrue(context.contains("User: What is the emergency procedure?"));
        assertTrue(context.contains("Observation: [doc-1001] Chemical spill guide"));
    }

    @Test
    void shouldDropLowPriorityBlocksWhenOverBudget() {
        String longThought = "This is a very long reasoning process. ".repeat(100);
        String importantDoc = "[doc-1001] Critical evacuation procedure.";

        List<MessageBlock> blocks = new ArrayList<>();
        blocks.add(MessageBlock.userQuestion("What should I do?", estimator.estimate("What should I do?")));
        blocks.add(MessageBlock.toolResult(importantDoc, estimator.estimate(importantDoc)));
        blocks.add(MessageBlock.thought(longThought, estimator.estimate(longThought)));

        // 预算很小，只能保留高优先级内容
        String context = contextManager.assemble(systemPrompt, blocks, new RagContextStrategy(), 60);

        // 用户问题应该保留
        assertTrue(context.contains("User: What should I do?"));
        // 在 RAG 策略下，Tool Result 优先于 Thought
        assertTrue(context.contains("Observation: [doc-1001] Critical evacuation procedure."));
        // 超长 Thought 应该被裁剪
        assertFalse(context.contains(longThought));
    }

    @Test
    void ragStrategyShouldPreferToolResults() {
        String docContent = "[doc-1001] doc content";
        String answerContent = "Previous assistant answer. ".repeat(50);

        List<MessageBlock> blocks = new ArrayList<>();
        blocks.add(MessageBlock.userQuestion("question", estimator.estimate("question")));
        blocks.add(MessageBlock.assistantAnswer(answerContent, estimator.estimate(answerContent)));
        blocks.add(MessageBlock.toolResult(docContent, estimator.estimate(docContent)));

        // 小预算，RAG 策略应优先保留 Tool Result 而非历史 Assistant 答案
        String context = contextManager.assemble(systemPrompt, blocks, new RagContextStrategy(), 50);

        assertTrue(context.contains("Observation: [doc-1001] doc content"));
        assertFalse(context.contains(answerContent));
    }

    @Test
    void shouldReserveTokensForSystemPrompt() {
        List<MessageBlock> blocks = List.of(
                MessageBlock.userQuestion("question", estimator.estimate("question"))
        );

        // 预算刚好等于 system prompt 时，用户问题应该被裁剪
        int systemTokens = estimator.estimate(systemPrompt);
        String context = contextManager.assemble(systemPrompt, blocks, new DefaultContextStrategy(), systemTokens);

        assertFalse(context.contains("User: question"));
    }

    @Test
    void shouldPreserveBlockOrder() {
        List<MessageBlock> blocks = new ArrayList<>();
        blocks.add(MessageBlock.userQuestion("first", estimator.estimate("first")));
        blocks.add(MessageBlock.toolResult("obs1", estimator.estimate("obs1")));
        blocks.add(MessageBlock.userQuestion("second", estimator.estimate("second")));
        blocks.add(MessageBlock.toolResult("obs2", estimator.estimate("obs2")));

        String context = contextManager.assemble(systemPrompt, blocks, new DefaultContextStrategy(), 2000);

        int firstIndex = context.indexOf("User: first");
        int obs1Index = context.indexOf("Observation: obs1");
        int secondIndex = context.indexOf("User: second");
        int obs2Index = context.indexOf("Observation: obs2");

        assertTrue(firstIndex < obs1Index);
        assertTrue(obs1Index < secondIndex);
        assertTrue(secondIndex < obs2Index);
    }
}

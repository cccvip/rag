package com.core.agent.tool.infrastructure;

import com.core.agent.tool.domain.Tool;
import com.core.agent.tool.domain.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.ai.model.function.FunctionCallingOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 基于 Spring AI Function Calling 的工具执行器。
 *
 * <p>把 CoreAgent 的工具通过 {@link FunctionCallback} 暴露给 LLM，
 * 由 LLM 自己决定调用哪个工具、传入什么参数。</p>
 */
public class FunctionCallingExecutor {

    private static final Logger log = LoggerFactory.getLogger(FunctionCallingExecutor.class);

    private final ChatModel chatModel;
    private final List<FunctionCallback> callbacks;
    private final int maxIterations;

    public FunctionCallingExecutor(ChatModel chatModel, ToolRegistry registry) {
        this(chatModel, registry, 5);
    }

    public FunctionCallingExecutor(ChatModel chatModel, ToolRegistry registry, int maxIterations) {
        this.chatModel = chatModel;
        this.callbacks = registry == null
                ? List.of()
                : registry.all().stream()
                        .map(ToolFunctionCallback::new)
                        .collect(Collectors.toList());
        this.maxIterations = maxIterations;
    }

    /**
     * 执行一轮 Function Calling 循环，直到 LLM 不再调用工具。
     *
     * @param systemPrompt 系统提示
     * @param userPrompt   用户问题
     * @return LLM 最终回答
     */
    public String execute(String systemPrompt, String userPrompt) {
        List<Message> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(new org.springframework.ai.chat.messages.SystemMessage(systemPrompt));
        }
        messages.add(new UserMessage(userPrompt));

        FunctionCallingOptions options = FunctionCallingOptions.builder()
                .withFunctionCallbacks(callbacks)
                .build();

        for (int i = 0; i < maxIterations; i++) {
            Prompt prompt = new Prompt(messages, options);
            ChatResponse response = chatModel.call(prompt);

            if (response == null || response.getResult() == null
                    || response.getResult().getOutput() == null) {
                return "";
            }

            // 检查是否有工具调用
            if (hasToolCalls(response)) {
                messages.add(response.getResult().getOutput());
                List<ToolResponseMessage.ToolResponse> toolResponses = executeToolCalls(response);
                messages.add(new ToolResponseMessage(toolResponses));
                log.debug("Function calling iteration {}, executed {} tools", i + 1, toolResponses.size());
            } else {
                return response.getResult().getOutput().getContent();
            }
        }

        log.warn("Function calling reached max iterations {}", maxIterations);
        return "";
    }

    private boolean hasToolCalls(ChatResponse response) {
        var output = response.getResult().getOutput();
        return output instanceof org.springframework.ai.chat.messages.AssistantMessage
                && ((org.springframework.ai.chat.messages.AssistantMessage) output).hasToolCalls();
    }

    private List<ToolResponseMessage.ToolResponse> executeToolCalls(ChatResponse response) {
        var toolCalls = response.getResult().getOutput().getToolCalls();
        return toolCalls.stream()
                .map(toolCall -> {
                    String name = toolCall.name();
                    String args = toolCall.arguments();

                    FunctionCallback callback = callbacks.stream()
                            .filter(cb -> cb.getName().equals(name))
                            .findFirst()
                            .orElse(null);

                    String result;
                    if (callback == null) {
                        result = "{\"error\":\"tool not found\"}";
                    } else {
                        result = callback.call(args);
                    }

                    return new ToolResponseMessage.ToolResponse(toolCall.id(), name, result);
                })
                .collect(Collectors.toList());
    }
}

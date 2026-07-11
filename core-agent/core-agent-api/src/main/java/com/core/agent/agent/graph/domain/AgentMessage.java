package com.core.agent.agent.graph.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 状态图中的消息对象。
 *
 * <p>比 {@link com.core.agent.memory.domain.MemoryMessage} 更轻量，
 * 专门用于在状态图执行过程中传递上下文，支持元数据扩展。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentMessage {

    /** 消息角色：system / user / assistant / tool / thought / observation / evaluation */
    private String role;

    /** 消息内容 */
    private String content;

    /** 扩展元数据，例如工具名、token 数、延迟等 */
    @Builder.Default
    private Map<String, Object> metadata = Map.of();

    public static AgentMessage system(String content) {
        return AgentMessage.builder().role("system").content(content).build();
    }

    public static AgentMessage user(String content) {
        return AgentMessage.builder().role("user").content(content).build();
    }

    public static AgentMessage assistant(String content) {
        return AgentMessage.builder().role("assistant").content(content).build();
    }

    public static AgentMessage tool(String content, String toolName) {
        return AgentMessage.builder()
                .role("tool")
                .content(content)
                .metadata(Map.of("toolName", toolName))
                .build();
    }

    public static AgentMessage thought(String content) {
        return AgentMessage.builder().role("thought").content(content).build();
    }

    public static AgentMessage observation(String content) {
        return AgentMessage.builder().role("observation").content(content).build();
    }

    public static AgentMessage evaluation(String content) {
        return AgentMessage.builder().role("evaluation").content(content).build();
    }
}

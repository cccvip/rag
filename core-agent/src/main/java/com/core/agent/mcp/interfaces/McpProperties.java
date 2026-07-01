package com.core.agent.mcp.interfaces;
import com.core.agent.tool.domain.ToolDefinition;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP Gateway 配置项。
 *
 * <p>对应 YAML 配置中的 {@code mcp.gateway} 节点，Spring Boot 启动时自动绑定。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ConfigurationProperties(prefix = "mcp.gateway")
public class McpProperties {

    /** 工具定义列表。 */
    @Builder.Default
    private List<ToolDefinition> tools = new ArrayList<>();

    /** 静态服务地址映射：serviceName -> baseUrl。 */
    @Builder.Default
    private Map<String, String> services = new HashMap<>();

    /** 工具调用超时（毫秒），默认 30 秒。 */
    @Builder.Default
    private int timeoutMs = 30_000;

    public int getTimeoutMs() {
        return timeoutMs <= 0 ? 30_000 : timeoutMs;
    }
}

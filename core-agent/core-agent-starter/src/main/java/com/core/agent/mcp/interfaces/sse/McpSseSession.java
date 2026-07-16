package com.core.agent.mcp.interfaces.sse;

import lombok.AllArgsConstructor;
import lombok.Data;
import reactor.core.publisher.Sinks;

/**
 * MCP SSE 会话。
 */
@Data
@AllArgsConstructor
public class McpSseSession {

    private final String sessionId;
    private final Sinks.Many<String> sink;
}

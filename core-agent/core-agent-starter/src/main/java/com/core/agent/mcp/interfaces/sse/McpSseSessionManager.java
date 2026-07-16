package com.core.agent.mcp.interfaces.sse;

import org.springframework.stereotype.Component;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP SSE 会话管理器。
 */
@Component
public class McpSseSessionManager {

    private final Map<String, McpSseSession> sessions = new ConcurrentHashMap<>();

    public McpSseSession createSession() {
        String sessionId = UUID.randomUUID().toString();
        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
        McpSseSession session = new McpSseSession(sessionId, sink);
        sessions.put(sessionId, session);
        return session;
    }

    public McpSseSession getSession(String sessionId) {
        return sessions.get(sessionId);
    }

    public void removeSession(String sessionId) {
        sessions.remove(sessionId);
    }
}

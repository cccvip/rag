package com.core.agent.tenant;

import java.time.Instant;

/**
 * 单次 Agent 调用记录 — 调用后回写用量。
 */
public class AgentCallRecord {

    private final String sessionId;
    private final long tokens;
    private final long latencyMs;
    private final long toolCallCount;
    private final Instant timestamp;

    public AgentCallRecord(String sessionId, long tokens,
                           long latencyMs, long toolCallCount) {
        this(sessionId, tokens, latencyMs, toolCallCount, Instant.now());
    }

    public AgentCallRecord(String sessionId, long tokens,
                           long latencyMs, long toolCallCount,
                           Instant timestamp) {
        this.sessionId = sessionId;
        this.tokens = tokens;
        this.latencyMs = latencyMs;
        this.toolCallCount = toolCallCount;
        this.timestamp = timestamp;
    }

    public String getSessionId() {
        return sessionId;
    }

    public long getTokens() {
        return tokens;
    }

    public long getLatencyMs() {
        return latencyMs;
    }

    public long getToolCallCount() {
        return toolCallCount;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    @Override
    public String toString() {
        return "AgentCallRecord{" +
                "sessionId='" + sessionId + '\'' +
                ", tokens=" + tokens +
                ", latencyMs=" + latencyMs +
                ", toolCallCount=" + toolCallCount +
                '}';
    }
}

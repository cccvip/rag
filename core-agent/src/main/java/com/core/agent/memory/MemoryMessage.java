package com.core.agent.memory;

/**
 * 记忆消息模型，对应会话中的一条消息。
 */
public class MemoryMessage {

    private final String role;
    private final String content;
    private final long timestamp;
    private final int tokenCount;

    public MemoryMessage(String role, String content, long timestamp, int tokenCount) {
        this.role = role;
        this.content = content;
        this.timestamp = timestamp;
        this.tokenCount = tokenCount;
    }

    public String getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public int getTokenCount() {
        return tokenCount;
    }

    @Override
    public String toString() {
        return "MemoryMessage{role='" + role + "', tokenCount=" + tokenCount + "}";
    }
}

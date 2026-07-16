package com.core.agent.memory.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 跨 Agent 共享记忆消息。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SharedMemoryMessage {

    private String id;
    private MemoryScope scope;
    private String scopeKey;
    private String agentId;
    private String role;
    private String content;
    private int tokenCount;
    private Instant createdAt;
}

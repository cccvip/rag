package com.core.agent.memory.domain;

/**
 * 共享记忆的作用域。
 */
public enum MemoryScope {
    /** 单次会话内有效 */
    SESSION,
    /** 同一用户跨会话共享 */
    USER,
    /** 同一租户内共享 */
    TENANT,
    /** 同一 Agent 实例共享 */
    AGENT
}

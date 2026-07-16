package com.core.agent.memory.infrastructure;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.core.agent.memory.domain.MemoryScope;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 共享记忆 MyBatis-Plus 实体。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("agent_shared_memory")
public class SharedMemoryEntity {

    @TableId(type = IdType.INPUT)
    private String id;

    private MemoryScope scope;

    private String scopeKey;

    private String agentId;

    private String role;

    private String content;

    private int tokenCount;

    private Instant createdAt;
}

package com.core.agent.checkpoint.infrastructure;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Checkpoint MyBatis-Plus 实体。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("agent_checkpoint")
public class CheckpointEntity {

    @TableId(type = IdType.INPUT)
    private String token;

    private String stateJson;

    private Instant createdAt;

    private String decision;

    private String comment;
}

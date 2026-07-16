package com.core.agent.agent.interfaces;

import lombok.Data;

/**
 * 任务状态查询响应。
 */
@Data
public class TaskStatusResponse {

    private String taskId;
    private String status;
    private String checkpointToken;
    private String answer;
}

package com.core.agent.agent.interfaces;

import lombok.Data;

import java.util.List;

/**
 * Agent 执行响应。
 */
@Data
public class RunResponse {

    private String taskId;
    private String status;
    private String answer;
    private String confidence;
    private List<String> citations;
    private String checkpointToken;
    private String errorMessage;
}

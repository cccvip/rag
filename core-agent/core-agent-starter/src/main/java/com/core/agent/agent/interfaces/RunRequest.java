package com.core.agent.agent.interfaces;

import lombok.Data;

/**
 * Agent 执行请求。
 */
@Data
public class RunRequest {

    private String sessionId;
    private String scene;
    private String query;
    private String strategy;
    private boolean requireApproval;
}

package com.core.agent.multiagent.domain;

/**
 * A2A 协议 Gateway SPI。
 */
public interface A2aGateway {

    A2aTask sendTask(String agentEndpoint, String query);

    A2aTask getTaskStatus(String agentEndpoint, String taskId);
}

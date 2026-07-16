package com.core.agent.multiagent.domain;

/**
 * Worker 执行器。
 */
public interface Worker {

    String agentId();

    WorkerResult execute(SubTask subTask);
}

package com.core.agent.multiagent.domain;

/**
 * 根据子任务解析对应的 Worker。
 */
public interface WorkerResolver {

    Worker resolve(SubTask subTask);
}

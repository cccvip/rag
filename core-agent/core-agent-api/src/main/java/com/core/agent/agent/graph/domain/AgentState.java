package com.core.agent.agent.graph.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 状态图执行过程中的不可变状态快照。
 *
 * <p>所有变更通过 {@link #withMessage} / {@link #withVariable} / {@link #withCurrentNode}
 * 等方法返回新副本，保证执行过程可追溯、可重放。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentState {

    /** 当前执行到的节点名称 */
    @Builder.Default
    private String currentNode = "start";

    /** 状态中的消息历史 */
    @Builder.Default
    private List<AgentMessage> messages = new ArrayList<>();

    /** 中间变量，用于节点间传递结构化数据 */
    @Builder.Default
    private Map<String, Object> variables = new HashMap<>();

    /** 执行状态 */
    @Builder.Default
    private Status status = Status.RUNNING;

    /** 当状态为 AWAITING_APPROVAL 时，用于恢复的 checkpoint token */
    private String checkpointToken;

    /** 扩展元数据：traceId、tenantId、userId、scene 等 */
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();

    /** 当状态为 ERROR 时的错误信息 */
    private String errorMessage;

    public enum Status {
        RUNNING,
        COMPLETED,
        HALTED,
        AWAITING_APPROVAL,
        ERROR
    }

    /**
     * 创建初始状态。
     */
    public static AgentState initial(String traceId, String tenantId, String userId, String scene) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("traceId", traceId);
        meta.put("tenantId", tenantId);
        meta.put("userId", userId);
        meta.put("scene", scene);
        return AgentState.builder()
                .currentNode("start")
                .messages(new ArrayList<>())
                .variables(new HashMap<>())
                .status(Status.RUNNING)
                .metadata(meta)
                .build();
    }

    /**
     * 追加一条消息，返回新状态。
     */
    public AgentState withMessage(AgentMessage message) {
        AgentState copy = copy();
        copy.messages.add(message);
        return copy;
    }

    /**
     * 追加多条消息，返回新状态。
     */
    public AgentState withMessages(List<AgentMessage> newMessages) {
        AgentState copy = copy();
        copy.messages.addAll(newMessages);
        return copy;
    }

    /**
     * 设置变量，返回新状态。
     */
    public AgentState withVariable(String key, Object value) {
        AgentState copy = copy();
        copy.variables.put(key, value);
        return copy;
    }

    /**
     * 设置当前节点，返回新状态。
     */
    public AgentState withCurrentNode(String node) {
        AgentState copy = copy();
        copy.currentNode = node;
        return copy;
    }

    /**
     * 标记为完成并设置最终答案，返回新状态。
     */
    public AgentState completed(String finalAnswer) {
        AgentState copy = copy();
        copy.status = Status.COMPLETED;
        copy.variables.put("finalAnswer", finalAnswer);
        return copy;
    }

    /**
     * 标记为等待人工审批，返回新状态。
     */
    public AgentState awaitingApproval(String checkpointToken) {
        AgentState copy = copy();
        copy.status = Status.AWAITING_APPROVAL;
        copy.checkpointToken = checkpointToken;
        return copy;
    }

    /**
     * 标记为出错，返回新状态。
     */
    public AgentState error(String errorMessage) {
        AgentState copy = copy();
        copy.status = Status.ERROR;
        copy.errorMessage = errorMessage;
        return copy;
    }

    /**
     * 标记为暂停（非错误终止）。
     */
    public AgentState halted() {
        AgentState copy = copy();
        copy.status = Status.HALTED;
        return copy;
    }

    /**
     * 从 checkpoint 恢复时，更新状态。
     */
    public AgentState resumeFromCheckpoint() {
        AgentState copy = copy();
        copy.status = Status.RUNNING;
        copy.checkpointToken = null;
        return copy;
    }

    /**
     * 获取元数据中的字段。
     */
    @SuppressWarnings("unchecked")
    public <T> T getMetadata(String key) {
        return (T) metadata.get(key);
    }

    /**
     * 获取变量。
     */
    @SuppressWarnings("unchecked")
    public <T> T getVariable(String key) {
        return (T) variables.get(key);
    }

    /**
     * 深拷贝当前状态。
     */
    public AgentState copy() {
        return AgentState.builder()
                .currentNode(this.currentNode)
                .messages(new ArrayList<>(this.messages))
                .variables(new HashMap<>(this.variables))
                .status(this.status)
                .checkpointToken(this.checkpointToken)
                .metadata(new HashMap<>(this.metadata))
                .errorMessage(this.errorMessage)
                .build();
    }

    /**
     * 只读视图下的消息列表。
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public List<AgentMessage> getMessagesView() {
        return Collections.unmodifiableList(messages);
    }

    /**
     * 只读视图下的变量表。
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public Map<String, Object> getVariablesView() {
        return Collections.unmodifiableMap(variables);
    }
}

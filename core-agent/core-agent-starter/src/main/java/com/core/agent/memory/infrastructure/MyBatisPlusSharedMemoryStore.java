package com.core.agent.memory.infrastructure;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.core.agent.memory.domain.MemoryScope;
import com.core.agent.memory.domain.SharedMemoryMessage;
import com.core.agent.memory.domain.SharedMemoryStore;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 基于 MyBatis-Plus 的跨 Agent 共享记忆持久化实现。
 */
@Component
public class MyBatisPlusSharedMemoryStore implements SharedMemoryStore {

    private final SharedMemoryMapper sharedMemoryMapper;

    public MyBatisPlusSharedMemoryStore(SharedMemoryMapper sharedMemoryMapper) {
        this.sharedMemoryMapper = sharedMemoryMapper;
    }

    @Override
    public void save(SharedMemoryMessage message) {
        sharedMemoryMapper.insert(toEntity(message));
    }

    @Override
    public List<SharedMemoryMessage> findByScope(MemoryScope scope, String scopeKey, String agentId, int limit) {
        QueryWrapper<SharedMemoryEntity> wrapper = new QueryWrapper<>();
        wrapper.eq("scope", scope.name())
                .eq("scope_key", scopeKey)
                .eq("agent_id", agentId)
                .orderByDesc("created_at")
                .last("LIMIT " + limit);

        return sharedMemoryMapper.selectList(wrapper).stream()
                .map(this::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public void delete(String id) {
        sharedMemoryMapper.deleteById(id);
    }

    private SharedMemoryEntity toEntity(SharedMemoryMessage message) {
        return SharedMemoryEntity.builder()
                .id(message.getId())
                .scope(message.getScope())
                .scopeKey(message.getScopeKey())
                .agentId(message.getAgentId())
                .role(message.getRole())
                .content(message.getContent())
                .tokenCount(message.getTokenCount())
                .createdAt(message.getCreatedAt())
                .build();
    }

    private SharedMemoryMessage toDomain(SharedMemoryEntity entity) {
        return SharedMemoryMessage.builder()
                .id(entity.getId())
                .scope(entity.getScope())
                .scopeKey(entity.getScopeKey())
                .agentId(entity.getAgentId())
                .role(entity.getRole())
                .content(entity.getContent())
                .tokenCount(entity.getTokenCount())
                .createdAt(entity.getCreatedAt())
                .build();
    }
}

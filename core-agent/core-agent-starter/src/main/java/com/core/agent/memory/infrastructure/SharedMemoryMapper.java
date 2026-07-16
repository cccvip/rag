package com.core.agent.memory.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 共享记忆 MyBatis-Plus Mapper。
 */
@Mapper
public interface SharedMemoryMapper extends BaseMapper<SharedMemoryEntity> {
}

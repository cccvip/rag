package com.core.agent.context;

import java.util.Comparator;
import java.util.List;

/**
 * 上下文裁剪策略。
 *
 * 业务场景通过实现此接口，决定不同类型内容的保留优先级。
 * 当上下文超过 Token 预算时，ContextManager 会按优先级从低到高裁剪。
 */
public interface ContextStrategy {

    /**
     * 内容块保留优先级，数字越小越优先保留。
     */
    int priority(MessageBlock block);

    /**
     * 超预算时的裁剪逻辑。
     *
     * 默认实现：按优先级从低到高排序，优先丢弃低优先级内容。
     */
    default List<MessageBlock> trim(List<MessageBlock> blocks, int overBudget) {
        List<MessageBlock> sorted = blocks.stream()
                .sorted(Comparator.comparingInt(this::priority).reversed())
                .toList();

        int removed = 0;
        List<MessageBlock> result = new java.util.ArrayList<>();
        for (MessageBlock block : sorted) {
            if (removed < overBudget) {
                removed += block.getTokenCount();
                continue;
            }
            result.add(block);
        }
        return result;
    }

    /**
     * 策略名称，用于日志和配置识别。
     */
    default String name() {
        return getClass().getSimpleName();
    }
}

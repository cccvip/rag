package com.core.agent.infopricing.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 信息定价时间线报告。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PricingTimeline {

    /** 比赛/市场名称 */
    private String marketName;

    /** 原始价格数据 */
    private List<MarketDataPoint> marketData;

    /** 检测到的异常点 */
    private List<AnomalyPoint> anomalies;

    /** 事件归因结果 */
    private List<Attribution> attributions;

    /** 最终报告文本 */
    private String report;

    /**
     * 单个异常点的事件归因。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Attribution {

        /** 跳变时间 */
        private String timestamp;

        /** 最可能关联的事件标题 */
        private String matchedEventTitle;

        /** 归因解释 */
        private String explanation;

        /** 置信度：HIGH / MEDIUM / LOW */
        private String confidence;
    }
}

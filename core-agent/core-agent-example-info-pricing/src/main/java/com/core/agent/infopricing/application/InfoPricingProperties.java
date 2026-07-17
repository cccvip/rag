package com.core.agent.infopricing.application;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 信息定价分析配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "info-pricing")
public class InfoPricingProperties {

    /** 是否启用 mock 数据（MVP 默认 true） */
    private boolean mockEnabled = true;

    /** 异常检测阈值倍数（标准差倍数） */
    private double anomalyThreshold = 1.5;

    /** 是否启用归因 mock（测试或无 API key 时使用） */
    private boolean attributionMockEnabled = false;

    /** 预置新闻事件时间线 */
    private List<NewsEventConfig> events = new ArrayList<>();

    @Data
    public static class NewsEventConfig {
        private String timestamp;
        private String title;
        private String description;
        private String category;
    }
}

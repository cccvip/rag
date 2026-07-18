package com.core.agent.infopricing.application;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

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

    /** 是否启用新闻搜索 mock */
    private boolean newsSearchMockEnabled = true;

    /** GNews API Key（从环境变量 GNEWS_API_KEY 注入） */
    private String gnewsApiKey;

    /** GNews 单次搜索最大返回条数 */
    private int gnewsMaxResults = 10;

    /** GNews 每分钟调用上限（免费额度建议 5） */
    private int gnewsRateLimitPerMinute = 5;

    /** GNews 每日调用上限（免费额度建议 100） */
    private int gnewsDailyQuota = 100;
}

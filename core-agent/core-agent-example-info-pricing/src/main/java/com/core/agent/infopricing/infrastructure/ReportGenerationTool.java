package com.core.agent.infopricing.infrastructure;

import com.core.agent.infopricing.domain.AnomalyPoint;
import com.core.agent.infopricing.domain.MarketDataPoint;
import com.core.agent.infopricing.domain.PricingTimeline;
import com.core.agent.shared.model.RiskLevel;
import com.core.agent.tool.domain.Tool;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 信息定价报告生成工具。
 */
@Component
public class ReportGenerationTool implements Tool {

    private final ObjectMapper objectMapper;
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    public ReportGenerationTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "report_generation";
    }

    @Override
    public String description() {
        return "根据价格数据、异常点和事件归因生成 Markdown 报告。输入：JSON 对象 {marketName, marketData, anomalies, attributions}；输出：Markdown 字符串。";
    }

    @Override
    public RiskLevel riskLevel() {
        return RiskLevel.LOW;
    }

    @Override
    public String execute(String input) {
        try {
            ReportInput reportInput = objectMapper.readValue(input, ReportInput.class);
            return generateMarkdown(reportInput);
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    private String generateMarkdown(ReportInput input) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 信息定价分析报告\n\n");
        sb.append("**市场：** ").append(input.getMarketName()).append("\n\n");

        sb.append("## 一、价格走势概览\n\n");
        sb.append("共采集 ").append(input.getMarketData().size()).append(" 个价格数据点。\n\n");
        sb.append("| 时间 | 价格 | 交易量 |\n");
        sb.append("| --- | --- | --- |\n");
        for (MarketDataPoint point : input.getMarketData()) {
            sb.append("| ").append(format(point.getTimestamp()))
                    .append(" | ").append(point.getPrice())
                    .append(" | ").append(point.getVolume())
                    .append(" |\n");
        }
        sb.append("\n");

        sb.append("## 二、异常跳变点\n\n");
        if (input.getAnomalies().isEmpty()) {
            sb.append("未检测到显著异常点。\n\n");
        } else {
            sb.append("检测到 ").append(input.getAnomalies().size()).append(" 个异常点：\n\n");
            sb.append("| 时间 | 跳变前 | 跳变后 | 变化幅度 |\n");
            sb.append("| --- | --- | --- | --- |\n");
            for (AnomalyPoint anomaly : input.getAnomalies()) {
                sb.append("| ").append(format(anomaly.getTimestamp()))
                        .append(" | ").append(anomaly.getPriceBefore())
                        .append(" | ").append(anomaly.getPriceAfter())
                        .append(" | ").append(anomaly.getChangePercent()).append("%")
                        .append(" |\n");
            }
            sb.append("\n");
        }

        sb.append("## 三、事件归因\n\n");
        if (input.getAttributions().isEmpty()) {
            sb.append("未找到明确的事件归因。\n\n");
        } else {
            for (PricingTimeline.Attribution attr : input.getAttributions()) {
                sb.append("### ").append(format(Instant.parse(attr.getTimestamp()))).append("\n\n");
                sb.append("- **关联事件：** ").append(attr.getMatchedEventTitle()).append("\n");
                sb.append("- **置信度：** ").append(attr.getConfidence()).append("\n");
                sb.append("- **解释：** ").append(attr.getExplanation()).append("\n\n");
            }
        }

        sb.append("## 四、结论\n\n");
        sb.append("Polymarket 价格变动与公开新闻事件存在可解释的相关性。");
        sb.append("通过对比异常点与事件时间线，可以识别市场对特定信息的反应速度和定价方向。\n");

        return sb.toString();
    }

    private String format(Instant instant) {
        return FORMATTER.format(instant);
    }

    @Data
    public static class ReportInput {
        private String marketName;
        private List<MarketDataPoint> marketData;
        private List<AnomalyPoint> anomalies;
        private List<PricingTimeline.Attribution> attributions;
    }
}

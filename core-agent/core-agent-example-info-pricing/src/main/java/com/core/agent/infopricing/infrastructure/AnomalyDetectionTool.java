package com.core.agent.infopricing.infrastructure;

import com.core.agent.infopricing.domain.AnomalyPoint;
import com.core.agent.infopricing.domain.MarketDataPoint;
import com.core.agent.shared.model.RiskLevel;
import com.core.agent.tool.domain.Tool;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 价格异常跳变检测工具。
 *
 * <p>基于相邻价格变化率，结合均值与标准差，识别显著跳变点。</p>
 */
@Component
public class AnomalyDetectionTool implements Tool {

    private final Gson gson;

    public AnomalyDetectionTool(Gson gson) {
        this.gson = gson;
    }

    @Override
    public String name() {
        return "anomaly_detection";
    }

    @Override
    public String description() {
        return "检测价格时间序列中的异常跳变点。输入：JSON 数组 [timestamp, price, volume]；输出：异常点列表 [timestamp, priceBefore, priceAfter, change, changePercent]。";
    }

    @Override
    public RiskLevel riskLevel() {
        return RiskLevel.LOW;
    }

    @Override
    public String execute(String input) {
        try {
            List<MarketDataPoint> points = gson.fromJson(input, new TypeToken<List<MarketDataPoint>>() {
            }.getType());
            List<AnomalyPoint> anomalies = detect(points);
            return gson.toJson(anomalies);
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    private List<AnomalyPoint> detect(List<MarketDataPoint> points) {
        List<AnomalyPoint> anomalies = new ArrayList<>();
        if (points == null || points.size() < 3) {
            return anomalies;
        }

        // 计算相邻变化率
        double[] returns = new double[points.size() - 1];
        for (int i = 0; i < returns.length; i++) {
            BigDecimal before = points.get(i).getPrice();
            BigDecimal after = points.get(i + 1).getPrice();
            returns[i] = after.subtract(before)
                    .divide(before, 6, RoundingMode.HALF_UP)
                    .doubleValue();
        }

        DescriptiveStatistics stats = new DescriptiveStatistics(returns);
        double mean = stats.getMean();
        double std = stats.getStandardDeviation();
        double threshold = std > 0 ? 1.5 * std : 0.05;

        for (int i = 0; i < returns.length; i++) {
            if (Math.abs(returns[i] - mean) > threshold) {
                BigDecimal before = points.get(i).getPrice();
                BigDecimal after = points.get(i + 1).getPrice();
                anomalies.add(AnomalyPoint.builder()
                        .timestamp(points.get(i + 1).getTimestamp())
                        .priceBefore(before)
                        .priceAfter(after)
                        .change(after.subtract(before))
                        .changePercent(BigDecimal.valueOf(returns[i] * 100).setScale(2, RoundingMode.HALF_UP))
                        .build());
            }
        }
        return anomalies;
    }
}

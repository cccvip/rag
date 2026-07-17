package com.core.agent.infopricing.infrastructure;

import com.core.agent.infopricing.application.InfoPricingProperties;
import com.core.agent.infopricing.domain.MarketDataPoint;
import com.core.agent.shared.model.RiskLevel;
import com.core.agent.tool.domain.Tool;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Polymarket 价格数据获取工具。
 *
 * <p>MVP 阶段支持两种模式：</p>
 * <ul>
 *   <li>mock 模式：返回预设的模拟价格曲线</li>
 *   <li>api 模式：调用 Polymarket Gamma API（需市场存在且可公开访问）</li>
 * </ul>
 */
@Component
public class PolymarketDataTool implements Tool {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final Gson gson;
    private final boolean mockEnabled;

    public PolymarketDataTool(Gson gson, InfoPricingProperties properties) {
        this.gson = gson;
        this.mockEnabled = properties.isMockEnabled();
    }

    @Override
    public String name() {
        return "polymarket_data";
    }

    @Override
    public String description() {
        return "获取 Polymarket 某场比赛的价格时间序列。输入：marketId；输出：JSON 数组 [timestamp, price, volume]。";
    }

    @Override
    public RiskLevel riskLevel() {
        return RiskLevel.LOW;
    }

    @Override
    public String execute(String input) {
        try {
            List<MarketDataPoint> points = mockEnabled ? mockData() : fetchFromApi(input);
            return gson.toJson(points);
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    private List<MarketDataPoint> mockData() {
        List<MarketDataPoint> points = new ArrayList<>();
        Instant start = Instant.parse("2024-07-13T18:00:00Z");
        // 模拟一场足球赛的价格曲线：赛前 0.48，首发公布跳升到 0.62，随后回落
        double[] prices = {
                0.48, 0.48, 0.49, 0.50, 0.51, 0.55, 0.62, 0.60, 0.58, 0.57,
                0.56, 0.55, 0.54, 0.53, 0.52, 0.51, 0.50, 0.49, 0.48, 0.47
        };
        for (int i = 0; i < prices.length; i++) {
            points.add(MarketDataPoint.builder()
                    .timestamp(start.plusSeconds(i * 300L))
                    .price(BigDecimal.valueOf(prices[i]))
                    .volume(BigDecimal.valueOf(1000 + i * 50))
                    .build());
        }
        return points;
    }

    private List<MarketDataPoint> fetchFromApi(String marketId) throws Exception {
        // Polymarket Gamma API 示例端点（可能随官方调整而变化）
        String url = "https://gamma-api.polymarket.com/events?slug=" + marketId;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("HTTP " + response.statusCode() + ": " + response.body());
        }
        // 实际解析需根据 Polymarket 响应结构调整
        return mockData();
    }
}

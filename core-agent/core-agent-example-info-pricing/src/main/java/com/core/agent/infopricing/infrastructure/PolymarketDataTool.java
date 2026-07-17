package com.core.agent.infopricing.infrastructure;

import com.core.agent.infopricing.application.InfoPricingProperties;
import com.core.agent.infopricing.domain.MarketDataPoint;
import com.core.agent.shared.model.RiskLevel;
import com.core.agent.tool.domain.Tool;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import lombok.extern.slf4j.Slf4j;
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
 * <p>通过 Gamma API 发现市场，通过 CLOB API 获取价格历史。</p>
 * <p>MVP 阶段如果 API 不可用或返回空数据，自动回退到 mock 数据。</p>
 */
@Slf4j
@Component
public class PolymarketDataTool implements Tool {

    private static final String GAMMA_API_BASE = "https://gamma-api.polymarket.com";
    private static final String CLOB_API_BASE = "https://clob.polymarket.com";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
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
        return "获取 Polymarket 某场预测市场的价格时间序列。输入：market slug（如 will-spain-win-the-2026-fifa-world-cup-963）；输出：JSON 数组 [timestamp, price, volume]。";
    }

    @Override
    public RiskLevel riskLevel() {
        return RiskLevel.LOW;
    }

    @Override
    public String execute(String input) {
        try {
            List<MarketDataPoint> points;
            if (mockEnabled) {
                log.info("Mock mode enabled, returning mock data for slug: {}", input);
                points = mockData();
            } else {
                points = fetchFromApi(input);
                if (points.isEmpty()) {
                    log.warn("API returned empty data for slug: {}, falling back to mock", input);
                    points = mockData();
                }
            }
            return gson.toJson(points);
        } catch (Exception e) {
            log.error("Failed to fetch Polymarket data for slug: {}", input, e);
            return "Error: " + e.getMessage();
        }
    }

    /**
     * 真实 API 获取流程：
     * 1. Gamma API /markets?slug=... 获取市场元数据
     * 2. 解析 clobTokenIds（取第一个代表 Yes 的 token）
     * 3. CLOB API /prices-history 获取历史价格
     */
    private List<MarketDataPoint> fetchFromApi(String slug) throws Exception {
        String gammaUrl = GAMMA_API_BASE + "/markets?slug=" + slug;
        JsonArray markets = getJsonArray(gammaUrl);
        if (markets == null || markets.isEmpty()) {
            throw new RuntimeException("Market not found for slug: " + slug);
        }

        JsonObject market = markets.get(0).getAsJsonObject();
        String tokenIdsJson = market.has("clobTokenIds") ? market.get("clobTokenIds").getAsString() : null;
        if (tokenIdsJson == null || tokenIdsJson.isBlank()) {
            throw new RuntimeException("No clobTokenIds found for market: " + slug);
        }

        JsonArray tokenIds = JsonParser.parseString(tokenIdsJson).getAsJsonArray();
        if (tokenIds.isEmpty()) {
            throw new RuntimeException("Empty clobTokenIds for market: " + slug);
        }

        // 取第一个 token id（通常对应 Yes / 主队 / 第一个 outcome）
        String tokenId = tokenIds.get(0).getAsString();
        log.info("Fetching price history for tokenId: {}", tokenId);

        String clobUrl = CLOB_API_BASE + "/prices-history?market=" + tokenId
                + "&interval=1w&fidelity=300";
        JsonObject historyResponse = getJsonObject(clobUrl);
        JsonArray history = historyResponse.getAsJsonArray("history");

        List<MarketDataPoint> points = new ArrayList<>();
        if (history != null) {
            for (JsonElement element : history) {
                JsonObject item = element.getAsJsonObject();
                long timestampSeconds = item.get("t").getAsLong();
                double price = item.get("p").getAsDouble();
                points.add(MarketDataPoint.builder()
                        .timestamp(Instant.ofEpochSecond(timestampSeconds))
                        .price(BigDecimal.valueOf(price))
                        .volume(BigDecimal.ZERO) // prices-history 不返回成交量
                        .build());
            }
        }

        return points;
    }

    private JsonArray getJsonArray(String url) throws Exception {
        String body = get(url);
        return gson.fromJson(body, JsonArray.class);
    }

    private JsonObject getJsonObject(String url) throws Exception {
        String body = get(url);
        return gson.fromJson(body, JsonObject.class);
    }

    private String get(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("HTTP " + response.statusCode() + ": " + response.body());
        }
        return response.body();
    }

    /**
     * Mock 数据：模拟一场足球赛的价格曲线。
     */
    private List<MarketDataPoint> mockData() {
        List<MarketDataPoint> points = new ArrayList<>();
        Instant start = Instant.parse("2024-07-13T18:00:00Z");
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
}

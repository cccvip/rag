package com.core.agent.infopricing.application;

import com.core.agent.agent.graph.domain.AgentGraph;
import com.core.agent.agent.graph.domain.AgentNode;
import com.core.agent.agent.graph.domain.AgentState;
import com.core.agent.agent.graph.domain.GraphResult;
import com.core.agent.agent.graph.domain.NodeContext;
import com.core.agent.infopricing.domain.AnomalyPoint;
import com.core.agent.infopricing.domain.MarketDataPoint;
import com.core.agent.infopricing.domain.PricingTimeline;
import com.core.agent.infopricing.infrastructure.AnomalyDetectionTool;
import com.core.agent.infopricing.infrastructure.GNewsSearchTool;
import com.core.agent.infopricing.infrastructure.MockNewsSearchTool;
import com.core.agent.infopricing.infrastructure.NewsAttributionTool;
import com.core.agent.infopricing.infrastructure.NewsArticle;
import com.core.agent.infopricing.infrastructure.NewsSearchTool;
import com.core.agent.infopricing.infrastructure.PolymarketDataTool;
import com.core.agent.infopricing.infrastructure.ReportGenerationTool;
import com.core.agent.infopricing.interfaces.AnalyzeRequest;
import com.core.agent.tool.domain.ToolRegistry;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 信息定价分析服务。
 *
 * <p>基于 core-agent 状态图编排完整分析流程：</p>
 * <ol>
 *   <li>获取 Polymarket 价格数据</li>
 *   <li>检测价格异常跳变点</li>
 *   <li>搜索真实新闻</li>
 *   <li>用 LLM 把异常点与新闻事件归因</li>
 *   <li>生成 Markdown 报告</li>
 * </ol>
 */
@Slf4j
@Service
public class InfoPricingService {

    private final PolymarketDataTool polymarketDataTool;
    private final AnomalyDetectionTool anomalyDetectionTool;
    private final NewsSearchTool newsSearchTool;
    private final NewsAttributionTool newsAttributionTool;
    private final ReportGenerationTool reportGenerationTool;
    private final InfoPricingProperties properties;
    private final Gson gson;

    public InfoPricingService(PolymarketDataTool polymarketDataTool,
                              AnomalyDetectionTool anomalyDetectionTool,
                              GNewsSearchTool gNewsSearchTool,
                              MockNewsSearchTool mockNewsSearchTool,
                              NewsAttributionTool newsAttributionTool,
                              ReportGenerationTool reportGenerationTool,
                              InfoPricingProperties properties,
                              Gson gson) {
        this.polymarketDataTool = polymarketDataTool;
        this.anomalyDetectionTool = anomalyDetectionTool;
        this.newsSearchTool = properties.isNewsSearchMockEnabled() ? mockNewsSearchTool : gNewsSearchTool;
        this.newsAttributionTool = newsAttributionTool;
        this.reportGenerationTool = reportGenerationTool;
        this.properties = properties;
        this.gson = gson;
    }

    @SneakyThrows
    public PricingTimeline analyze(AnalyzeRequest request) {
        String sessionId = UUID.randomUUID().toString();

        // 构建状态图
        AgentGraph<NodeContext> graph = AgentGraph.<NodeContext>builder()
                .startNode("fetchData")
                .addNode("fetchData", new FetchDataNode())
                .addNode("detectAnomalies", new DetectAnomaliesNode())
                .addNode("searchNews", new SearchNewsNode())
                .addNode("attributeEvents", new AttributeEventsNode())
                .addNode("generateReport", new GenerateReportNode())
                .addNode("end", new EndNode())
                .addEdge("fetchData", "detectAnomalies")
                .addEdge("detectAnomalies", "searchNews")
                .addEdge("searchNews", "attributeEvents")
                .addEdge("attributeEvents", "generateReport")
                .addEdge("generateReport", "end")
                .endNode("end")
                .maxSteps(15)
                .build();

        // 初始状态
        AgentState initialState = AgentState.builder()
                .currentNode("fetchData")
                .variables(Map.of(
                        "sessionId", sessionId,
                        "marketName", request.getMarketName(),
                        "marketId", request.getMarketId()
                ))
                .build();

        // 节点上下文
        ToolRegistry registry = new ToolRegistry();
        registry.register(polymarketDataTool);
        registry.register(anomalyDetectionTool);
        registry.register(newsAttributionTool);
        registry.register(reportGenerationTool);

        NodeContext ctx = NodeContext.builder()
                .toolRegistry(registry)
                .build();

        GraphResult result = graph.execute(initialState, ctx);

        return PricingTimeline.builder()
                .marketName(request.getMarketName())
                .marketData(gson.fromJson((String) result.getFinalState().getVariable("marketData"), new TypeToken<List<MarketDataPoint>>() {
                }.getType()))
                .anomalies(gson.fromJson((String) result.getFinalState().getVariable("anomalies"), new TypeToken<List<AnomalyPoint>>() {
                }.getType()))
                .attributions(gson.fromJson((String) result.getFinalState().getVariable("attributions"), new TypeToken<List<PricingTimeline.Attribution>>() {
                }.getType()))
                .report(result.getFinalAnswer())
                .build();
    }

    private class FetchDataNode implements AgentNode<NodeContext> {
        @Override
        public String name() {
            return "fetchData";
        }

        @Override
        @SneakyThrows
        public AgentState invoke(AgentState state, NodeContext ctx) {
            String marketId = state.getVariable("marketId");
            String dataJson = polymarketDataTool.execute(marketId);
            if (dataJson.startsWith("Error:")) {
                throw new RuntimeException(dataJson);
            }
            JsonElement element = JsonParser.parseString(dataJson);
            return state.withVariable("marketData", gson.toJson(element));
        }
    }

    private class DetectAnomaliesNode implements AgentNode<NodeContext> {
        @Override
        public String name() {
            return "detectAnomalies";
        }

        @Override
        @SneakyThrows
        public AgentState invoke(AgentState state, NodeContext ctx) {
            String marketData = state.getVariable("marketData");
            String anomaliesJson = anomalyDetectionTool.execute(marketData);
            if (anomaliesJson.startsWith("Error:")) {
                throw new RuntimeException(anomaliesJson);
            }
            JsonElement element = JsonParser.parseString(anomaliesJson);
            return state.withVariable("anomalies", gson.toJson(element));
        }
    }

    private class SearchNewsNode implements AgentNode<NodeContext> {
        @Override
        public String name() {
            return "searchNews";
        }

        @Override
        @SneakyThrows
        public AgentState invoke(AgentState state, NodeContext ctx) {
            List<MarketDataPoint> marketData = gson.fromJson(
                    (String) state.getVariable("marketData"), new TypeToken<List<MarketDataPoint>>() {
                    }.getType());

            Instant earliest = marketData.stream()
                    .min(Comparator.comparing(MarketDataPoint::getTimestamp))
                    .map(MarketDataPoint::getTimestamp)
                    .orElse(Instant.now().minus(Duration.ofDays(7)));

            Instant latest = marketData.stream()
                    .max(Comparator.comparing(MarketDataPoint::getTimestamp))
                    .map(MarketDataPoint::getTimestamp)
                    .orElse(Instant.now());

            // 向前后各扩展 6 小时，覆盖新闻提前/滞后披露
            Instant from = earliest.minus(Duration.ofHours(6));
            Instant to = latest.plus(Duration.ofHours(6));

            String marketName = state.getVariable("marketName");
            String query = buildQuery(marketName);

            String searchToolName = newsSearchTool instanceof GNewsSearchTool ? "GNews" : "Mock";
            log.info("[searchNews] tool={}, marketName='{}', query='{}', priceRange=[{}, {}], searchWindow=[{}, {}]",
                    searchToolName, marketName, query, earliest, latest, from, to);

            List<NewsArticle> articles = newsSearchTool.search(query, from, to);
            log.info("[searchNews] found {} articles", articles.size());
            articles.forEach(a -> log.info("[searchNews] article: {} | {}", a.getPublishedAt(), a.getTitle()));

            return state.withVariable("articles", gson.toJson(articles));
        }

        private String buildQuery(String marketName) {
            if (marketName == null || marketName.isBlank()) {
                return "Polymarket prediction market";
            }
            // 去掉特殊字符，保留关键词
            return marketName.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fa5\\s]", " ").trim();
        }
    }

    private class AttributeEventsNode implements AgentNode<NodeContext> {
        @Override
        public String name() {
            return "attributeEvents";
        }

        @Override
        @SneakyThrows
        public AgentState invoke(AgentState state, NodeContext ctx) {
            String anomalies = state.getVariable("anomalies");
            String articles = state.getVariable("articles");

            NewsAttributionTool.AttributionInput input = new NewsAttributionTool.AttributionInput();
            input.setMarketName(state.getVariable("marketName"));
            input.setAnomalies(gson.fromJson(anomalies, new TypeToken<List<AnomalyPoint>>() {
            }.getType()));
            input.setArticles(gson.fromJson(articles, new TypeToken<List<NewsArticle>>() {
            }.getType()));

            String attributionJson = newsAttributionTool.execute(gson.toJson(input));
            if (attributionJson.startsWith("Error:")) {
                throw new RuntimeException(attributionJson);
            }
            JsonElement element = JsonParser.parseString(attributionJson);
            return state.withVariable("attributions", gson.toJson(element));
        }
    }

    private class GenerateReportNode implements AgentNode<NodeContext> {
        @Override
        public String name() {
            return "generateReport";
        }

        @Override
        @SneakyThrows
        public AgentState invoke(AgentState state, NodeContext ctx) {
            ReportGenerationTool.ReportInput input = new ReportGenerationTool.ReportInput();
            input.setMarketName(state.getVariable("marketName"));
            input.setMarketData(gson.fromJson((String) state.getVariable("marketData"), new TypeToken<List<MarketDataPoint>>() {
            }.getType()));
            input.setAnomalies(gson.fromJson((String) state.getVariable("anomalies"), new TypeToken<List<AnomalyPoint>>() {
            }.getType()));
            input.setAttributions(gson.fromJson((String) state.getVariable("attributions"), new TypeToken<List<PricingTimeline.Attribution>>() {
            }.getType()));

            String report = reportGenerationTool.execute(gson.toJson(input));
            if (report.startsWith("Error:")) {
                throw new RuntimeException(report);
            }
            return state.withVariable("finalAnswer", report);
        }
    }

    private static class EndNode implements AgentNode<NodeContext> {
        @Override
        public String name() {
            return "end";
        }

        @Override
        public AgentState invoke(AgentState state, NodeContext ctx) {
            return state;
        }
    }
}

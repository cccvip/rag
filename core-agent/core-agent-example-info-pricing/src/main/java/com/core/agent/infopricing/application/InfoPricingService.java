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
import com.core.agent.infopricing.infrastructure.NewsAttributionTool;
import com.core.agent.infopricing.infrastructure.PolymarketDataTool;
import com.core.agent.infopricing.infrastructure.ReportGenerationTool;
import com.core.agent.infopricing.interfaces.AnalyzeRequest;
import com.core.agent.tool.domain.ToolRegistry;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import lombok.SneakyThrows;
import org.springframework.stereotype.Service;

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
 *   <li>用 LLM 把异常点与新闻事件归因</li>
 *   <li>生成 Markdown 报告</li>
 * </ol>
 */
@Service
public class InfoPricingService {

    private final PolymarketDataTool polymarketDataTool;
    private final AnomalyDetectionTool anomalyDetectionTool;
    private final NewsAttributionTool newsAttributionTool;
    private final ReportGenerationTool reportGenerationTool;
    private final InfoPricingProperties properties;
    private final Gson gson;

    public InfoPricingService(PolymarketDataTool polymarketDataTool,
                              AnomalyDetectionTool anomalyDetectionTool,
                              NewsAttributionTool newsAttributionTool,
                              ReportGenerationTool reportGenerationTool,
                              InfoPricingProperties properties,
                              Gson gson) {
        this.polymarketDataTool = polymarketDataTool;
        this.anomalyDetectionTool = anomalyDetectionTool;
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
                .addNode("attributeEvents", new AttributeEventsNode())
                .addNode("generateReport", new GenerateReportNode())
                .addNode("end", new EndNode())
                .addEdge("fetchData", "detectAnomalies")
                .addEdge("detectAnomalies", "attributeEvents")
                .addEdge("attributeEvents", "generateReport")
                .addEdge("generateReport", "end")
                .endNode("end")
                .maxSteps(10)
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
            JsonElement element = JsonParser.parseString(anomaliesJson);
            return state.withVariable("anomalies", gson.toJson(element));
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
            List<NewsAttributionTool.NewsEventInput> events = properties.getEvents().stream()
                    .map(e -> {
                        NewsAttributionTool.NewsEventInput event = new NewsAttributionTool.NewsEventInput();
                        event.setTimestamp(java.time.Instant.parse(e.getTimestamp()));
                        event.setTitle(e.getTitle());
                        event.setDescription(e.getDescription());
                        event.setCategory(e.getCategory());
                        return event;
                    })
                    .toList();

            NewsAttributionTool.AttributionInput input = new NewsAttributionTool.AttributionInput();
            input.setAnomalies(gson.fromJson(anomalies, new TypeToken<List<AnomalyPoint>>() {
            }.getType()));
            input.setEvents(events);

            String attributionJson = newsAttributionTool.execute(gson.toJson(input));
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

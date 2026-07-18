package com.core.agent.infopricing.infrastructure;

import com.core.agent.infopricing.application.InfoPricingProperties;
import com.core.agent.infopricing.domain.AnomalyPoint;
import com.core.agent.infopricing.domain.PricingTimeline;
import com.core.agent.shared.model.RiskLevel;
import com.core.agent.tool.domain.Tool;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import lombok.Data;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 新闻事件归因工具。
 *
 * <p>利用 LLM 把价格异常点与新闻列表进行匹配，解释价格跳变原因。</p>
 */
@Component
public class NewsAttributionTool implements Tool {

    private final ChatModel chatModel;
    private final Gson gson;
    private final boolean mockEnabled;

    public NewsAttributionTool(ChatModel chatModel, Gson gson,
                               InfoPricingProperties properties) {
        this.chatModel = chatModel;
        this.gson = gson;
        this.mockEnabled = properties.isAttributionMockEnabled();
    }

    @Override
    public String name() {
        return "news_attribution";
    }

    @Override
    public String description() {
        return "把价格异常点与新闻事件做归因匹配。输入：JSON 对象 {marketName, anomalies, articles}；输出：归因列表。";
    }

    @Override
    public RiskLevel riskLevel() {
        return RiskLevel.LOW;
    }

    @Override
    public String execute(String input) {
        try {
            AttributionInput attributionInput = gson.fromJson(input, AttributionInput.class);

            List<NewsArticle> articles = attributionInput.getArticles();
            if (mockEnabled || articles == null || articles.isEmpty()) {
                return mockAttribution(attributionInput.getAnomalies(), articles);
            }

            return llmAttribution(attributionInput);
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    private String llmAttribution(AttributionInput input) {
        String systemPrompt = """
                你是一位信息定价分析师。你的任务是把 Polymarket 价格异常跳变点与新闻列表进行归因匹配。

                规则：
                1. 对每个异常点，从新闻列表中找出最可能关联的新闻。
                2. 如果找不到明显关联，返回 confidence=LOW 并给出最可能的市场解释。
                3. 解释必须具体：说明新闻如何影响市场预期，而不是泛泛而谈。
                4. 输出必须是纯 JSON 数组，格式如下：
                [
                  {
                    "timestamp": "2024-07-13T18:30:00Z",
                    "matchedEventTitle": "新闻标题",
                    "explanation": "该新闻导致市场对英格兰胜率重新定价...",
                    "confidence": "HIGH"
                  }
                ]
                """;

        String userPrompt = String.format("""
                        市场主题：%s

                        价格异常点：%s

                        相关新闻：%s

                        请输出 JSON 数组。不要输出 markdown 代码块，只输出 JSON。
                        """,
                input.getMarketName() != null ? input.getMarketName() : "未知市场",
                gson.toJson(input.getAnomalies()),
                gson.toJson(input.getArticles()));

        String fullPrompt = systemPrompt + "\n\n---\n\n" + userPrompt;
        Prompt prompt = new Prompt(new UserMessage(fullPrompt));
        String response = chatModel.call(prompt).getResult().getOutput().getContent();

        return cleanMarkdown(response);
    }

    private String cleanMarkdown(String response) {
        return response.replaceAll("```json", "")
                .replaceAll("```", "")
                .trim();
    }

    private String mockAttribution(List<AnomalyPoint> anomalies, List<NewsArticle> articles) {
        List<PricingTimeline.Attribution> result = anomalies.stream()
                .map(a -> {
                    NewsArticle matched = articles != null ? articles.stream()
                            .min((e1, e2) -> Long.compare(
                                    Math.abs(e1.getPublishedAt().getEpochSecond() - a.getTimestamp().getEpochSecond()),
                                    Math.abs(e2.getPublishedAt().getEpochSecond() - a.getTimestamp().getEpochSecond())))
                            .orElse(null) : null;
                    return PricingTimeline.Attribution.builder()
                            .timestamp(a.getTimestamp().toString())
                            .matchedEventTitle(matched != null ? matched.getTitle() : "未知事件")
                            .explanation(matched != null
                                    ? "mock 归因：该异常点与新闻时间接近，可能被市场重新定价。"
                                    : "mock 归因：未找到明显关联新闻。")
                            .confidence(matched != null ? "MEDIUM" : "LOW")
                            .build();
                })
                .toList();
        return gson.toJson(result);
    }

    @Data
    public static class AttributionInput {
        private String marketName;
        private List<AnomalyPoint> anomalies;
        private List<NewsArticle> articles;
    }
}

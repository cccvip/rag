package com.core.agent.infopricing.infrastructure;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Mock 新闻搜索工具。
 *
 * <p>当真实新闻 API 不可用或 GNews API Key 未配置/超配额时，回退到内置的 mock 事件列表。</p>
 * <p>mock 事件硬编码在代码中，不污染 application.yml 配置。</p>
 */
@Slf4j
@Component
public class MockNewsSearchTool implements NewsSearchTool {

    private final List<NewsArticle> mockArticles = List.of(
            createArticle(
                    "2024-07-13T18:00:00Z",
                    "Mock 赛前：A 队公布首发名单",
                    "Mock 新闻：A 队排出最强阵容，市场预期其胜率上升。"),
            createArticle(
                    "2024-07-13T18:30:00Z",
                    "Mock 半场：B 队核心受伤下场",
                    "Mock 新闻：B 队中场核心因伤退场，A 队优势扩大。"),
            createArticle(
                    "2024-07-13T19:00:00Z",
                    "Mock 赛后：A 队 2:0 完胜 B 队",
                    "Mock 新闻：A 队取得关键胜利，夺冠概率大幅提升。")
    );

    @Override
    public List<NewsArticle> search(String query, Instant from, Instant to) {
        log.info("[MockNews] Searching mock articles: query='{}', window=[{}, {}]", query, from, to);
        log.info("[MockNews] Total mock articles in repository: {}", mockArticles.size());

        List<NewsArticle> matched = mockArticles.stream()
                .filter(article -> {
                    Instant publishedAt = article.getPublishedAt();
                    return publishedAt != null
                            && !publishedAt.isBefore(from)
                            && !publishedAt.isAfter(to);
                })
                .peek(article -> log.info("[MockNews] Matched: {} | {}", article.getPublishedAt(), article.getTitle()))
                .toList();

        log.info("[MockNews] Returning {} matched articles", matched.size());
        return matched;
    }

    private static NewsArticle createArticle(String timestamp, String title, String description) {
        NewsArticle article = new NewsArticle();
        article.setTitle(title);
        article.setDescription(description);
        article.setPublishedAt(Instant.parse(timestamp));
        article.setSource("mock");
        article.setUrl(null);
        return article;
    }
}

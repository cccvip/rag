package com.core.agent.infopricing.infrastructure;

import com.core.agent.infopricing.application.InfoPricingProperties;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * GNews API 实现的新闻搜索工具。
 *
 * <p>免费额度通常限制：100 次/天，每次最多 10 条。本类内置速率限制与每日配额，
 * 超出时自动回退到 {@link MockNewsSearchTool}，避免浪费 API 调用额度。</p>
 */
@Slf4j
@Component
public class GNewsSearchTool implements NewsSearchTool {

    private static final String API_BASE = "https://gnews.io/api/v4/search";
    private static final DateTimeFormatter ISO = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
            .withZone(ZoneOffset.UTC);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final Gson gson;
    private final String apiKey;
    private final int maxResults;
    private final int rateLimitPerMinute;
    private final int dailyQuota;
    private final MockNewsSearchTool fallback;

    // 内存级限流与配额统计（重启失效；生产可替换为 Redis/DB）
    private final List<Instant> callTimestamps = new ArrayList<>();
    private int dailyCount = 0;
    private LocalDate currentDate = LocalDate.now(ZoneOffset.UTC);

    public GNewsSearchTool(Gson gson,
                           InfoPricingProperties properties,
                           MockNewsSearchTool fallback) {
        this.gson = gson;
        this.apiKey = properties.getGnewsApiKey();
        this.maxResults = properties.getGnewsMaxResults() > 0
                ? properties.getGnewsMaxResults() : 10;
        this.rateLimitPerMinute = properties.getGnewsRateLimitPerMinute() > 0
                ? properties.getGnewsRateLimitPerMinute() : 5;
        this.dailyQuota = properties.getGnewsDailyQuota() > 0
                ? properties.getGnewsDailyQuota() : 100;
        this.fallback = fallback;
    }

    @Override
    public List<NewsArticle> search(String query, Instant from, Instant to) throws Exception {
        log.info("[GNews] Quota status before search: {}", getQuotaStatus());

        if (apiKey == null || apiKey.isBlank()) {
            log.warn("[GNews] API key not configured, fallback to mock news");
            return fallback.search(query, from, to);
        }

        String rejectReason = acquirePermit();
        if (rejectReason != null) {
            log.warn("[GNews] {}. fallback to mock news", rejectReason);
            return fallback.search(query, from, to);
        }

        String url = buildUrl(query, from, to);
        String urlForLog = url.replaceAll("apikey=[^&]*", "apikey=***");
        log.info("[GNews] Calling API: {}", urlForLog);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("GNews HTTP " + response.statusCode() + ": " + response.body());
        }

        List<NewsArticle> articles = parseArticles(response.body());
        log.info("[GNews] Received {} articles", articles.size());
        articles.forEach(a -> log.info("[GNews]   - {} | {}", a.getPublishedAt(), a.getTitle()));
        log.info("[GNews] Quota status after search: {}", getQuotaStatus());
        return articles;
    }

    /**
     * 获取当前配额使用情况（用于日志/监控）。
     */
    public synchronized QuotaStatus getQuotaStatus() {
        return new QuotaStatus(dailyCount, dailyQuota, callTimestamps.size(), rateLimitPerMinute);
    }

    private synchronized String acquirePermit() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        if (!today.equals(currentDate)) {
            currentDate = today;
            dailyCount = 0;
            callTimestamps.clear();
        }

        if (dailyCount >= dailyQuota) {
            return String.format("daily quota exceeded: %d/%d", dailyCount, dailyQuota);
        }

        Instant oneMinuteAgo = Instant.now().minus(Duration.ofMinutes(1));
        callTimestamps.removeIf(t -> t.isBefore(oneMinuteAgo));

        if (callTimestamps.size() >= rateLimitPerMinute) {
            return String.format("rate limit exceeded: %d/%d per minute", callTimestamps.size(), rateLimitPerMinute);
        }

        callTimestamps.add(Instant.now());
        dailyCount++;
        return null;
    }

    private String buildUrl(String query, Instant from, Instant to) {
        return String.format("%s?q=%s&from=%s&to=%s&lang=en&max=%d&apikey=%s",
                API_BASE,
                java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8),
                ISO.format(from),
                ISO.format(to),
                maxResults,
                apiKey);
    }

    private List<NewsArticle> parseArticles(String body) {
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        JsonArray articles = root.getAsJsonArray("articles");

        List<NewsArticle> result = new ArrayList<>();
        if (articles == null) {
            return result;
        }

        for (JsonElement element : articles) {
            JsonObject item = element.getAsJsonObject();
            NewsArticle article = new NewsArticle();
            article.setTitle(getString(item, "title"));
            article.setDescription(getString(item, "description"));
            article.setUrl(getString(item, "url"));
            article.setPublishedAt(parseInstant(getString(item, "publishedAt")));

            JsonObject source = item.getAsJsonObject("source");
            if (source != null) {
                article.setSource(getString(source, "name"));
            }

            result.add(article);
        }
        return result;
    }

    private String getString(JsonObject obj, String field) {
        return obj.has(field) && !obj.get(field).isJsonNull() ? obj.get(field).getAsString() : null;
    }

    private Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (Exception e) {
            log.warn("Failed to parse publishedAt: {}", value);
            return null;
        }
    }

    /**
     * GNews 配额使用情况。
     */
    public record QuotaStatus(int dailyUsed, int dailyLimit, int lastMinuteUsed, int lastMinuteLimit) {

        public boolean hasQuota() {
            return dailyUsed < dailyLimit && lastMinuteUsed < lastMinuteLimit;
        }

        @Override
        public String toString() {
            return String.format("GNews quota: %d/%d today, %d/%d last minute",
                    dailyUsed, dailyLimit, lastMinuteUsed, lastMinuteLimit);
        }
    }
}

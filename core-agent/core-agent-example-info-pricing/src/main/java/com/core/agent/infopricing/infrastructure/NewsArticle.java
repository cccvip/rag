package com.core.agent.infopricing.infrastructure;

import lombok.Data;

import java.time.Instant;

/**
 * 新闻文章模型。
 */
@Data
public class NewsArticle {

    private String title;
    private String description;
    private String url;
    private Instant publishedAt;
    private String source;
}

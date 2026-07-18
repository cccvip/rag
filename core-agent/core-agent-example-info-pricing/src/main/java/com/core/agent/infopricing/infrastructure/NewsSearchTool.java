package com.core.agent.infopricing.infrastructure;

import java.time.Instant;
import java.util.List;

/**
 * 新闻搜索工具 SPI。
 *
 * <p>根据查询关键词和时间窗口拉取真实新闻列表。</p>
 */
public interface NewsSearchTool {

    /**
     * 搜索新闻。
     *
     * @param query 查询关键词
     * @param from  时间窗口起始
     * @param to    时间窗口结束
     * @return 新闻列表；无结果时返回空列表
     * @throws Exception 搜索失败
     */
    List<NewsArticle> search(String query, Instant from, Instant to) throws Exception;
}

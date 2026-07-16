package com.core.agent.rag.interfaces;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * RAG 文档写入请求中的单条文档。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagDocumentDto {

    /** 文档唯一标识，为空时由底层存储生成。 */
    private String id;

    /** 文档正文。 */
    private String content;

    /** 可选元数据，会随向量一起存储并可在检索时过滤。 */
    private Map<String, Object> metadata;
}

package com.core.agent.rag.interfaces;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * RAG 文档批量写入请求。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagDocumentRequest {

    @Builder.Default
    private List<RagDocumentDto> documents = new ArrayList<>();
}

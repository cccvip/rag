package com.core.agent.rag.infrastructure;

import com.core.agent.shared.model.RiskLevel;
import com.core.agent.tool.domain.Tool;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 向量检索工具。
 *
 * <p>直接基于 Spring AI {@link VectorStore} 做语义检索，返回带 [doc-xxx] 引用的文本片段。
 * 底层存储可由 {@code SimpleVectorStore}、Qdrant、Milvus、Elasticsearch 等实现。</p>
 */
public class VectorRetrieverTool implements Tool {

    private final String name;
    private final VectorStore vectorStore;
    private final int defaultTopK;

    public VectorRetrieverTool(String name, VectorStore vectorStore, int defaultTopK) {
        this.name = name;
        this.vectorStore = vectorStore;
        this.defaultTopK = defaultTopK;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String description() {
        return "Retrieve relevant documents by semantic similarity. Input: search query string.";
    }

    @Override
    public RiskLevel riskLevel() {
        return RiskLevel.LOW;
    }

    @Override
    public String execute(String input) {
        SearchRequest request = SearchRequest.query(input).withTopK(defaultTopK);
        List<Document> docs = vectorStore.similaritySearch(request);
        if (docs.isEmpty()) {
            return "No relevant documents found.";
        }

        return docs.stream()
                .map(doc -> "[doc-" + doc.getId() + "] " + doc.getContent())
                .collect(Collectors.joining("\n\n"));
    }
}

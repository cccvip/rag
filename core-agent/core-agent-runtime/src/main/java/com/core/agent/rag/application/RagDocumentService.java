package com.core.agent.rag.application;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * RAG 文档写入服务。
 *
 * <p>负责把业务文档转换为 Spring AI {@link Document} 并写入向量存储。</p>
 */
@Service
public class RagDocumentService {

    private final VectorStore vectorStore;

    public RagDocumentService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    /**
     * 批量写入文档。
     *
     * @param documents 待写入的文档列表
     */
    public void addDocuments(List<Document> documents) {
        List<Document> validDocs = documents.stream()
                .filter(Objects::nonNull)
                .filter(doc -> doc.getContent() != null && !doc.getContent().isBlank())
                .toList();
        if (validDocs.isEmpty()) {
            return;
        }
        vectorStore.add(validDocs);
    }

    /**
     * 便捷方法：把文本和元数据封装成 {@link Document}。
     */
    public Document toDocument(String id, String content, Map<String, Object> metadata) {
        Map<String, Object> safeMetadata = metadata == null ? Map.of() : metadata;
        if (id != null && !id.isBlank()) {
            return new Document(id, content, safeMetadata);
        }
        return new Document(content, safeMetadata);
    }
}

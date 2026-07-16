package com.core.agent.rag.interfaces;

import com.core.agent.rag.application.RagDocumentService;
import org.springframework.ai.document.Document;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * RAG 文档管理 REST 接口。
 */
@RestController
@RequestMapping("/rag")
public class RagDocumentController {

    private final RagDocumentService documentService;

    public RagDocumentController(RagDocumentService documentService) {
        this.documentService = documentService;
    }

    /**
     * 批量写入文档到向量存储。
     *
     * <p>写入后可通过 Agent 的 {@code dense_retrieve} 工具进行语义检索。</p>
     */
    @PostMapping("/documents")
    public ResponseEntity<Void> addDocuments(@RequestBody RagDocumentRequest request) {
        List<Document> documents = request.getDocuments().stream()
                .map(dto -> documentService.toDocument(dto.getId(), dto.getContent(), dto.getMetadata()))
                .toList();
        documentService.addDocuments(documents);
        return ResponseEntity.ok().build();
    }
}

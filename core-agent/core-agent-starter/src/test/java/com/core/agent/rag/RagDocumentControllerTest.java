package com.core.agent.rag;

import com.core.agent.rag.interfaces.RagDocumentDto;
import com.core.agent.rag.interfaces.RagDocumentRequest;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import com.core.agent.bootstrap.AgentApp;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = AgentApp.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RagDocumentControllerTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private VectorStore vectorStore;

    @Test
    void shouldAddDocumentsAndMakeThemSearchable() {
        RagDocumentRequest request = RagDocumentRequest.builder()
                .documents(List.of(
                        RagDocumentDto.builder()
                                .id("doc-1")
                                .content("CoreAgent 支持 Qdrant 向量存储")
                                .metadata(Map.of("scene", "rag"))
                                .build(),
                        RagDocumentDto.builder()
                                .id("doc-2")
                                .content("Spring AI 提供 VectorStore 抽象")
                                .build()
                ))
                .build();

        ResponseEntity<Void> response = restTemplate.postForEntity(
                "/rag/documents", request, Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        List<Document> results = vectorStore.similaritySearch(
                SearchRequest.query("Qdrant").withTopK(10));
        assertThat(results).hasSize(2);
        assertThat(results.stream().map(Document::getId))
                .containsExactlyInAnyOrder("doc-1", "doc-2");
    }

    @Test
    void shouldIgnoreEmptyDocuments() {
        RagDocumentRequest request = RagDocumentRequest.builder()
                .documents(List.of(
                        RagDocumentDto.builder().id("empty").content("  ").build()
                ))
                .build();

        ResponseEntity<Void> response = restTemplate.postForEntity(
                "/rag/documents", request, Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}

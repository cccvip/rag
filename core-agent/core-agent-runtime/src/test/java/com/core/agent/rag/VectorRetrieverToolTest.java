package com.core.agent.rag;

import com.core.agent.rag.infrastructure.VectorRetrieverTool;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VectorRetrieverToolTest {

    private final VectorStore vectorStore = mock(VectorStore.class);
    private final ArgumentCaptor<SearchRequest> requestCaptor = ArgumentCaptor.forClass(SearchRequest.class);

    @Test
    void shouldReturnFormattedDocuments() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                new Document("doc-1", "退款需要联系客服", Map.of()),
                new Document("doc-2", "退款将在 3 个工作日到账", Map.of())
        ));

        VectorRetrieverTool tool = new VectorRetrieverTool("retriever", vectorStore, 2);
        String result = tool.execute("退款流程");

        assertThat(result).contains("[doc-doc-1] 退款需要联系客服");
        assertThat(result).contains("[doc-doc-2] 退款将在 3 个工作日到账");
    }

    @Test
    void shouldBuildSearchRequestWithQueryAndTopK() {
        when(vectorStore.similaritySearch(requestCaptor.capture())).thenReturn(List.of(
                new Document("doc-1", "退款需要联系客服", Map.of())
        ));

        VectorRetrieverTool tool = new VectorRetrieverTool("retriever", vectorStore, 3);
        tool.execute("退款流程");

        SearchRequest request = requestCaptor.getValue();
        assertThat(request.getQuery()).isEqualTo("退款流程");
        assertThat(request.getTopK()).isEqualTo(3);
    }

    @Test
    void shouldReturnNoDocumentsMessage() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        VectorRetrieverTool tool = new VectorRetrieverTool("retriever", vectorStore, 2);
        String result = tool.execute("未知问题");

        assertThat(result).isEqualTo("No relevant documents found.");
    }
}

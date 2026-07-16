package com.core.agent;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.List;

/**
 * 测试基础设施配置。
 *
 * <p>在单元测试中替换真实的 Qdrant / OpenAI Embedding，避免依赖外部服务。</p>
 */
@Configuration
public class TestInfraConfig {

    @Bean
    @Primary
    public EmbeddingModel embeddingModel() {
        return new EmbeddingModel() {
            private static final int DIMENSIONS = 384;

            @Override
            public EmbeddingResponse call(EmbeddingRequest request) {
                List<Embedding> embeddings = request.getInstructions().stream()
                        .map(text -> new Embedding(unitVector(), null))
                        .toList();
                return new EmbeddingResponse(embeddings);
            }

            @Override
            public float[] embed(Document document) {
                return unitVector();
            }

            private float[] unitVector() {
                float[] vector = new float[DIMENSIONS];
                vector[0] = 1.0f;
                return vector;
            }

            @Override
            public int dimensions() {
                return DIMENSIONS;
            }
        };
    }

    @Bean
    public VectorStore vectorStore(EmbeddingModel embeddingModel) {
        return new SimpleVectorStore(embeddingModel);
    }
}

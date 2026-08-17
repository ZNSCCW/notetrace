package com.notetrace.ai.ollama;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notetrace.ai.EmbeddingProvider;

/**
 * 本地 Ollama 嵌入实现（免费、无限额、数据不出本机）。
 * 依赖：本机安装 Ollama 并 pull bge-m3 模型（ollama pull bge-m3）。
 * 激活：notetrace.ai.embedding-provider=ollama
 */
@Component
@ConditionalOnProperty(name = "notetrace.ai.embedding-provider", havingValue = "ollama")
public class OllamaEmbeddingProvider implements EmbeddingProvider {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String model;

    public OllamaEmbeddingProvider(@Value("${notetrace.ai.ollama.base-url}") String baseUrl,
                                   @Value("${notetrace.ai.ollama.model}") String model,
                                   ObjectMapper objectMapper) {
        // 连接/读取超时：Ollama 未启动或无响应时快速失败，避免 ingest/QA 无限挂起
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                java.net.http.HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(5))
                        .build());
        factory.setReadTimeout(Duration.ofSeconds(60));
        this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.model = model;
        this.objectMapper = objectMapper;
    }

    @Override
    public float[] embed(String text) {
        String body = restClient.post()
                .uri("/api/embed")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("model", model, "input", List.of(text)))
                .retrieve()
                .body(String.class);
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode emb = root.path("embeddings").get(0);
            float[] v = new float[emb.size()];
            for (int i = 0; i < emb.size(); i++) {
                v[i] = (float) emb.get(i).asDouble();
            }
            return v;
        } catch (Exception e) {
            throw new IllegalStateException("解析 Ollama embedding 响应失败", e);
        }
    }

    @Override
    public String modelName() {
        return model;
    }
}

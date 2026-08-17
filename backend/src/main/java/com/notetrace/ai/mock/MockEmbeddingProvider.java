package com.notetrace.ai.mock;

import java.util.Random;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.notetrace.ai.EmbeddingProvider;

/**
 * 确定性 Mock 嵌入：同一文本始终得到同一向量（基于内容 hash 播种），
 * 用于无 Ollama 环境下的开发与集成测试。生产切换 notetrace.ai.embedding-provider=ollama。
 */
@Component
@ConditionalOnProperty(name = "notetrace.ai.embedding-provider", havingValue = "mock", matchIfMissing = true)
public class MockEmbeddingProvider implements EmbeddingProvider {

    public static final int DIM = 1024;

    @Override
    public float[] embed(String text) {
        Random r = new Random(text.hashCode());
        float[] v = new float[DIM];
        for (int i = 0; i < DIM; i++) {
            v[i] = r.nextFloat() * 2f - 1f;
        }
        return v;
    }

    @Override
    public String modelName() {
        return "mock-1024";
    }
}

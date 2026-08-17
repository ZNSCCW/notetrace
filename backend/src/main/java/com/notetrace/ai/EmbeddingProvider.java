package com.notetrace.ai;

/**
 * 向量化提供者抽象（FR-6/FR-7）。
 * 实现：本地 Ollama bge-m3（生产）、Mock（无环境时测试/开发）。
 * 切换通过配置 notetrace.ai.embedding-provider 控制，避免硬编码绑定某家服务。
 */
public interface EmbeddingProvider {

    /** 将文本编码为向量 */
    float[] embed(String text);

    /** 当前嵌入模型名，写入 chunks.embedding_model 用于换模型时全量重建 */
    String modelName();
}

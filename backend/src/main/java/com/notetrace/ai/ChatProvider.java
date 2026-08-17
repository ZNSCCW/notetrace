package com.notetrace.ai;

/**
 * 大模型生成提供者抽象（FR-7/FR-10）。
 * 实现：DeepSeek API（生产）、Mock（无 key 时开发）。
 * 切换通过配置 notetrace.ai.chat-provider 控制。
 */
public interface ChatProvider {

    /** 单轮对话：system 提示 + 用户输入，返回生成文本 */
    String chat(String systemPrompt, String userPrompt);
}

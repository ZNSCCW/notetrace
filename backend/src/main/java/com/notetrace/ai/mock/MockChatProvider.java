package com.notetrace.ai.mock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.notetrace.ai.ChatProvider;

/**
 * Mock 生成：无 DeepSeek key 时兜底，把候选内容原样返回（供接口联调）。
 * 生产切换 notetrace.ai.chat-provider=deepseek。
 */
@Component
@ConditionalOnProperty(name = "notetrace.ai.chat-provider", havingValue = "mock", matchIfMissing = true)
public class MockChatProvider implements ChatProvider {

    @Override
    public String chat(String systemPrompt, String userPrompt) {
        // 简单回显最后一段 user 内容，便于联调时观察 prompt 结构
        return "[mock] " + (userPrompt == null ? "" : userPrompt);
    }
}

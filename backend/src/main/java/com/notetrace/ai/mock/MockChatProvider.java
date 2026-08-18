package com.notetrace.ai.mock;

import org.springframework.stereotype.Component;

import com.notetrace.ai.ChatProvider;

/**
 * Mock 生成：无 DeepSeek key 时兜底，把候选内容原样返回（供接口联调）。
 * 总是注册（路由 choice=mock 时使用），不影响 api/local 正常路径。
 */
@Component("mockChatProvider")
public class MockChatProvider implements ChatProvider {

    @Override
    public String chat(String systemPrompt, String userPrompt) {
        // 简单回显最后一段 user 内容，便于联调时观察 prompt 结构
        return "[mock] " + (userPrompt == null ? "" : userPrompt);
    }
}

package com.notetrace.ai;

import org.springframework.stereotype.Component;

import com.notetrace.ai.deepseek.DeepSeekChatProvider;
import com.notetrace.ai.mock.MockChatProvider;
import com.notetrace.ai.ollama.OllamaChatProvider;

/**
 * 生成 provider 路由：Web UI 由用户选择「调用 API」（DeepSeek）或「本地 AI」（Ollama）。
 * 默认 API；未配置 key 时 API 调用会报错，可切本地。
 */
@Component
public class ChatRouter {

    private final DeepSeekChatProvider deepSeek;
    private final OllamaChatProvider ollama;
    private final MockChatProvider mock;

    public ChatRouter(DeepSeekChatProvider deepSeek, OllamaChatProvider ollama, MockChatProvider mock) {
        this.deepSeek = deepSeek;
        this.ollama = ollama;
        this.mock = mock;
    }

    /** choice: api | local | mock（默认 api） */
    public ChatProvider resolve(String choice) {
        return switch (choice == null ? "api" : choice) {
            case "local" -> ollama;
            case "mock" -> mock;
            default -> deepSeek;
        };
    }
}

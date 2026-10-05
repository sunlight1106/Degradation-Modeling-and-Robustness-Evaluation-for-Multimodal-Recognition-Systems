package com.robustvision.platform.domain;

import java.util.List;

/** Personal credentials are deliberately separate from the administrator's model key ring. */
public enum AiProvider {
    OPENAI("OpenAI", Protocol.OPENAI_CHAT, List.of("https://api.openai.com/v1")),
    GEMINI("Google Gemini", Protocol.GEMINI, List.of("https://generativelanguage.googleapis.com/v1beta")),
    XAI("xAI", Protocol.OPENAI_CHAT, List.of("https://api.x.ai/v1")),
    ANTHROPIC("Anthropic Claude", Protocol.ANTHROPIC, List.of("https://api.anthropic.com/v1")),
    DEEPSEEK("DeepSeek", Protocol.OPENAI_CHAT, List.of("https://api.deepseek.com", "https://api.deepseek.com/v1")),
    MOONSHOT("Moonshot Kimi", Protocol.OPENAI_CHAT, List.of("https://api.moonshot.ai/v1", "https://api.moonshot.cn/v1")),
    QWEN("Alibaba Qwen", Protocol.OPENAI_CHAT, List.of("https://dashscope.aliyuncs.com/compatible-mode/v1", "https://dashscope-intl.aliyuncs.com/compatible-mode/v1", "https://dashscope-us.aliyuncs.com/compatible-mode/v1")),
    LLAMA("Hosted Llama (OpenAI compatible)", Protocol.OPENAI_CHAT, List.of("https://api.groq.com/openai/v1", "https://api.together.ai/v1")),
    CUSTOM("Custom OpenAI-compatible endpoint", Protocol.OPENAI_CHAT, List.of());

    public enum Protocol { OPENAI_CHAT, GEMINI, ANTHROPIC }
    private final String displayName;
    private final Protocol protocol;
    private final List<String> baseUrls;
    AiProvider(String displayName, Protocol protocol, List<String> baseUrls) {
        this.displayName = displayName; this.protocol = protocol; this.baseUrls = baseUrls;
    }
    public String displayName() { return displayName; }
    public Protocol protocol() { return protocol; }
    public List<String> baseUrls() { return baseUrls; }
}

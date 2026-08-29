package com.smarttrip.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "ai.llm")
public class AiProperties {
    // 默认使用 DeepSeek，也可换成通义千问或 OpenAI 兼容端点
    private String baseUrl = "https://api.deepseek.com/v1";
    private String apiKey = "YOUR_API_KEY_HERE"; //todo 如果没有 key，后面会走高仿真流式模拟
    private String model = "deepseek-chat";
}
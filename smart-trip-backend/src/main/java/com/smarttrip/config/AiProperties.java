package com.smarttrip.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "ai.llm")
public class AiProperties {
  private boolean enabled = false;
  private boolean freeQuotaVerified = false;
  // 默认与 application-local.example.yml 保持一致；未显式开启且确认免费额度时不会调用外部服务。
  private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
  private String apiKey = "";
  private String model = "qwen3.8-flash";
}

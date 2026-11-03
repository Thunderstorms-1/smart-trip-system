package com.smarttrip.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "smarttrip.rag")
public class RagProperties {
  private boolean enabled = false;
  private boolean fallbackToOriginal = true;
  private int retrievalTopK = 10;
  private int contextTopK = 5;
  private int rrfK = 60;
  private int contextTokenBudget = 1800;
  private int maxSourceAgeDays = 1095;
  private boolean includeTestKnowledge = false;
  private boolean rerankEnabled = false;
  private String rerankUrl = "http://127.0.0.1:8010/rerank";
  private int rerankTimeoutMillis = 1500;
  private int retrievalTimeoutMillis = 6000;
  private String elasticsearchUrl = "http://127.0.0.1:9200";
  private String elasticsearchUsername = "";
  private String elasticsearchPassword = "";
  private String indexName = "smarttrip-travel-v1";
  private String embeddingBaseUrl = "http://127.0.0.1:11434";
  private String embeddingModel = "bge-m3";
  private String embeddingVersion = "v1";
  private int embeddingTimeoutMillis = 4000;
  private int chunkTokens = 384;
  private int chunkOverlapTokens = 48;
  // Explicit offline ingestion may take longer than interactive retrieval.
  private int ingestionTimeoutMillis = 120000;
}
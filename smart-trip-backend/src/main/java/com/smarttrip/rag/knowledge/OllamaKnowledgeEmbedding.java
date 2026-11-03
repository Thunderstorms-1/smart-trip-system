package com.smarttrip.rag.knowledge;

import com.smarttrip.config.RagProperties;
import java.time.Duration;
import java.util.List;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaEmbeddingOptions;
import org.springframework.ai.ollama.management.ModelManagementOptions;
import org.springframework.ai.ollama.management.PullModelStrategy;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class OllamaKnowledgeEmbedding {
  private final RagProperties config;
  private volatile OllamaEmbeddingModel cached;
  private volatile String cacheKey;
  public OllamaKnowledgeEmbedding(RagProperties config) { this.config = config; }
  public String model() { return config.getEmbeddingModel(); }
  public String version() { return config.getEmbeddingVersion(); }
  public float[] embed(String text) {
    if (text == null || text.isBlank()) throw new RagException("DOCUMENT_EMPTY", "无法向量化空文本");
    try {
      var response = embeddingModel().call(new EmbeddingRequest(List.of(text), null));
      if (response.getResults().size() != 1)
        throw new RagException("EMBEDDING_OUTPUT_INVALID", "向量服务返回数量无效");
      float[] vector = response.getResult().getOutput();
      if (vector == null || vector.length < 1 || vector.length > 4096)
        throw new RagException("EMBEDDING_OUTPUT_INVALID", "向量服务返回维度无效");
      double magnitude = 0;
      for (float value : vector) {
        if (!Float.isFinite(value)) throw new RagException("EMBEDDING_OUTPUT_INVALID", "向量服务返回非有限数值");
        magnitude += value * (double) value;
      }
      if (magnitude == 0) throw new RagException("EMBEDDING_OUTPUT_INVALID", "向量服务返回零向量");
      return vector;
    } catch (RagException e) { throw e; }
    catch (RuntimeException e) {
      // Supplier exception bodies can contain request text; keep them out of logs and task metadata.
      throw new RagException(RagHttpTransport.timeout(e) ? "RETRIEVAL_TIMEOUT" : "OLLAMA_UNAVAILABLE",
          RagHttpTransport.timeout(e) ? "本地Embedding请求超时" : "本地Embedding服务不可用");
    }
  }
  private OllamaEmbeddingModel embeddingModel() {
    String base = RagHttpTransport.baseUrl(config.getEmbeddingBaseUrl());
    if (model() == null || !model().matches("[A-Za-z0-9_./:@-]{1,200}")
        || version() == null || !version().matches("[A-Za-z0-9_.-]{1,80}"))
      throw new RagException("RAG_CONFIG_INVALID", "Embedding模型或版本配置无效");
    String key = base + "|" + model() + "|" + version() + "|" + config.getEmbeddingTimeoutMillis();
    if (cached != null && key.equals(cacheKey)) return cached;
    synchronized (this) {
      if (cached != null && key.equals(cacheKey)) return cached;
      var transport = RagHttpTransport.client(config.getEmbeddingTimeoutMillis());
      var api = OllamaApi.builder().baseUrl(base)
          .restClientBuilder(RestClient.builder().requestFactory(RagHttpTransport.factory(transport)))
          .responseErrorHandler(new org.springframework.web.client.ResponseErrorHandler() {
            public boolean hasError(org.springframework.http.client.ClientHttpResponse response) throws java.io.IOException {
              return !response.getStatusCode().is2xxSuccessful();
            }
            public void handleError(java.net.URI uri, org.springframework.http.HttpMethod method,
                org.springframework.http.client.ClientHttpResponse response) throws java.io.IOException {
              throw new RagException(response.getStatusCode().value() == 404
                  ? "EMBEDDING_MODEL_MISSING" : "OLLAMA_UNAVAILABLE",
                  response.getStatusCode().value() == 404 ? "本地Embedding模型未安装或接口不存在" : "本地Embedding服务拒绝请求");
            }
          }).build();
      cached = OllamaEmbeddingModel.builder().ollamaApi(api)
          .options(OllamaEmbeddingOptions.builder().model(model()).truncate(false).keepAlive("2m").build())
          .modelManagementOptions(ModelManagementOptions.builder().pullModelStrategy(PullModelStrategy.NEVER)
              .maxRetries(0).timeout(Duration.ofMillis(config.getEmbeddingTimeoutMillis())).build()).build();
      cacheKey = key;
      return cached;
    }
  }
}
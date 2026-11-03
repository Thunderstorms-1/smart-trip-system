package com.smarttrip.rag.knowledge;

import com.fasterxml.jackson.databind.*;
import com.smarttrip.config.RagProperties;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import okhttp3.*;
import org.springframework.stereotype.Service;

@Service
public class ElasticsearchKnowledgeClient {
  private final RagProperties config;
  private final ObjectMapper json = new ObjectMapper();
  private final Map<Integer, OkHttpClient> transports = new java.util.concurrent.ConcurrentHashMap<>();
  public ElasticsearchKnowledgeClient(RagProperties config) { this.config = config; }
  public String indexName() {
    String name = config.getIndexName();
    if (name == null || !name.matches("[a-z0-9][a-z0-9_-]{0,119}"))
      throw new RagException("RAG_CONFIG_INVALID", "知识索引名称无效");
    return name;
  }
  public JsonNode search(Object body) {
    JsonNode result = request("POST", "/" + indexName() + "/_search", body);
    if (result.path("timed_out").asBoolean() || result.path("_shards").path("failed").asInt() > 0)
      throw new RagException("RETRIEVAL_TIMEOUT", "知识检索超时或分片未完整响应");
    return result;
  }
  public JsonNode request(String method, String path, Object body) {
    return request(method, path, body, config.getRetrievalTimeoutMillis());
  }
  public JsonNode request(String method, String path, Object body, int timeoutMillis) {
    if (path == null || !path.startsWith("/") || path.startsWith("//") || path.contains("..") || path.contains("#"))
      throw new RagException("RAG_CONFIG_INVALID", "知识索引请求路径无效");
    try {
      String base = RagHttpTransport.baseUrl(config.getElasticsearchUrl());
      Request.Builder builder = new Request.Builder().url(base + path).header("Accept", "application/json");
      String user = config.getElasticsearchUsername(), password = config.getElasticsearchPassword();
      if ((user == null || user.isBlank()) != (password == null || password.isBlank()))
        throw new RagException("RAG_CONFIG_INVALID", "知识索引账号和密码必须同时配置");
      if (user != null && !user.isBlank()) builder.header("Authorization", Credentials.basic(user, password, StandardCharsets.UTF_8));
      String serialized = body == null ? "" : body instanceof String text ? text : json.writeValueAsString(body);
      String contentType = path.contains("_bulk") ? "application/x-ndjson" : "application/json";
      builder.method(method, Set.of("GET", "HEAD").contains(method) && body == null ? null
          : RequestBody.create(serialized, MediaType.get(contentType)));
      try (Response response = transports.computeIfAbsent(timeoutMillis, RagHttpTransport::client).newCall(builder.build()).execute()) {
        if (!response.isSuccessful()) {
          if (response.code() == 404) throw new RagException("INDEX_EMPTY", "知识索引尚未初始化或已不存在");
          if (response.code() == 401 || response.code() == 403)
            throw new RagException("ES_AUTH_FAILED", "知识索引鉴权失败");
          throw new RagException("ES_UNAVAILABLE", "知识索引请求失败（HTTP " + response.code() + "）");
        }
        if (response.body() == null || method.equals("HEAD")) return json.createObjectNode();
        byte[] bytes = response.body().byteStream().readNBytes(8 * 1024 * 1024 + 1);
        if (bytes.length > 8 * 1024 * 1024) throw new RagException("ES_OUTPUT_INVALID", "知识索引响应超出限制");
        return bytes.length == 0 ? json.createObjectNode() : json.readTree(bytes);
      }
    } catch (RagException e) { throw e; }
    catch (Exception e) {
      throw new RagException(RagHttpTransport.timeout(e) ? "RETRIEVAL_TIMEOUT" : "ES_UNAVAILABLE",
          RagHttpTransport.timeout(e) ? "知识索引请求超时" : "知识索引服务不可用");
    }
  }
  public int mappedDimensions() {
    validateIndex(0);
    JsonNode mappings = request("GET", "/" + indexName() + "/_mapping", null);
    return mappings.elements().next().path("mappings").path("_meta").path("vectorDimensions").asInt();
  }
  public void validateIndex(int vectorDimensions) {
    JsonNode mappings = request("GET", "/" + indexName() + "/_mapping", null);
    if (mappings.isEmpty()) throw new RagException("INDEX_EMPTY", "知识索引尚未初始化");
    for (JsonNode index : mappings) {
      JsonNode mapping = index.path("mappings"), meta = mapping.path("_meta");
      if (!config.getEmbeddingModel().equals(meta.path("embeddingModel").asText())
          || !config.getEmbeddingVersion().equals(meta.path("embeddingVersion").asText()))
        throw new RagException("EMBEDDING_MODEL_MISMATCH", "知识索引模型版本不匹配，请重建新索引");
      int expected = meta.path("vectorDimensions").asInt(-1);
      int mapped = mapping.path("properties").path("embedding").path("dims").asInt(-1);
      if (expected < 1 || expected != mapped || (vectorDimensions > 0 && expected != vectorDimensions))
        throw new RagException("DIMENSION_MISMATCH", "Embedding输出维度与知识索引不一致");
    }
  }
}
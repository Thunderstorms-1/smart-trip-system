package com.smarttrip.rag;

import com.fasterxml.jackson.databind.*;
import com.smarttrip.config.RagProperties;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import okhttp3.*;
import org.springframework.stereotype.Service;

/** A local-compatible /rerank API. Disabled by default; no heuristic model scores. */
@Service
public class TravelRerankService {
  public record Result(List<RetrievalHit> hits, String provider, long durationMs, String fallbackReason) {}
  private final RagProperties properties;
  private final ObjectMapper json = new ObjectMapper();
  public TravelRerankService(RagProperties properties) { this.properties = properties; }
  public Result rerank(String query, List<RetrievalHit> candidates) {
    if (!properties.isRerankEnabled() || candidates.isEmpty())
      return new Result(candidates, "NONE", 0, null);
    long start = System.nanoTime();
    var timeout = Duration.ofMillis(Math.max(50, properties.getRerankTimeoutMillis()));
    var client = new OkHttpClient.Builder().connectTimeout(timeout).readTimeout(timeout)
        .callTimeout(timeout).retryOnConnectionFailure(false)
        .followRedirects(false).followSslRedirects(false).build();
    try {
      var documents = candidates.stream().map(h -> h.chunk().content()).toList();
      String body = json.writeValueAsString(Map.of("query", query, "documents", documents,
          "top_n", candidates.size(), "return_documents", false));
      var request = new Request.Builder().url(properties.getRerankUrl())
          .post(RequestBody.create(body, MediaType.get("application/json; charset=utf-8"))).build();
      try (var response = client.newCall(request).execute()) {
        if (!response.isSuccessful() || response.body() == null) throw new IOException("Rerank response unavailable");
        var data = json.readTree(response.body().string());
        var results = data.path("results");
        if (!results.isArray() || results.isEmpty()) throw new IOException("Rerank response invalid");
        var reranked = new ArrayList<RetrievalHit>();
        var used = new HashSet<Integer>();
        for (var item : results) {
          if (!item.path("index").isIntegralNumber() || !item.path("index").canConvertToInt()
              || !item.path("relevance_score").isNumber())
            throw new IOException("Rerank score invalid");
          int index = item.path("index").intValue();
          double score = item.path("relevance_score").doubleValue();
          if (index < 0 || index >= candidates.size() || !used.add(index) || !Double.isFinite(score))
            throw new IOException("Rerank index invalid");
          reranked.add(candidates.get(index).reranked(score));
        }
        reranked.sort(Comparator.comparingDouble((RetrievalHit h) -> h.rerankScore()).reversed());
        for (int i = 0; i < candidates.size(); i++) if (!used.contains(i)) reranked.add(candidates.get(i));
        return new Result(List.copyOf(reranked), "HTTP_RERANK", elapsed(start), null);
      }
    } catch (Exception ex) {
      return new Result(candidates, "RRF_FALLBACK", elapsed(start),
          ex instanceof java.io.InterruptedIOException ? "RERANK_TIMEOUT" : "RERANK_UNAVAILABLE");
    } finally {
      client.connectionPool().evictAll();
      client.dispatcher().executorService().shutdown();
    }
  }
  private long elapsed(long start) { return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start); }
}

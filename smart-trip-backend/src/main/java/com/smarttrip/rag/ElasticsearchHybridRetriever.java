package com.smarttrip.rag;

import com.fasterxml.jackson.databind.*;
import com.smarttrip.config.RagProperties;
import com.smarttrip.rag.knowledge.*;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.stereotype.Service;

@Service
public class ElasticsearchHybridRetriever {
  public enum Mode { BM25, VECTOR, HYBRID, HYBRID_RERANK }
  public record Result(List<RetrievalHit> hits, int bm25HitCount, int vectorHitCount,
                       String status, List<String> failures, long durationMs) {}
  private record Branch(List<RetrievalHit> hits, String failure) {}
  private final ElasticsearchKnowledgeClient elasticsearch;
  private final OllamaKnowledgeEmbedding embedding;
  private final RagProperties properties;
  private final ObjectMapper json = new ObjectMapper();
  // Bounded concurrency and no request queue: unavailable downstreams cannot accumulate work.
  private final ExecutorService workers = new ThreadPoolExecutor(0, 16, 30, TimeUnit.SECONDS,
      new SynchronousQueue<>(), task -> { var t = new Thread(task, "rag-retrieval"); t.setDaemon(true); return t; });

  public ElasticsearchHybridRetriever(ElasticsearchKnowledgeClient elasticsearch,
      OllamaKnowledgeEmbedding embedding, RagProperties properties) {
    this.elasticsearch = elasticsearch; this.embedding = embedding; this.properties = properties;
  }

  public Result retrieve(TravelRetrievalQueryBuilder.Query query, Mode mode) {
    long start = System.nanoTime();
    long deadline = start + TimeUnit.MILLISECONDS.toNanos(Math.max(100, properties.getRetrievalTimeoutMillis()));
    Future<Branch> bm25 = mode == Mode.VECTOR ? null : submit(() -> lexical(query));
    Future<Branch> vector = mode == Mode.BM25 ? null : submit(() -> semantic(query));
    Branch a = collect(bm25, deadline), b = collect(vector, deadline);
    var failures = new ArrayList<String>();
    if (a.failure() != null) failures.add(a.failure());
    if (b.failure() != null && !failures.contains(b.failure())) failures.add(b.failure());
    var merged = ReciprocalRankFusion.fuse(a.hits(), b.hits(), properties.getRrfK());
    String status = !failures.isEmpty() ? (merged.isEmpty() ? "UNAVAILABLE" : "DEGRADED")
        : merged.isEmpty() ? "NO_RELEVANT_KNOWLEDGE" : "SUCCESS";
    // Count only authorized public knowledge; do not expose existence of private/other-model data.
    if (merged.isEmpty() && failures.isEmpty() && System.nanoTime() < deadline) {
      Future<Branch> probe = submit(() -> {
        var node = elasticsearch.search(Map.of("size", 0, "query", Map.of("bool", Map.of("filter", query.accessFilters()))));
        JsonNode totalNode = node == null ? null : node.path("hits").path("total");
        if (totalNode != null && totalNode.isObject()) totalNode = totalNode.path("value");
        if (totalNode == null || !totalNode.isIntegralNumber() || !totalNode.canConvertToLong()
            || totalNode.longValue() < 0)
          throw new RagException("ES_OUTPUT_INVALID", "Knowledge count response is invalid");
        long total = totalNode.longValue();
        return new Branch(List.of(), total == 0 ? "INDEX_EMPTY" : null);
      });
      Branch counted = collect(probe, deadline);
      if ("INDEX_EMPTY".equals(counted.failure())) status = "INDEX_EMPTY";
      else if (counted.failure() != null) { status = "DEGRADED"; failures.add(counted.failure()); }
    }
    return new Result(merged, a.hits().size(), b.hits().size(), status,
        List.copyOf(failures), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
  }

  private Future<Branch> submit(Callable<Branch> task) {
    try { return workers.submit(task); }
    catch (RejectedExecutionException ex) { return CompletableFuture.completedFuture(new Branch(List.of(), "RAG_BUSY")); }
  }
  private Branch collect(Future<Branch> future, long deadline) {
    if (future == null) return new Branch(List.of(), null);
    try { return future.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS); }
    catch (TimeoutException ex) { future.cancel(true); return new Branch(List.of(), "RETRIEVAL_TIMEOUT"); }
    catch (InterruptedException ex) { future.cancel(true); Thread.currentThread().interrupt(); return new Branch(List.of(), "RETRIEVAL_INTERRUPTED"); }
    catch (ExecutionException ex) {
      Throwable cause = ex.getCause();
      return new Branch(List.of(), cause instanceof RagException re ? re.code() : "RETRIEVAL_ERROR");
    }
  }
  private Branch lexical(TravelRetrievalQueryBuilder.Query query) {
    Object match = query.text().isBlank() ? Map.of("match_all", Map.of())
        : Map.of("multi_match", Map.of("query", query.text(), "fields",
            List.of("destinations^5", "title^3", "content"), "type", "best_fields"));
    var body = new LinkedHashMap<String, Object>();
    body.put("size", topK()); body.put("_source", Map.of("excludes", List.of("embedding")));
    body.put("query", Map.of("bool", Map.of("must", List.of(match), "filter", query.filters())));
    return parse(elasticsearch.search(body), true, query);
  }
  private Branch semantic(TravelRetrievalQueryBuilder.Query query) {
    float[] vector = embedding.embed(query.text().isBlank() ? "一般旅行规划与安全" : query.text());
    if (vector.length == 0) throw new RagException("EMBEDDING_EMPTY", "Embedding returned an empty vector");
    elasticsearch.validateIndex(vector.length);
    var body = new LinkedHashMap<String, Object>();
    body.put("size", topK()); body.put("_source", Map.of("excludes", List.of("embedding")));
    body.put("knn", Map.of("field", "embedding", "query_vector", vector, "k", topK(),
        "num_candidates", Math.min(1000, Math.max(50, topK() * 5)),
        "filter", Map.of("bool", Map.of("filter", query.filters()))));
    return parse(elasticsearch.search(body), false, query);
  }
  private Branch parse(JsonNode response, boolean lexical, TravelRetrievalQueryBuilder.Query query) {
    if (response == null || !response.path("hits").path("hits").isArray())
      throw new RagException("ES_OUTPUT_INVALID", "Knowledge search response is invalid");
    var hits = new ArrayList<RetrievalHit>();
    String failure = null;
    int rank = 0;
    var ids = new HashSet<String>();
    for (var node : response.path("hits").path("hits")) {
      rank++;
      KnowledgeChunk chunk;
      try { chunk = json.treeToValue(node.path("_source"), KnowledgeChunk.class); }
      catch (Exception ex) { failure = "INVALID_KNOWLEDGE_METADATA"; continue; }
      if (!eligible(chunk, query)) {
        if (failure == null) failure = "PERMISSION_OR_SCOPE_FILTERED";
        continue;
      }
      if (!ids.add(chunk.chunkId())) continue;
      Double score = node.path("_score").isNumber() ? node.path("_score").doubleValue() : null;
      if (score != null && !Double.isFinite(score)) score = null;
      hits.add(new RetrievalHit(chunk, lexical ? rank : null, lexical ? null : rank,
          lexical ? score : null, lexical ? null : score, 0, null));
    }
    return new Branch(List.copyOf(hits), failure);
  }
  private boolean eligible(KnowledgeChunk c, TravelRetrievalQueryBuilder.Query q) {
    if (c == null || c.chunkId() == null || c.content() == null || c.content().isBlank()
        || !"PUBLIC".equals(c.visibility()) || !c.authorized() || c.ownerId() != null
        || (!properties.isIncludeTestKnowledge() && c.testOnly())
        || !Objects.equals(properties.getEmbeddingModel(), c.embeddingModel())
        || !Objects.equals(properties.getEmbeddingVersion(), c.embeddingVersion())) return false;
    if (!KnowledgeValidity.assess(c, q.travelDates()).usable()) return false;
    if (c.sourceName() == null || c.sourceName().isBlank() || c.sourceUrl() == null || c.sourceUrl().isBlank()) return false;
    if (c.sourceUpdatedAt() != null && !c.sourceUpdatedAt().isBlank()) {
      try {
        var updated = java.time.LocalDate.parse(c.sourceUpdatedAt().substring(0, Math.min(10, c.sourceUpdatedAt().length())));
        if ((!"CULTURE".equals(c.category()) && updated.isBefore(java.time.LocalDate.now().minusDays(Math.max(1, properties.getMaxSourceAgeDays()))))
            || updated.isAfter(java.time.LocalDate.now().plusDays(1))) return false;
      } catch (RuntimeException ignored) { /* unknown dates are explicitly marked and deprioritized */ }
    }
    var destinations = c.destinations() == null ? List.<String>of() : c.destinations();
    boolean direct = destinations.stream().anyMatch(q.destinations()::contains);
    boolean hub = "TRANSPORT".equals(c.category()) && destinations.stream().anyMatch(q.hubs()::contains);
    boolean general = destinations.contains("GENERAL") && q.generalCategories().contains(c.category());
    if (!direct && !hub && !general) return false;
    var seasons = c.applicableSeasons() == null ? List.<String>of() : c.applicableSeasons();
    return q.seasons().isEmpty() || seasons.contains("ALL") || seasons.stream().anyMatch(q.seasons()::contains);
  }
  private int topK() { return Math.max(1, Math.min(100, properties.getRetrievalTopK())); }
  @PreDestroy public void close() { workers.shutdownNow(); }
}

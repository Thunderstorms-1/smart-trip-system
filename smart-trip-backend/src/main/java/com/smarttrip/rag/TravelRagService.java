package com.smarttrip.rag;

import com.smarttrip.config.RagProperties;
import com.smarttrip.entity.TravelRequirement;
import com.smarttrip.rag.knowledge.RagException;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.slf4j.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class TravelRagService {
  private static final Logger log = LoggerFactory.getLogger(TravelRagService.class);
  private final RagProperties properties;
  private final TravelRetrievalQueryBuilder queries;
  private final ElasticsearchHybridRetriever retriever;
  private final TravelRerankService reranker;
  private final RagContextBuilder contexts;
  private final MeterRegistry metrics;
  public TravelRagService(RagProperties properties, TravelRetrievalQueryBuilder queries,
      ElasticsearchHybridRetriever retriever, TravelRerankService reranker,
      RagContextBuilder contexts, ObjectProvider<MeterRegistry> metrics) {
    this.properties = properties; this.queries = queries; this.retriever = retriever;
    this.reranker = reranker; this.contexts = contexts;
    this.metrics = metrics.orderedStream().findFirst().orElse(null);
  }

  public TravelRagContext retrieve(TravelRequirement requirement, String taskId) {
    return retrieve(requirement, taskId, null);
  }

  /** Read-only manual preview of the exact retrieval/context pipeline; never creates an AI task. */
  public Map<String, Object> preview(TravelRequirement requirement, String previewId) {
    var snippets = new ArrayList<Map<String, Object>>();
    var context = retrieve(requirement, previewId, snippets);
    var result = new LinkedHashMap<String, Object>();
    result.put("previewId", previewId);
    result.put("previewOnly", true);
    result.put("chatModelCalled", false);
    result.put("rag", context.snapshot());
    result.put("snippets", List.copyOf(snippets));
    if (!properties.isEnabled()) result.put("message", "RAG 未启用，本次没有执行知识检索或向量化");
    return result;
  }

  private TravelRagContext retrieve(TravelRequirement requirement, String taskId,
      List<Map<String, Object>> previewSnippets) {
    long start = System.nanoTime();
    var snapshot = base();
    if (!properties.isEnabled()) return new TravelRagContext("", Map.copyOf(snapshot));
    String previousTask = MDC.get("taskId");
    MDC.put("taskId", taskId == null ? "unknown" : taskId);
    try {
      var query = queries.build(requirement);
      var result = retriever.retrieve(query, ElasticsearchHybridRetriever.Mode.HYBRID);
      var ranked = reranker.rerank(query.text(), result.hits());
      var context = contexts.build(ranked.hits(), query);
      if (previewSnippets != null) collectPreview(previewSnippets, ranked.hits(), context);
      var reasons = new ArrayList<>(result.failures());
      if (ranked.fallbackReason() != null) reasons.add(ranked.fallbackReason());
      String status = result.status();
      if ("SUCCESS".equals(status) && !reasons.isEmpty()) status = "DEGRADED";
      if (context.sources().isEmpty() && !result.hits().isEmpty()) {
        status = "NO_RELIABLE_KNOWLEDGE"; reasons.add("NO_USABLE_SOURCE_WITHIN_BUDGET");
      }
      snapshot.put("status", status);
      snapshot.put("bm25HitCount", result.bm25HitCount());
      snapshot.put("vectorHitCount", result.vectorHitCount());
      snapshot.put("mergedHitCount", result.hits().size());
      snapshot.put("finalContextCount", context.sources().size());
      snapshot.put("contextEstimatedTokens", context.estimatedTokens());
      snapshot.put("retrievalDurationMs", result.durationMs());
      snapshot.put("durationMs", elapsed(start));
      snapshot.put("rerankProvider", ranked.provider());
      snapshot.put("rerankDurationMs", ranked.durationMs());
      snapshot.put("fallbackReason", String.join(",", reasons));
      snapshot.put("candidates", ranked.hits().stream().map(hit -> contexts.metadata(hit, query)).toList());
      snapshot.put("sources", context.sources());
      record(snapshot, taskId);
      if (!properties.isFallbackToOriginal() && !result.failures().isEmpty() && result.hits().isEmpty())
        throw new RagException(result.failures().get(0), "旅行资料检索暂不可用，请稍后重试");
      return new TravelRagContext(context.prompt(), Collections.unmodifiableMap(snapshot));
    } catch (RagException ex) {
      if (!properties.isFallbackToOriginal()) throw ex;
      snapshot.put("status", "UNAVAILABLE"); snapshot.put("fallbackReason", ex.code());
      snapshot.put("durationMs", elapsed(start)); record(snapshot, taskId);
      return new TravelRagContext("NO_RELIABLE_KNOWLEDGE：旅行参考资料暂不可用，不得编造引用。", Collections.unmodifiableMap(snapshot));
    } catch (RuntimeException ex) {
      snapshot.put("status", "UNAVAILABLE"); snapshot.put("fallbackReason", "RAG_INTERNAL_ERROR");
      snapshot.put("durationMs", elapsed(start)); record(snapshot, taskId);
      if (!properties.isFallbackToOriginal()) throw new RagException("RAG_INTERNAL_ERROR", "旅行资料检索暂不可用，请稍后重试");
      return new TravelRagContext("NO_RELIABLE_KNOWLEDGE：旅行参考资料暂不可用，不得编造引用。", Collections.unmodifiableMap(snapshot));
    } finally {
      if (previousTask == null) MDC.remove("taskId"); else MDC.put("taskId", previousTask);
    }
  }
  /** Only selected, permission-filtered public references; never expose the private Prompt. */
  private void collectPreview(List<Map<String, Object>> snippets, List<RetrievalHit> hits,
      RagContextBuilder.Context context) {
    var byId = new HashMap<String, RetrievalHit>();
    hits.forEach(hit -> byId.putIfAbsent(hit.id(), hit));
    for (var source : context.sources()) {
      var hit = byId.get(source.get("chunkId"));
      if (hit == null) continue;
      String content = hit.chunk().content();
      int characters = content.codePointCount(0, content.length());
      int end = content.offsetByCodePoints(0, Math.min(400, characters));
      var snippet = new LinkedHashMap<String, Object>(source);
      snippet.put("excerpt", content.substring(0, end));
      snippet.put("excerptTruncated", characters > 400);
      snippets.add(Collections.unmodifiableMap(snippet));
    }
  }

  private Map<String, Object> base() {
    var out = new LinkedHashMap<String, Object>();
    out.put("status", properties.isEnabled() ? "PENDING" : "DISABLED");
    out.put("ragEnabled", properties.isEnabled()); out.put("strategy", "BM25_VECTOR_JAVA_RRF");
    out.put("embeddingModel", properties.getEmbeddingModel());
    out.put("embeddingVersion", properties.getEmbeddingVersion());
    out.put("bm25HitCount", 0); out.put("vectorHitCount", 0); out.put("mergedHitCount", 0);
    out.put("finalContextCount", 0); out.put("durationMs", 0L);
    out.put("rerankProvider", "NONE"); out.put("rerankDurationMs", 0L);
    out.put("fallbackReason", ""); out.put("candidates", List.of()); out.put("sources", List.of());
    out.put("unverifiedRealtime", true);
    return out;
  }
  private void record(Map<String, Object> s, String taskId) {
    log.info("rag traceId={} taskId={} ragEnabled={} retrievalStrategy={} embeddingModel={} retrievalDuration={} bm25HitCount={} vectorHitCount={} mergedHitCount={} rerankDuration={} finalContextCount={} retrievalStatus={} fallbackReason={}",
        MDC.get("traceId"), taskId, properties.isEnabled(), s.get("strategy"), s.get("embeddingModel"),
        s.get("durationMs"), s.get("bm25HitCount"), s.get("vectorHitCount"), s.get("mergedHitCount"),
        s.get("rerankDurationMs"), s.get("finalContextCount"), s.get("status"), s.get("fallbackReason"));
    if (metrics != null) {
      String status = String.valueOf(s.get("status"));
      metrics.counter("smarttrip.rag.retrieval", "status", status).increment();
      metrics.timer("smarttrip.rag.duration", "status", status)
          .record(((Number) s.get("durationMs")).longValue(), TimeUnit.MILLISECONDS);
      metrics.summary("smarttrip.rag.context.sources").record(((Number) s.get("finalContextCount")).doubleValue());
    }
  }
  private long elapsed(long start) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start); }
}

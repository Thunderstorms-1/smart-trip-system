package com.smarttrip.rag.evaluation;

import com.fasterxml.jackson.databind.*;
import com.smarttrip.config.RagProperties;
import com.smarttrip.entity.TravelRequirement;
import com.smarttrip.rag.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.springframework.stereotype.Service;

/** Repeatable labeled retrieval evaluation; never invokes a ChatModel. */
@Service
public class RagEvaluationService {
  private final TravelRetrievalQueryBuilder queries;
  private final ElasticsearchHybridRetriever retriever;
  private final TravelRerankService reranker;
  private final RagProperties properties;
  private final ObjectMapper json = new ObjectMapper();
  public RagEvaluationService(TravelRetrievalQueryBuilder queries, ElasticsearchHybridRetriever retriever,
      TravelRerankService reranker, RagProperties properties) {
    this.queries = queries; this.retriever = retriever; this.reranker = reranker; this.properties = properties;
  }
  public Map<String, Object> evaluate(Path dataset) throws IOException {
    byte[] datasetBytes = Files.readAllBytes(dataset);
    JsonNode corpus = json.readTree(datasetBytes);
    var samples = corpus.has("cases") ? corpus.path("cases") : corpus.path("samples");
    if (!samples.isArray() || samples.isEmpty()) throw new IOException("Evaluation dataset has no labeled cases");
    var strategies = new LinkedHashMap<String, Object>();
    for (var mode : ElasticsearchHybridRetriever.Mode.values()) {
      if (mode == ElasticsearchHybridRetriever.Mode.HYBRID_RERANK && !properties.isRerankEnabled()) {
        strategies.put(mode.name(), Map.of("status", "SKIPPED_RERANK_DISABLED", "executedCases", 0));
        continue;
      }
      var details = new ArrayList<Map<String, Object>>();
      double recall = 0, precision = 0, mrr = 0, latency = 0;
      int labeled = 0, measured = 0, empty = 0, degraded = 0, errors = 0, irrelevant = 0, retrieved = 0,
          forbidden = 0, expectedEmpty = 0, unexpectedNonempty = 0, actualReranked = 0;
      for (var sample : samples) {
        long start = System.nanoTime();
        var relevant = strings(sample.path("relevantDocumentIds"));
        var relevantChunks = strings(sample.path("relevantChunkIds"));
        var forbiddenIds = strings(sample.path("forbiddenDocumentIds"));
        var detail = new LinkedHashMap<String, Object>();
        detail.put("id", sample.path("id").asText());
        try {
          var requirement = json.treeToValue(sample.path("requirement"), TravelRequirement.class);
          var query = queries.build(requirement);
          var result = retriever.retrieve(query, mode);
          List<RetrievalHit> hits = result.hits();
          String provider = "NONE", rerankFailure = null;
          if (mode == ElasticsearchHybridRetriever.Mode.HYBRID_RERANK) {
            var reranked = reranker.rerank(query.text(), hits);
            hits = reranked.hits(); provider = reranked.provider(); rerankFailure = reranked.fallbackReason();
            if ("HTTP_RERANK".equals(provider)) actualReranked++;
          }
          if (!result.failures().isEmpty() || rerankFailure != null) degraded++;
          boolean unavailable = "UNAVAILABLE".equals(result.status());
          if (unavailable) errors++; else measured++;
          var unique = new LinkedHashMap<String, RetrievalHit>();
          for (var hit : hits) unique.putIfAbsent(relevantChunks.isEmpty() ? hit.chunk().documentId() : hit.id(), hit);
          var ranked = new ArrayList<>(unique.entrySet());
          int correct = 0;
          double reciprocal = 0;
          for (int i = 0; i < ranked.size(); i++) {
            var entry = ranked.get(i);
            boolean match = relevantChunks.isEmpty() ? relevant.contains(entry.getKey()) : relevantChunks.contains(entry.getKey());
            if (i < 5) { retrieved++; if (match) correct++; else irrelevant++; }
            if (i < 10 && reciprocal == 0 && match) reciprocal = 1.0 / (i + 1);
            if (forbiddenIds.contains(entry.getValue().chunk().documentId())) forbidden++;
          }
          int denominator = relevantChunks.isEmpty() ? relevant.size() : relevantChunks.size();
          if (!unavailable && denominator > 0) { recall += (double) correct / denominator; mrr += reciprocal; labeled++; }
          if (!unavailable) {
            precision += correct / 5.0;
            if (ranked.isEmpty()) empty++;
            if (sample.path("expectNoResult").asBoolean(false)) {
              expectedEmpty++; if (!ranked.isEmpty()) unexpectedNonempty++;
            }
          }
          detail.put("status", result.status()); detail.put("failures", result.failures());
          detail.put("ids", ranked.stream().map(Map.Entry::getKey).toList());
          if (!unavailable) {
            detail.put("precisionAt5", correct / 5.0);
            if (denominator > 0) detail.put("recallAt5", (double) correct / denominator);
            detail.put("mrrAt10", reciprocal);
          }
          detail.put("rerankProvider", provider);
          if (rerankFailure != null) detail.put("rerankFallbackReason", rerankFailure);
        } catch (Exception ex) {
          errors++; detail.put("status", "ERROR"); detail.put("failureType", ex.getClass().getSimpleName());
        }
        long elapsed = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        latency += elapsed; detail.put("latencyMs", elapsed); details.add(detail);
      }
      var report = new LinkedHashMap<String, Object>();
      report.put("status", errors == details.size() ? "ALL_CASES_UNAVAILABLE" : "EXECUTED");
      report.put("executedCases", details.size()); report.put("measuredCases", measured); report.put("positiveLabeledCases", labeled);
      report.put("recallAt5", labeled == 0 ? null : recall / labeled);
      report.put("precisionAt5", measured == 0 ? null : precision / measured);
      report.put("mrrAt10", labeled == 0 ? null : mrr / labeled);
      report.put("averageLatencyMs", details.isEmpty() ? null : latency / details.size());
      report.put("irrelevantMixingRate", retrieved == 0 ? null : (double) irrelevant / retrieved);
      report.put("emptyResultCases", empty); report.put("degradedCases", degraded); report.put("errorCases", errors);
      report.put("forbiddenDocumentHits", forbidden); report.put("expectedEmptyCases", expectedEmpty);
      report.put("unexpectedNonemptyCases", unexpectedNonempty); report.put("correctExpectedEmptyCases", expectedEmpty - unexpectedNonempty);
      report.put("correctExpectedEmptyRate", expectedEmpty == 0 ? null : (double) (expectedEmpty - unexpectedNonempty) / expectedEmpty);
      report.put("actuallyRerankedCases", actualReranked);
      report.put("cases", details); strategies.put(mode.name(), report);
    }
    var report = new LinkedHashMap<String, Object>();
    report.put("measurement", "ACTUAL_RETRIEVAL_ONLY_NO_CHATMODEL");
    report.put("generatedAt", java.time.Instant.now().toString()); report.put("dataset", dataset.toAbsolutePath().toString());
    report.put("datasetVersion", corpus.path("datasetVersion").asText(corpus.path("schemaVersion").asText("unspecified")));
    report.put("datasetId", corpus.path("datasetId").asText("unspecified"));
    report.put("indexName", properties.getIndexName());
    try { report.put("datasetSha256", java.util.HexFormat.of().formatHex(
        java.security.MessageDigest.getInstance("SHA-256").digest(datasetBytes))); }
    catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    report.put("testKnowledgeIncluded", properties.isIncludeTestKnowledge());
    report.put("embeddingModel", properties.getEmbeddingModel()); report.put("embeddingVersion", properties.getEmbeddingVersion());
    report.put("strategies", strategies); return report;
  }
  private Set<String> strings(JsonNode node) {
    var values = new LinkedHashSet<String>();
    if (node.isArray()) for (var item : node) values.add(item.asText());
    return values;
  }
}

package com.smarttrip.rag;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.smarttrip.config.RagProperties;
import com.smarttrip.entity.TravelRequirement;
import com.smarttrip.rag.knowledge.*;
import java.net.URI;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Opt-in real Elasticsearch/corpus BM25 measurements.
 * Constant synthetic vectors only satisfy the index mapping; they are never semantic evaluation.
 * No Spring application, database, Ollama, ChatModel or generation provider is started.
 */
@EnabledIfEnvironmentVariable(named = "SMARTTRIP_RAG_REAL_ES_URL", matches = "https?://.+")
class RagCorpusElasticsearchEvaluationTests {
  private static final String PREFIX = "smarttrip-rag-bm25-fixture-";
  private final ObjectMapper json = new ObjectMapper();

  @Test void measuresRealBm25AgainstLabeledTravelCorpusWithoutEmbeddingCalls() throws Exception {
    var config = new RagProperties();
    config.setEnabled(true);
    config.setElasticsearchUrl(System.getenv("SMARTTRIP_RAG_REAL_ES_URL"));
    config.setElasticsearchUsername(System.getenv().getOrDefault("SMARTTRIP_RAG_REAL_ES_USERNAME", "elastic"));
    config.setElasticsearchPassword(System.getenv().getOrDefault("SMARTTRIP_RAG_REAL_ES_PASSWORD", ""));
    String alias = PREFIX + UUID.randomUUID().toString().replace("-", "");
    assertTrue(alias.matches(Pattern.quote(PREFIX) + "[0-9a-f]{32}"));
    config.setIndexName(alias);
    config.setEmbeddingModel("FIXTURE_VECTOR_NOT_BGE_M3");
    config.setEmbeddingVersion("fixture-v1");
    config.setRetrievalTimeoutMillis(15000);
    config.setIngestionTimeoutMillis(30000);
    config.setIncludeTestKnowledge(false);
    config.setRerankEnabled(false);
    var client = new ElasticsearchKnowledgeClient(config);
    var index = new KnowledgeIndexService(client, config);
    Path manifest = Path.of("../knowledge/travel-rag/manifest.json").toRealPath();
    Path datasetPath = manifest.getParent().resolve("evaluation/cases.json").toRealPath();
    var documents = new KnowledgeDocumentReader(config).readManifest(manifest);
    int chunkCount = documents.stream().mapToInt(doc -> doc.chunks().size()).sum();
    assertEquals(8, documents.size(), "The initial corpus must be the reviewed eight-document snapshot");
    assertEquals(16, chunkCount, "Report actual section splitting; do not silently replace real corpus with fixtures");
    byte[] datasetBytes = Files.readAllBytes(datasetPath);
    JsonNode dataset = json.readTree(datasetBytes);
    assertEquals(26, dataset.path("cases").size());
    JsonNode server = client.request("GET", "/", null);
    var embedCalls = new AtomicInteger();
    var noEmbedding = new OllamaKnowledgeEmbedding(config) {
      @Override public float[] embed(String text) {
        embedCalls.incrementAndGet();
        throw new AssertionError("BM25 corpus evaluation must never invoke an embedding model");
      }
    };
    var queries = new TravelRetrievalQueryBuilder(config);
    var retriever = new ElasticsearchHybridRetriever(client, noEmbedding, config);
    String physical = null;
    try {
      physical = index.createStaging(3);
      assertOwned(alias, physical);
      // Explicit index-only synthetic vectors. Text and source metadata come from the real corpus.
      for (var document : documents) for (var chunk : document.chunks())
        index.putChunk(physical, chunk, new float[] {1f, 0f, 0f}, 3);
      index.publish(List.of(), physical);
      client.validateIndex(3);
      assertEquals(chunkCount, index.count(alias));
      var cases = new ArrayList<Map<String, Object>>();
      double recallTotal = 0, precisionTotal = 0, mrrTotal = 0, latencyTotal = 0;
      int positive = 0, measured = 0, empty = 0, degraded = 0, errors = 0;
      int irrelevant = 0, retrieved = 0, forbidden = 0, expectedEmpty = 0, correctEmpty = 0;
      for (var sample : dataset.path("cases")) {
        long start = System.nanoTime();
        var row = new LinkedHashMap<String, Object>();
        var relevant = strings(sample.path("relevantDocumentIds"));
        var forbiddenIds = strings(sample.path("forbiddenDocumentIds"));
        row.put("id", sample.path("id").asText());
        row.put("description", sample.path("description").asText());
        row.put("relevantDocumentIds", relevant);
        row.put("forbiddenDocumentIds", forbiddenIds);
        row.put("expectNoResult", sample.path("expectNoResult").asBoolean(false));
        try {
          var requirement = json.treeToValue(sample.path("requirement"), TravelRequirement.class);
          var result = retriever.retrieve(queries.build(requirement), ElasticsearchHybridRetriever.Mode.BM25);
          var ranked = new LinkedHashMap<String, RetrievalHit>();
          result.hits().forEach(hit -> ranked.putIfAbsent(hit.chunk().documentId(), hit));
          var ids = new ArrayList<>(ranked.keySet());
          row.put("status", result.status());
          row.put("failures", result.failures());
          row.put("retrievedDocumentIds", ids);
          row.put("retrievedChunkIds", result.hits().stream().map(RetrievalHit::id).toList());
          row.put("bm25HitCount", result.bm25HitCount());
          row.put("vectorHitCount", result.vectorHitCount());
          assertEquals(0, result.vectorHitCount());
          if (!result.failures().isEmpty()) degraded++;
          if ("UNAVAILABLE".equals(result.status())) {
            errors++;
          } else {
            measured++;
            int correct = 0;
            double reciprocal = 0;
            for (int rank = 0; rank < ids.size(); rank++) {
              String id = ids.get(rank);
              boolean match = relevant.contains(id);
              if (rank < 5) { retrieved++; if (match) correct++; else irrelevant++; }
              if (rank < 10 && reciprocal == 0 && match) reciprocal = 1.0 / (rank + 1);
              if (forbiddenIds.contains(id)) forbidden++;
            }
            if (!relevant.isEmpty()) {
              recallTotal += (double) correct / relevant.size();
              mrrTotal += reciprocal; positive++;
              row.put("recallAt5", (double) correct / relevant.size());
              row.put("mrrAt10", reciprocal);
            } else {
              row.put("recallAt5", null); row.put("mrrAt10", null);
            }
            double precision = correct / 5.0;
            precisionTotal += precision; row.put("precisionAt5", precision);
            if (ids.isEmpty()) empty++;
            if (sample.path("expectNoResult").asBoolean(false)) {
              expectedEmpty++; if (ids.isEmpty()) correctEmpty++;
            }
          }
        } catch (Exception ex) {
          errors++; row.put("status", "ERROR");
          row.put("failureCode", ex instanceof RagException re ? re.code() : ex.getClass().getSimpleName());
          row.putIfAbsent("retrievedDocumentIds", List.of());
        }
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        latencyTotal += elapsed; row.put("latencyMs", elapsed); cases.add(row);
      }
      var metrics = new LinkedHashMap<String, Object>();
      metrics.put("status", errors == cases.size() ? "ALL_CASES_UNAVAILABLE" : "MEASURED");
      metrics.put("executedCases", cases.size()); metrics.put("measuredCases", measured);
      metrics.put("positiveLabeledCases", positive);
      metrics.put("recallAt5", positive == 0 ? null : recallTotal / positive);
      metrics.put("precisionAt5", measured == 0 ? null : precisionTotal / measured);
      metrics.put("mrrAt10", positive == 0 ? null : mrrTotal / positive);
      metrics.put("averageLatencyMs", cases.isEmpty() ? null : latencyTotal / cases.size());
      metrics.put("irrelevantMixingRate", retrieved == 0 ? null : (double) irrelevant / retrieved);
      metrics.put("emptyResultCases", empty); metrics.put("degradedCases", degraded);
      metrics.put("errorCases", errors); metrics.put("forbiddenDocumentHits", forbidden);
      metrics.put("expectedEmptyCases", expectedEmpty); metrics.put("correctExpectedEmptyCases", correctEmpty);
      metrics.put("correctExpectedEmptyRate", expectedEmpty == 0 ? null : (double) correctEmpty / expectedEmpty);
      metrics.put("cases", cases);
      var strategies = new LinkedHashMap<String, Object>();
      strategies.put("BM25", metrics);
      for (String unmeasured : List.of("VECTOR", "HYBRID", "HYBRID_RERANK")) {
        var result = new LinkedHashMap<String, Object>();
        result.put("status", "UNVERIFIED_REAL_MODELS_NOT_RUN");
        result.put("recallAt5", null); result.put("precisionAt5", null); result.put("mrrAt10", null);
        result.put("averageLatencyMs", null); result.put("irrelevantMixingRate", null);
        result.put("executedCases", 0);
        strategies.put(unmeasured, result);
      }
      var report = new LinkedHashMap<String, Object>();
      report.put("measurement", "REAL_ELASTICSEARCH_CORPUS_BM25_ONLY_NO_CHATMODEL");
      report.put("generatedAt", Instant.now().toString());
      report.put("datasetId", dataset.path("datasetId").asText());
      report.put("datasetSchemaVersion", dataset.path("schemaVersion").asText());
      report.put("datasetSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(datasetBytes)));
      report.put("datasetPath", datasetPath.toString());
      report.put("labelingProvenance", dataset.path("description").asText());
      report.put("corpusDocumentCount", documents.size()); report.put("corpusChunkCount", chunkCount);
      report.put("corpusDocuments", documents.stream().map(doc -> Map.of("documentId", doc.document().documentId(),
          "contentHash", doc.contentHash(), "chunkCount", doc.chunks().size())).toList());
      var actualEs = new LinkedHashMap<String, Object>();
      actualEs.put("version", server.path("version").path("number").asText());
      actualEs.put("buildFlavor", server.path("version").path("build_flavor").asText());
      URI endpoint = URI.create(config.getElasticsearchUrl());
      actualEs.put("endpoint", endpoint.getScheme() + "://" + endpoint.getHost() + ":" + endpoint.getPort());
      actualEs.put("indexAlias", alias); actualEs.put("physicalIndex", physical);
      actualEs.put("textAnalyzer", "cjk"); report.put("actualElasticsearch", actualEs);
      report.put("embeddingModelInFixtureIndex", config.getEmbeddingModel());
      report.put("embeddingCalls", embedCalls.get());
      report.put("modelQualityDisclaimer", "Only real Elasticsearch BM25 over the reviewed travel corpus was measured. "
          + "Constant synthetic 3D vectors [1,0,0] exist solely to satisfy the real index mapping; "
          + "they are NOT BGE-M3 embeddings and were never queried. "
          + "VECTOR/HYBRID/RERANK model quality remains UNVERIFIED. No ChatModel was invoked.");
      report.put("metricNotes", List.of(
          "Document IDs are deduplicated by first actual ranked chunk before Recall@5, Precision@5 and MRR@10.",
          "Precision@5 uses denominator 5; empty-relevance cases do not inflate Recall or MRR.",
          "Latency includes actual retrieval, defensive eligibility, one-branch RRF and public-scope empty probes.",
          "No synthetic negative fixtures were added: zero forbidden IDs alone does not prove polluted-index isolation.",
          "Labels were authored by the implementation assistant and have not had independent business review.",
          "Small single-instance corpus measurements do not establish production latency or general travel quality."));
      report.put("strategies", strategies);
      Path output = Path.of(System.getProperty("smarttrip.build-dir", "target"))
          .toAbsolutePath().resolve("rag-corpus-bm25-evaluation.json");
      Files.createDirectories(output.getParent());
      Files.writeString(output, json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
      System.out.println("Real BM25 corpus report: " + output);
      assertEquals(0, embedCalls.get(), "No embedding model may be invoked in this BM25-only test");
      assertEquals(26, measured, "Infrastructure failures must be fixed or explicitly reported; quality has no threshold");
      assertEquals(0, errors);
      // No quality threshold: low recall/precision is evidence to report, never a reason to alter labels or core code.
    } finally {
      retriever.close();
      if (physical != null) {
        assertOwned(alias, physical);
        try {
          client.request("POST", "/_aliases", Map.of("actions", List.of(Map.of("remove",
              Map.of("index", physical, "alias", alias, "must_exist", false)))));
        } finally { index.discard(physical); }
      }
    }
  }
  private static void assertOwned(String alias, String physical) {
    if (!alias.matches(Pattern.quote(PREFIX) + "[0-9a-f]{32}")
        || !physical.matches(Pattern.quote(alias) + "-g-[0-9a-f]{32}"))
      throw new IllegalStateException("Refusing to touch an index outside this exact randomized test namespace");
  }
  private Set<String> strings(JsonNode values) {
    var out = new LinkedHashSet<String>();
    if (values.isArray()) for (var value : values) out.add(value.asText());
    return out;
  }
}

package com.smarttrip.rag;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.smarttrip.config.RagProperties;
import com.smarttrip.rag.knowledge.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Opt-in actual ES ACL/expiry verification against a deliberately polluted, isolated test index. */
@EnabledIfEnvironmentVariable(named = "SMARTTRIP_RAG_REAL_ES_URL", matches = "https?://.+")
class RagElasticsearchAccessTests {
  private static final String PREFIX = "smarttrip-rag-acl-fixture-";
  private final ObjectMapper json = new ObjectMapper();

  @Test void realEsFiltersPrivateOwnerUnauthorizedAndExpiredBeforeContextConstruction() {
    var config = new RagProperties();
    config.setElasticsearchUrl(System.getenv("SMARTTRIP_RAG_REAL_ES_URL"));
    config.setElasticsearchUsername(System.getenv().getOrDefault("SMARTTRIP_RAG_REAL_ES_USERNAME", "elastic"));
    config.setElasticsearchPassword(System.getenv().getOrDefault("SMARTTRIP_RAG_REAL_ES_PASSWORD", ""));
    String alias = PREFIX + UUID.randomUUID().toString().replace("-", "");
    config.setIndexName(alias);
    config.setEmbeddingModel("FIXTURE_VECTOR_NOT_BGE_M3");
    config.setEmbeddingVersion("fixture-v1");
    config.setRetrievalTimeoutMillis(15000);
    config.setIngestionTimeoutMillis(30000);
    config.setIncludeTestKnowledge(false);
    config.setContextTokenBudget(3000);
    var rawRetrievedIds = new AtomicReference<List<String>>();
    // Capture the genuine server response, before defensive Java filtering can hide a broken ES ACL query.
    var client = new ElasticsearchKnowledgeClient(config) {
      @Override public JsonNode search(Object body) {
        JsonNode response = super.search(body);
        if (json.valueToTree(body).path("size").asInt() > 0) {
          var ids = new ArrayList<String>();
          response.path("hits").path("hits").forEach(hit -> ids.add(hit.path("_source").path("chunkId").asText()));
          rawRetrievedIds.set(List.copyOf(ids));
        }
        return response;
      }
    };
    var index = new KnowledgeIndexService(client, config);
    var embedCalls = new AtomicInteger();
    var embedding = new OllamaKnowledgeEmbedding(config) {
      @Override public float[] embed(String text) {
        embedCalls.incrementAndGet();
        throw new AssertionError("This actual BM25 ACL test must never invoke embedding");
      }
    };
    var retriever = new ElasticsearchHybridRetriever(client, embedding, config);
    String physical = null;
    try {
      physical = index.createStaging(3);
      assertOwned(alias, physical);
      String recent = LocalDate.now().minusDays(1).toString();
      var fixtures = List.of(
          chunk("allowed", "PUBLIC", null, true, "CULTURE", recent, "ALLOWED_REFERENCE"),
          chunk("private-owner", "PRIVATE", 900001L, true, "CULTURE", recent, "FORBIDDEN_PRIVATE_OWNER"),
          // Inconsistent poisoned metadata must still fail the owner filter even if visibility says PUBLIC.
          chunk("public-owner", "PUBLIC", 900002L, true, "CULTURE", recent, "FORBIDDEN_PUBLIC_OWNER"),
          chunk("unauthorized", "PUBLIC", null, false, "CULTURE", recent, "FORBIDDEN_UNAUTHORIZED"),
          chunk("expired-operations", "PUBLIC", null, true, "OPERATIONS", "2000-01-01", "FORBIDDEN_EXPIRED"));
      for (var fixture : fixtures) index.putChunk(physical, fixture, new float[] {1f, 0f, 0f}, 3);
      index.publish(List.of(), physical);
      client.validateIndex(3);
      assertEquals(5, index.count(alias));
      // Operator-only evidence that all synthetic negative records really exist in this isolated index.
      JsonNode polluted = client.request("POST", "/" + alias + "/_search", Map.of("size", 10,
          "query", Map.of("match_all", Map.of()), "_source", List.of("chunkId")));
      var seededIds = new HashSet<String>();
      polluted.path("hits").path("hits").forEach(hit -> seededIds.add(hit.path("_source").path("chunkId").asText()));
      assertEquals(Set.of("allowed", "private-owner", "public-owner", "unauthorized", "expired-operations"), seededIds);
      var requirement = json.convertValue(Map.of("destinations", List.of("苏州"),
          "interests", List.of("园林", "参考")), com.smarttrip.entity.TravelRequirement.class);
      var result = retriever.retrieve(new TravelRetrievalQueryBuilder(config).build(requirement),
          ElasticsearchHybridRetriever.Mode.BM25);
      assertEquals("SUCCESS", result.status());
      assertTrue(result.failures().isEmpty());
      assertEquals(List.of("allowed"), rawRetrievedIds.get(),
          "Elasticsearch itself must filter denied records before Java processes the response");
      assertEquals(List.of("allowed"), result.hits().stream().map(RetrievalHit::id).toList());
      var context = new RagContextBuilder(config).build(result.hits());
      assertEquals(1, context.sources().size());
      assertEquals("allowed", context.sources().get(0).get("chunkId"));
      assertTrue(context.prompt().contains("ALLOWED_REFERENCE"));
      for (String marker : List.of("FORBIDDEN_PRIVATE_OWNER", "FORBIDDEN_PUBLIC_OWNER",
          "FORBIDDEN_UNAUTHORIZED", "FORBIDDEN_EXPIRED")) assertFalse(context.prompt().contains(marker));
      assertEquals(0, embedCalls.get());
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
  private KnowledgeChunk chunk(String id, String visibility, Long owner, boolean authorized,
      String category, String sourceDate, String marker) {
    return new KnowledgeChunk("acl-fixture-" + id, id, "苏州园林参考",
        "苏州园林 参考 " + marker, List.of("苏州"), List.of("江苏"), category, List.of("ALL"),
        "Synthetic actual-ES ACL fixture", "https://example.org/smarttrip-acl-fixture",
        sourceDate, java.time.Instant.now().toString(), visibility, owner, "1",
        "fixture-content-" + id, "FIXTURE_VECTOR_NOT_BGE_M3", "fixture-v1", authorized, false);
  }
  private static void assertOwned(String alias, String physical) {
    if (!alias.matches(Pattern.quote(PREFIX) + "[0-9a-f]{32}")
        || !physical.matches(Pattern.quote(alias) + "-g-[0-9a-f]{32}"))
      throw new IllegalStateException("Refusing to touch an index outside this exact randomized test namespace");
  }
}

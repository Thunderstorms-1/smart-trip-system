package com.smarttrip.rag;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.smarttrip.config.RagProperties;
import com.smarttrip.rag.evaluation.RagEvaluationService;
import com.smarttrip.rag.knowledge.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Explicit opt-in: real local BGE-M3 and ES; no Spring application, database or ChatModel. */
@EnabledIfEnvironmentVariable(named="SMARTTRIP_RAG_HYBRID_LIVE", matches="true")
class RagHybridLiveIntegrationTests {
  private static final String PREFIX = "smarttrip-rag-hybrid-live-";
  private final ObjectMapper json = new ObjectMapper();

  @Test void evaluatesUnchangedLabeledQueriesUsingRealEmbeddingAndIndependentIndex() throws Exception {
    var config = new RagProperties();
    config.setEnabled(true);
    config.setElasticsearchUrl(required("SMARTTRIP_RAG_REAL_ES_URL"));
    config.setElasticsearchUsername(System.getenv().getOrDefault("SMARTTRIP_RAG_REAL_ES_USERNAME", "elastic"));
    config.setElasticsearchPassword(required("SMARTTRIP_RAG_REAL_ES_PASSWORD"));
    config.setEmbeddingBaseUrl(System.getenv().getOrDefault("SMARTTRIP_RAG_LIVE_OLLAMA_URL", "http://127.0.0.1:11434"));
    config.setEmbeddingModel(System.getenv().getOrDefault("SMARTTRIP_RAG_LIVE_MODEL", "bge-m3"));
    config.setEmbeddingVersion("v1");
    config.setEmbeddingTimeoutMillis(60000);
    config.setRetrievalTimeoutMillis(90000);
    config.setIngestionTimeoutMillis(120000);
    config.setRerankEnabled(false);
    String alias = PREFIX + UUID.randomUUID().toString().replace("-", "");
    config.setIndexName(alias);
    Set<String> forbidden = new HashSet<>();
    AtomicInteger embeddingCalls = new AtomicInteger(), rawSearches = new AtomicInteger();
    var embedding = new OllamaKnowledgeEmbedding(config) {
      @Override public float[] embed(String text) { embeddingCalls.incrementAndGet(); return super.embed(text); }
    };
    var client = new ElasticsearchKnowledgeClient(config) {
      @Override public JsonNode search(Object body) {
        var result = super.search(body);
        if (json.valueToTree(body).path("size").asInt() > 0) {
          rawSearches.incrementAndGet();
          // Check the actual ES response BEFORE defensive Java filtering can hide a broken ACL.
          for (var hit : result.path("hits").path("hits")) {
            var source = hit.path("_source");
            assertFalse(forbidden.contains(source.path("documentId").asText()));
            assertEquals("PUBLIC", source.path("visibility").asText());
            assertTrue(source.path("authorized").asBoolean());
            assertTrue(source.path("ownerId").isMissingNode() || source.path("ownerId").isNull());
            assertFalse(source.path("testOnly").asBoolean());
            assertEquals(config.getEmbeddingModel(), source.path("embeddingModel").asText());
            assertEquals(config.getEmbeddingVersion(), source.path("embeddingVersion").asText());
          }
        }
        return result;
      }
    };
    var index = new KnowledgeIndexService(client, config);
    var queries = new TravelRetrievalQueryBuilder(config);
    var contexts = new RagContextBuilder(config);
    var retriever = new ElasticsearchHybridRetriever(client, embedding, config) {
      @Override public Result retrieve(TravelRetrievalQueryBuilder.Query query, Mode mode) {
        var result = super.retrieve(query, mode);
        for (var hit : result.hits()) {
          assertNotNull(hit.chunk().sourceName()); assertFalse(hit.chunk().sourceName().isBlank());
          assertTrue(hit.chunk().sourceUrl().startsWith("https://"));
          assertNotNull(hit.chunk().documentVersion()); assertNotNull(hit.chunk().contentHash());
          assertFalse(forbidden.contains(hit.chunk().documentId()));
        }
        for (var source : contexts.build(result.hits(), query).sources())
          assertFalse(forbidden.contains(source.get("documentId")));
        return result;
      }
    };
    String physical = null;
    try {
      Path manifest = Path.of("../knowledge/travel-rag/manifest.json").toRealPath();
      var docs = new KnowledgeDocumentReader(config).readManifest(manifest);
      assertEquals(8, docs.size());
      var chunks = docs.stream().flatMap(doc -> doc.chunks().stream()).toList();
      assertEquals(16, chunks.size());
      float[] first = embedding.embed(chunks.get(0).content());
      int dimensions = first.length;
      physical = index.createStaging(dimensions); owned(alias, physical);
      for (int i=0; i<chunks.size(); i++) {
        float[] vector = i==0 ? first : embedding.embed(chunks.get(i).content());
        assertEquals(dimensions, vector.length);
        index.putChunk(physical, chunks.get(i), vector, dimensions);
      }
      // All negative fixtures reuse an actual vector of the IDENTICAL text, never a synthetic vector.
      List<Map<String,Object>> negatives = List.of(
          Map.of("visibility","PRIVATE","ownerId",42),
          Map.of("ownerId",42), Map.of("authorized",false),
          Map.of("embeddingVersion","incompatible-version"), Map.of("testOnly",true),
          Map.of("category","OPERATIONS","sourceUpdatedAt","2000-01-01"));
      for (int i=0; i<negatives.size(); i++) {
        var fields = json.<Map<String,Object>>convertValue(chunks.get(0),
            json.getTypeFactory().constructMapType(LinkedHashMap.class,String.class,Object.class));
        String id="live-negative-"+i;
        fields.putAll(negatives.get(i)); fields.put("documentId",id); fields.put("chunkId",id);
        forbidden.add(id);
        index.putChunk(physical,json.convertValue(fields,KnowledgeChunk.class),first,dimensions);
      }
      index.publish(List.of(),physical);
      assertEquals(22,index.count(alias));
      client.validateIndex(dimensions);
      assertEquals(dimensions,client.mappedDimensions());
      assertEquals("DIMENSION_MISMATCH",assertThrows(RagException.class,
          ()->client.validateIndex(dimensions+1)).code());
      var other = new RagProperties(); other.setIndexName(alias);
      other.setElasticsearchUrl(config.getElasticsearchUrl());
      other.setElasticsearchUsername(config.getElasticsearchUsername());
      other.setElasticsearchPassword(config.getElasticsearchPassword());
      other.setEmbeddingModel(config.getEmbeddingModel()); other.setEmbeddingVersion("wrong-version");
      assertEquals("EMBEDDING_MODEL_MISMATCH",assertThrows(RagException.class,
          ()->new ElasticsearchKnowledgeClient(other).validateIndex(0)).code());
      var validityChecks = verifyAnnualValidity(client, retriever, queries, contexts);
      var evaluator = new RagEvaluationService(queries,retriever,new TravelRerankService(config),config);
      var report = new LinkedHashMap<String,Object>(evaluator.evaluate(manifest.getParent().resolve("evaluation/cases.json")));
      report.put("validityChecks",validityChecks);
      report.put("measurement","REAL_BGE_M3_ES_BM25_VECTOR_HYBRID_NO_CHATMODEL");
      report.put("actualVectorDimensions",dimensions);
      report.put("actualElasticsearchVersion",client.request("GET","/",null).path("version").path("number").asText());
      report.put("corpusDocumentCount",8); report.put("corpusChunkCount",16);
      report.put("isolatedNegativeFixtureCount",6); report.put("negativeFixtureIds",forbidden);
      report.put("embeddingCalls",embeddingCalls.get()); report.put("rawSearchesChecked",rawSearches.get());
      report.put("physicalIndex",physical); report.put("paidChatModelCalls",0);
      report.put("qualityDisclaimer","Assistant-authored qrels, unchanged; small seed corpus. No quality threshold or assumption that hybrid is better. Rerank not deployed.");
      Path output=Path.of(System.getProperty("smarttrip.build-dir","target")).toAbsolutePath().resolve("rag-hybrid-live-evaluation.json");
      Files.createDirectories(output.getParent());
      Files.writeString(output,json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
      var strategies=json.valueToTree(report).path("strategies");
      for(String mode:List.of("BM25","VECTOR","HYBRID")) {
        assertEquals(26,strategies.path(mode).path("measuredCases").asInt(),mode);
        assertEquals(0,strategies.path(mode).path("errorCases").asInt(),mode);
        assertEquals(0,strategies.path(mode).path("degradedCases").asInt(),mode);
      }
      assertTrue(embeddingCalls.get()>=68); // 16 corpus + 26 vector + 26 hybrid query embeddings.
      System.out.println("Actual hybrid report written: "+output);
    } finally {
      retriever.close();
      if(physical!=null) { owned(alias,physical); index.discard(physical); }
    }
  }
  private List<Map<String,Object>> verifyAnnualValidity(ElasticsearchKnowledgeClient client,
      ElasticsearchHybridRetriever retriever, TravelRetrievalQueryBuilder queries, RagContextBuilder contexts) {
    String annual="travel-daxinganling-autumn-v1";
    var scenarios=List.of(
        Map.of("date","2026-09-15","days",1,"status","APPLICABLE"),
        Map.of("date","2026-11-15","days",1,"status","APPLICABLE"),
        Map.of("date","2026-11-20","days",1,"status","EXCLUDED"),
        Map.of("date","2027-10-15","days",8,"status","EXCLUDED"),
        Map.of("date","UNKNOWN","days",8,"status","EXCLUDED"),
        Map.of("date","2026-11-14","days",5,"status","PARTIALLY_APPLICABLE"),
        Map.of("date","2026-09-13","days",5,"status","PARTIALLY_APPLICABLE"));
    var report=new ArrayList<Map<String,Object>>();
    for(var scenario:scenarios) {
      var fields=new LinkedHashMap<String,Object>(); fields.put("destinations",List.of("大兴安岭"));
      fields.put("startDate",scenario.get("date")); fields.put("days",scenario.get("days"));
      var query=queries.build(json.convertValue(fields,com.smarttrip.entity.TravelRequirement.class));
      boolean expected=!"EXCLUDED".equals(scenario.get("status"));
      var raw=client.search(Map.of("size",100,"_source",Map.of("excludes",List.of("embedding")),
          "query",Map.of("bool",Map.of("filter",query.filters()))));
      boolean rawFound=false;
      for(var row:raw.path("hits").path("hits"))if(annual.equals(row.path("_source").path("documentId").asText()))rawFound=true;
      assertEquals(expected,rawFound,"ES date filter "+scenario);
      for(var mode:List.of(ElasticsearchHybridRetriever.Mode.BM25,ElasticsearchHybridRetriever.Mode.VECTOR,
          ElasticsearchHybridRetriever.Mode.HYBRID)) {
        var result=retriever.retrieve(query,mode); assertTrue(result.failures().isEmpty(),result.failures().toString());
        assertEquals(expected,result.hits().stream().anyMatch(h->annual.equals(h.chunk().documentId())),mode+" "+scenario);
        var context=contexts.build(result.hits(),query);
        assertEquals(expected,!context.sources().isEmpty());
        if(expected) {
          var source=context.sources().get(0); assertEquals(scenario.get("status"),source.get("applicability"));
          assertEquals("2026-09-15",source.get("validFrom")); assertEquals("2026-11-15",source.get("validUntil"));
          if("PARTIALLY_APPLICABLE".equals(source.get("applicability"))) {
            assertTrue(source.containsKey("applicabilityNote"));
            assertEquals(scenario.get("date").equals("2026-11-14")?"2026-11-15":"2026-09-17",source.get("applicableUntil"));
          }
        }
      }
      report.add(Map.of("scenario",scenario,"rawEsAndAllThreeStrategies","PASSED"));
    }
    var mapping=client.request("GET","/"+client.indexName()+"/_mapping",null).elements().next().path("mappings").path("properties");
    for(String name:List.of("validFrom","validUntil")) {
      assertEquals("date",mapping.path(name).path("type").asText());
      assertEquals("strict_date",mapping.path(name).path("format").asText());
    }
    return report;
  }
  private static String required(String key) {
    String value=System.getenv(key);
    if(value==null||value.isBlank())throw new IllegalStateException("Missing explicit live-test configuration: "+key);
    return value;
  }
  private static void owned(String alias,String physical) {
    if(!alias.matches(Pattern.quote(PREFIX)+"[0-9a-f]{32}")
        ||!physical.matches(Pattern.quote(alias)+"-g-[0-9a-f]{32}"))
      throw new IllegalStateException("Refusing cleanup outside this exact randomized live-test namespace");
  }
}

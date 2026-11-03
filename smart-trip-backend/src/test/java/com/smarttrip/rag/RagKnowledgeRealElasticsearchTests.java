package com.smarttrip.rag;

import com.smarttrip.config.RagProperties;
import com.smarttrip.rag.knowledge.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/** Optional real ES verification. Synthetic 3D vectors are fixtures, never represented as BGE-M3 output. */
@EnabledIfEnvironmentVariable(named="SMARTTRIP_RAG_REAL_ES_URL",matches="https?://.+")
class RagKnowledgeRealElasticsearchTests {
  private KnowledgeChunk chunk(String doc,String id,String text) {
    return new KnowledgeChunk(doc,id,"苏州测试",text,List.of("苏州"),List.of("江苏"),"CULTURE",List.of("ALL"),
        "Synthetic integration fixture","https://example.org/test","2026-10-28","2026-10-28T00:00:00Z", "PUBLIC",null,"1","fixture-hash","fixture-vector","v1",true,true);
  }
  @Test void realIndexCjkKnnAtomicReplacementDeletionAndRollback() {
    RagProperties properties=new RagProperties();
    properties.setElasticsearchUrl(System.getenv("SMARTTRIP_RAG_REAL_ES_URL"));
    properties.setElasticsearchUsername(System.getenv().getOrDefault("SMARTTRIP_RAG_REAL_ES_USERNAME","elastic"));
    properties.setElasticsearchPassword(System.getenv().getOrDefault("SMARTTRIP_RAG_REAL_ES_PASSWORD",""));
    properties.setIndexName("smarttrip-rag-fixture-"+UUID.randomUUID().toString().replace("-",""));
    properties.setEmbeddingModel("fixture-vector"); properties.setEmbeddingVersion("v1");
    properties.setRetrievalTimeoutMillis(10000); properties.setIngestionTimeoutMillis(30000);
    var client=new ElasticsearchKnowledgeClient(properties); var index=new KnowledgeIndexService(client,properties);
    List<String> created=new ArrayList<>();
    try {
      String first=index.createStaging(3); created.add(first);
      index.putChunk(first,chunk("replace-me","first","苏州园林旧片段"),new float[]{1,0,0},3);
      index.putChunk(first,chunk("keep-me","retained","苏州交通保留片段"),new float[]{0,1,0},3);
      index.publish(List.of(),first); client.validateIndex(3); assertEquals(2,index.count(properties.getIndexName()));
      var lexical=client.search(Map.of("query",Map.of("match",Map.of("content","苏州园林")),"_source",Map.of("excludes",List.of("embedding"))));
      assertTrue(lexical.path("hits").path("total").path("value").asInt()>0);
      var semantic=client.search(Map.of("knn",Map.of("field","embedding","query_vector",List.of(1,0,0),"k",1,"num_candidates",10)));
      assertEquals("first",semantic.path("hits").path("hits").get(0).path("_id").asText());
      String updated=index.createStaging(3); created.add(updated);
      assertEquals(1,index.copyUnchanged(List.of(first),updated,List.of("replace-me")));
      index.putChunk(updated,chunk("replace-me","second","苏州园林新片段"),new float[]{1,0,0},3);
      index.publish(List.of(first),updated);
      assertEquals(2,index.count(properties.getIndexName()));
      assertEquals(0,client.search(Map.of("query",Map.of("ids",Map.of("values",List.of("first"))))).path("hits").path("total").path("value").asInt());
      // Failed writes never alter the read alias.
      assertEquals("DIMENSION_MISMATCH",assertThrows(RagException.class,()->index.putChunk(updated,chunk("bad","bad","bad"),new float[]{1,2},3)).code());
      assertEquals(List.of(updated),index.currentIndices());
      String deleted=index.createStaging(3); created.add(deleted);
      assertEquals(1,index.copyUnchanged(List.of(updated),deleted,List.of("replace-me")));
      index.publish(List.of(updated),deleted); assertEquals(1,index.count(properties.getIndexName()));
      index.restore(List.of(updated),deleted); assertEquals(List.of(updated),index.currentIndices());
      assertEquals(2,index.count(properties.getIndexName()));
      properties.setEmbeddingVersion("v2");
      assertEquals("EMBEDDING_MODEL_MISMATCH",assertThrows(RagException.class,()->client.validateIndex(3)).code());
    } finally {
      // Only exact indices created by this test are removed; never touches any existing stack/index.
      for(String physical:created) try { index.discard(physical); } catch(RuntimeException ignored) {}
    }
  }
}
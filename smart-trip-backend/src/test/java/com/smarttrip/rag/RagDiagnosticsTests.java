package com.smarttrip.rag;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.fasterxml.jackson.databind.*;
import com.smarttrip.common.BusinessException;
import com.smarttrip.config.RagProperties;
import com.smarttrip.entity.TravelRequirement;
import com.smarttrip.rag.knowledge.*;
import com.smarttrip.service.*;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.ai.chat.client.ChatClient;

/** Mock-only diagnostic/presentation checks. No real service or database is contacted. */
class RagDiagnosticsTests {
  final ObjectMapper json=new ObjectMapper();
  final RagProperties config=new RagProperties();
  final ElasticsearchKnowledgeClient es=mock(ElasticsearchKnowledgeClient.class);
  final KnowledgeIndexService index=mock(KnowledgeIndexService.class);
  final OllamaKnowledgeEmbedding embedding=mock(OllamaKnowledgeEmbedding.class);
  final TravelRetrievalQueryBuilder queries=new TravelRetrievalQueryBuilder(config);
  ElasticsearchHybridRetriever retrieval;
  TravelRagService rag;
  RagDiagnosticsService diagnostics;

  @BeforeEach void setup() {
    config.setEnabled(true); config.setContextTokenBudget(4000);
    retrieval=new ElasticsearchHybridRetriever(es,embedding,config);
    rag=new TravelRagService(config,queries,retrieval,new TravelRerankService(config),
        new RagContextBuilder(config),new org.springframework.beans.factory.support.StaticListableBeanFactory().getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class));
    diagnostics=new RagDiagnosticsService(config,es,index,queries,rag,new TravelRequirementService());
  }
  @AfterEach void cleanup() { retrieval.close(); }
  TravelRequirement requirement() { return json.convertValue(Map.of("destinations",List.of("苏州"),"pace","RELAXED"),TravelRequirement.class); }
  KnowledgeChunk chunk(String id,String visibility,Long owner,boolean authorized,String content) {
    return new KnowledgeChunk("doc-"+id,id,"公开园林资料",content,List.of("苏州"),List.of("江苏"),"CULTURE",List.of("ALL"),
        "核对来源","https://example.org/"+id,LocalDate.now().minusDays(1).toString(),"2026-10-29T00:00:00Z",
        visibility,owner,"v1","hash-"+id,"bge-m3","v1",authorized,false);
  }
  JsonNode hits(KnowledgeChunk... chunks) {
    return json.valueToTree(Map.of("hits",Map.of("hits",Arrays.stream(chunks).map(c->Map.of("_score",1.0,"_source",c)).toList(),
        "total",Map.of("value",chunks.length,"relation","eq"))));
  }
  void readyCounts(String body) throws Exception {
    when(index.currentIndices()).thenReturn(List.of("owned-physical"));
    when(es.mappedDimensions()).thenReturn(1024);
    when(es.search(any())).thenReturn(json.readTree(body));
  }
  @Test void defaultStatusIsConfigurationOnlyAndNeverTouchesServices() {
    var result=diagnostics.status(false);
    assertEquals("NOT_CHECKED",result.get("indexStatus")); assertNull(result.get("mappedVectorDimensions"));
    verifyNoInteractions(es,index,embedding);
  }
  @Test void probeReadsActualIndexResponseAndKeepsEmbeddingUnverified() throws Exception {
    readyCounts("{\"hits\":{\"total\":{\"value\":16,\"relation\":\"eq\"}},\"aggregations\":{\"documents\":{\"value\":8}}}");
    var result=diagnostics.status(true);
    assertEquals("READY",result.get("indexStatus")); assertEquals(16L,result.get("publicChunkCount"));
    assertEquals(8L,result.get("publicDocumentCountEstimate")); assertEquals(1024,result.get("mappedVectorDimensions"));
    assertEquals("NOT_CHECKED",result.get("embeddingStatus")); verifyNoInteractions(embedding);
    var body=org.mockito.ArgumentCaptor.forClass(Object.class); verify(es).search(body.capture());
    assertEquals(json.valueToTree(queries.accessFilters()),json.valueToTree(body.getValue()).path("query").path("bool").path("filter"));
  }
  @Test void missingAliasDoesNotInventCountsOrCallEmbedding() {
    when(index.currentIndices()).thenReturn(List.of());
    var result=diagnostics.status(true);
    assertEquals("INDEX_EMPTY",result.get("indexStatus")); assertNull(result.get("publicChunkCount"));
    verifyNoInteractions(es,embedding);
  }
  @Test void existingButEmptyPublicScopeIsNotReportedReady() throws Exception {
    readyCounts("{\"hits\":{\"total\":{\"value\":0,\"relation\":\"eq\"}},\"aggregations\":{\"documents\":{\"value\":0}}}");
    var result=diagnostics.status(true);
    assertEquals("INDEX_EMPTY",result.get("indexStatus")); assertEquals(0L,result.get("publicChunkCount"));
  }
  @Test void probePreservesAuthAndDimensionFailures() {
    for(String code:List.of("ES_AUTH_FAILED","ES_UNAVAILABLE","DIMENSION_MISMATCH","EMBEDDING_MODEL_MISMATCH")) {
      doThrow(new RagException(code,"not exported")).when(index).currentIndices();
      var result=diagnostics.status(true);
      assertEquals("UNAVAILABLE",result.get("indexStatus")); assertEquals(code,result.get("failureReason"));
      assertNull(result.get("publicChunkCount"));
    }
  }
  @Test void malformedCountsCannotBecomeSuccessfulOrZero() throws Exception {
    for(String body:List.of("{}","{\"hits\":{\"total\":{\"value\":16,\"relation\":\"gte\"}}}",
        "{\"hits\":{\"total\":{\"value\":16,\"relation\":\"eq\"}},\"aggregations\":{\"documents\":{\"value\":-1}}}")) {
      readyCounts(body); var result=diagnostics.status(true);
      assertEquals("UNAVAILABLE",result.get("indexStatus")); assertEquals("ES_OUTPUT_INVALID",result.get("failureReason"));
      assertNull(result.get("publicChunkCount"));
    }
  }
  @Test void disabledPreviewMakesNoRetrievalOrEmbeddingRequests() {
    config.setEnabled(false); var result=diagnostics.preview(requirement());
    assertEquals("DISABLED",json.valueToTree(result).path("rag").path("status").asText());
    assertTrue(json.valueToTree(result).path("snippets").isEmpty()); verifyNoInteractions(es,index,embedding);
  }
  @Test void previewFiltersPrivateOwnerUnauthorizedAndReturnsOnlyBoundedPublicExcerpts() throws Exception {
    when(embedding.embed(anyString())).thenReturn(new float[]{1,0,0});
    when(es.search(any())).thenReturn(hits(chunk("public","PUBLIC",null,true,"园林慢游".repeat(140)),
        chunk("private","PRIVATE",42L,true,"PRIVATE_TASK_PAYLOAD"),chunk("owner","PUBLIC",42L,true,"OWNER_PAYLOAD"),
        chunk("denied","PUBLIC",null,false,"UNAUTHORIZED_PAYLOAD")));
    var result=json.valueToTree(diagnostics.preview(requirement()));
    assertEquals(1,result.path("rag").path("sources").size()); assertEquals(1,result.path("snippets").size());
    String excerpt=result.path("snippets").get(0).path("excerpt").asText();
    assertEquals(400,excerpt.codePointCount(0,excerpt.length()));
    assertTrue(result.path("snippets").get(0).path("excerptTruncated").asBoolean());
    assertFalse(result.path("chatModelCalled").asBoolean()); assertFalse(result.path("requirementsConfirmed").asBoolean());
    for(String hidden:List.of("PRIVATE_TASK_PAYLOAD","OWNER_PAYLOAD","UNAUTHORIZED_PAYLOAD","_ragContext","query_vector","_modelDiagnostics"))
      assertFalse(result.toString().contains(hidden),hidden);
    assertTrue(result.findValues("content").isEmpty()); assertTrue(result.findValues("embedding").isEmpty());
  }
  @Test void missingModelAndEsFailureAreHonestFallbacksOrStrictFailures() {
    when(embedding.embed(anyString())).thenThrow(new RagException("EMBEDDING_MODEL_MISSING","fixture"));
    when(es.search(any())).thenThrow(new RagException("ES_UNAVAILABLE","fixture"));
    var result=json.valueToTree(diagnostics.preview(requirement()));
    assertEquals("UNAVAILABLE",result.path("rag").path("status").asText());
    assertTrue(result.path("rag").path("fallbackReason").asText().contains("EMBEDDING_MODEL_MISSING"));
    assertTrue(result.path("snippets").isEmpty());
    config.setFallbackToOriginal(false);
    assertEquals(503,assertThrows(BusinessException.class,()->diagnostics.preview(requirement())).getCode());
  }
  @Test void noTrustworthySourceDoesNotExposeAnUnverifiedExcerpt() {
    when(embedding.embed(anyString())).thenReturn(new float[]{1,0,0});
    var good=chunk("bad-url","PUBLIC",null,true,"source requires verification");
    var fields=json.<Map<String,Object>>convertValue(good,json.getTypeFactory().constructMapType(HashMap.class,String.class,Object.class));
    fields.put("sourceUrl","javascript:untrusted");
    when(es.search(any())).thenReturn(hits(json.convertValue(fields,KnowledgeChunk.class)));
    var result=json.valueToTree(diagnostics.preview(requirement()));
    assertEquals("NO_RELIABLE_KNOWLEDGE",result.path("rag").path("status").asText());
    assertTrue(result.path("snippets").isEmpty());
  }
  @Test void previewsDoNotReachTaskQuotaDatabaseRedisOrChatBeans() {
    when(embedding.embed(anyString())).thenReturn(new float[]{1,0,0});
    when(es.search(any())).thenReturn(hits(chunk("public","PUBLIC",null,true,"园林慢游")));
    var task=mock(AiTaskService.class);var store=mock(AiTaskStore.class);var db=mock(JdbcTemplate.class);
    var redis=mock(StringRedisTemplate.class);var chat=mock(ChatClient.class);var provider=mock(AiProvider.class);
    try(var context=new AnnotationConfigApplicationContext()) {
      context.registerBean(RagProperties.class,()->config);context.registerBean(ElasticsearchKnowledgeClient.class,()->es);
      context.registerBean(KnowledgeIndexService.class,()->index);context.registerBean(TravelRetrievalQueryBuilder.class,()->queries);
      context.registerBean(ElasticsearchHybridRetriever.class,()->retrieval);context.registerBean(TravelRerankService.class,()->new TravelRerankService(config));
      context.registerBean(RagContextBuilder.class,()->new RagContextBuilder(config));context.registerBean(TravelRequirementService.class);
      context.registerBean(AiTaskService.class,()->task);context.registerBean(AiTaskStore.class,()->store);
      context.registerBean(JdbcTemplate.class,()->db);context.registerBean(StringRedisTemplate.class,()->redis);
      context.registerBean(ChatClient.class,()->chat);context.registerBean(AiProvider.class,()->provider);
      context.registerBean(TravelRagService.class);context.registerBean(RagDiagnosticsService.class);context.refresh();
      clearInvocations(task,store,db,redis,chat,provider);
      var result=context.getBean(RagDiagnosticsService.class).preview(requirement());
      assertEquals(true,result.get("previewOnly"));
      verifyNoInteractions(task,store,db,redis,chat,provider);
    }
  }
}

package com.smarttrip.rag;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.*;
import com.smarttrip.config.RagProperties;
import com.smarttrip.entity.TravelRequirement;
import com.smarttrip.rag.knowledge.*;
import java.nio.file.*;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RagValidityTests {
  private final ObjectMapper json = new ObjectMapper();
  private final RagProperties config = new RagProperties();
  @TempDir Path temporary;
  private TravelRetrievalQueryBuilder.Query query(String date, Integer days) {
    var values = new LinkedHashMap<String,Object>();
    values.put("destinations",List.of("大兴安岭")); values.put("timeDescription","秋季");
    values.put("startDate",date); values.put("days",days);
    return new TravelRetrievalQueryBuilder(config).build(json.convertValue(values,TravelRequirement.class));
  }
  private KnowledgeChunk chunk(boolean bounded) {
    return new KnowledgeChunk("annual","chunk","森林防火","林区防火规定。",List.of("大兴安岭"),List.of(),"SAFETY",List.of("AUTUMN"),
        "官方来源","https://example.org/annual",LocalDate.now().minusDays(1).toString(),"2026-10-28T00:00:00Z",
        "PUBLIC",null,"1","hash","bge-m3","v1",true,false,
        bounded?"2026-09-15":null,bounded?"2026-11-15":null);
  }
  private RetrievalHit hit(KnowledgeChunk chunk) { return new RetrievalHit(chunk,1,null,2.0,null,0,null); }
  @ParameterizedTest @CsvSource({"2026-09-15,true","2026-11-15,true","2026-11-20,false","2027-10-15,false"})
  void contextChecksInclusiveBoundariesAndFutureYear(String date,boolean expected) {
    var context = new RagContextBuilder(config).build(List.of(hit(chunk(true))),query(date,1));
    assertEquals(expected,!context.sources().isEmpty());
    if(expected) {
      var source=context.sources().get(0);
      assertEquals("2026-09-15",source.get("validFrom")); assertEquals("2026-11-15",source.get("validUntil"));
      assertEquals("APPLICABLE",source.get("applicability"));
      assertNotEquals(source.get("sourceUpdatedAt"),source.get("validFrom"));
      assertTrue(context.prompt().contains("\"validUntil\":\"2026-11-15\""));
    }
  }
  @Test void unknownDateOrDurationNeverAssumesAnAnnualRuleIsApplicable() {
    var builder=new RagContextBuilder(config);
    for(var q:List.of(query(null,8),query("2026-10-15",null),query("invalid",8))) {
      assertTrue(builder.build(List.of(hit(chunk(true))),q).sources().isEmpty());
      assertTrue(json.valueToTree(q.filters()).toString().contains("validFrom"));
      assertFalse(json.valueToTree(q.filters()).toString().contains("2026-09-15"));
    }
    assertTrue(builder.build(List.of(hit(chunk(true)))).sources().isEmpty());
  }
  @ParameterizedTest @CsvSource({"2026-09-13,5,2026-09-15,2026-09-17","2026-11-14,5,2026-11-14,2026-11-15"})
  void partialOverlapNamesOnlyTheApplicableTravelDays(String date,int days,String from,String until) {
    var q=query(date,days); var context=new RagContextBuilder(config).build(List.of(hit(chunk(true))),q);
    var source=context.sources().get(0);
    assertEquals("PARTIALLY_APPLICABLE",source.get("applicability"));
    assertEquals(from,source.get("applicableFrom")); assertEquals(until,source.get("applicableUntil"));
    assertTrue(source.get("applicabilityNote").toString().contains("其余日期不适用"));
    assertTrue(context.prompt().contains("PARTIALLY_APPLICABLE"));
    assertEquals(LocalDate.parse(date).plusDays(days-1),q.travelDates().until());
  }
  @Test void ordinaryKnowledgeRetainsItsOldBehaviorAndNullsDoNotChangeLegacyFingerprints() {
    for(var q:List.of(query(null,8),query("2027-10-15",8)))
      assertEquals("UNBOUNDED",new RagContextBuilder(config).build(List.of(hit(chunk(false))),q).sources().get(0).get("applicability"));
    assertFalse(json.valueToTree(chunk(false)).has("validFrom"));
  }
  @Test void esFilterUsesTheEntireTripAndBothBranchesUseTheSameFilter() {
    var es=mock(ElasticsearchKnowledgeClient.class); var embedding=mock(OllamaKnowledgeEmbedding.class);
    when(embedding.embed(anyString())).thenReturn(new float[]{1,2,3});
    when(es.search(any())).thenReturn(json.valueToTree(Map.of("hits",Map.of("hits",List.of(Map.of("_source",chunk(true)))))));
    var q=query("2026-11-14",5);
    try(varClose retriever=new varClose(new ElasticsearchHybridRetriever(es,embedding,config))) {
      assertEquals(1,retriever.value.retrieve(q,ElasticsearchHybridRetriever.Mode.HYBRID).hits().size());
    }
    var captor=org.mockito.ArgumentCaptor.forClass(Object.class); verify(es,times(2)).search(captor.capture());
    for(var body:captor.getAllValues()) {
      var node=json.valueToTree(body);
      var filters=node.has("knn")?node.path("knn").path("filter").path("bool").path("filter"):node.path("query").path("bool").path("filter");
      assertEquals(json.valueToTree(q.filters()),filters);
      assertTrue(filters.toString().contains("\"validFrom\":{\"lte\":\"2026-11-18\"}"));
      assertTrue(filters.toString().contains("\"validUntil\":{\"gte\":\"2026-11-14\"}"));
    }
  }
  private record varClose(ElasticsearchHybridRetriever value) implements AutoCloseable { public void close(){value.close();} }
  @Test void javaRejectsExpiredOrMalformedHitsEvenIfEsReturnsThem() {
    var es=mock(ElasticsearchKnowledgeClient.class); var embedding=mock(OllamaKnowledgeEmbedding.class);
    var invalid=json.valueToTree(chunk(true)); ((com.fasterxml.jackson.databind.node.ObjectNode)invalid).put("validUntil","2026-09-01");
    when(es.search(any())).thenReturn(json.valueToTree(Map.of("hits",Map.of("hits",List.of(
        Map.of("_source",chunk(true)),Map.of("_source",invalid),Map.of("_source",chunk(false)))))));
    try(varClose retriever=new varClose(new ElasticsearchHybridRetriever(es,embedding,config))) {
      for(var q:List.of(query("2027-10-15",8),query(null,8))) {
        var result=retriever.value.retrieve(q,ElasticsearchHybridRetriever.Mode.BM25);
        assertEquals(1,result.hits().size()); assertNull(result.hits().get(0).chunk().validFrom());
      }
    }
  }
  @Test void manifestRejectsMissingInvalidOrReversedDates() {
    var reader=new KnowledgeDocumentReader(config);
    String[][] dates={{"2026-09-15",null},{null,"2026-11-15"},{"2026-9-15","2026-11-15"},
        {"2026-02-30","2026-11-15"},{"2026-11-16","2026-11-15"},{"",""}};
    for(var pair:dates)assertEquals("DOCUMENT_VALIDITY_INVALID",assertThrows(RagException.class,
        ()->reader.validate(document(pair[0],pair[1]))).code());
    assertDoesNotThrow(()->reader.validate(document(null,null)));
  }
  private KnowledgeDocument document(String from,String until) {
    return new KnowledgeDocument("annual","森林防火","guide.md",List.of("大兴安岭"),List.of(),"SAFETY",List.of("AUTUMN"),
        "官方来源","https://example.org/annual",null,"PUBLIC",null,"1",true,false,from,until);
  }
  @Test void metadataOnlyManifestChangeUpdatesIndexWithoutChangingText() throws Exception {
    Files.writeString(temporary.resolve("guide.md"),"# 防火\n森林旅行应遵守官方防火规定。\n");
    var manifest=temporary.resolve("manifest.json"); var reader=new KnowledgeDocumentReader(config);
    Files.writeString(manifest,json.writeValueAsString(Map.of("documents",List.of(document(null,null)))));
    var old=reader.readManifest(manifest).get(0);
    Files.writeString(manifest,json.writeValueAsString(Map.of("documents",List.of(document("2026-09-15","2026-11-15")))));
    var updated=reader.readManifest(manifest).get(0);
    assertNotEquals(old.contentHash(),updated.contentHash());
    assertEquals(old.chunks().get(0).contentHash(),updated.chunks().get(0).contentHash());
    assertNotEquals(old.chunks().get(0).chunkId(),updated.chunks().get(0).chunkId());
    assertEquals("2026-11-15",updated.chunks().get(0).validUntil());
    var metadata=mock(KnowledgeMetadataService.class); var index=mock(KnowledgeIndexService.class);
    var client=mock(ElasticsearchKnowledgeClient.class); var embedding=mock(OllamaKnowledgeEmbedding.class);
    Connection connection=mock(Connection.class);
    when(client.indexName()).thenReturn("test"); when(index.currentIndices()).thenReturn(List.of("old"));
    when(metadata.beginRun(anyString(),anyString())).thenReturn("run");
    when(metadata.exclusively(anyString(),any(),any())).thenAnswer(call->((KnowledgeMetadataService.Work<?>)call.getArgument(1)).run(connection));
    when(metadata.unchanged(eq(connection),any(),any(),any())).thenAnswer(call->old.contentHash().equals(((KnowledgeDocumentReader.PreparedDocument)call.getArgument(1)).contentHash()));
    when(embedding.embed(anyString())).thenReturn(new float[]{1,2,3});
    when(embedding.model()).thenReturn("bge-m3"); when(embedding.version()).thenReturn("v1");
    when(index.createStaging(3)).thenReturn("new"); when(index.count("new")).thenReturn(updated.chunks().size());
    var service=new KnowledgeIngestionService(reader,metadata,index,client,embedding,config);
    assertEquals("SUCCESS",service.importManifest(manifest,false).status());
    verify(index).copyUnchanged(List.of("old"),"new",Set.of("annual"));
    verify(index).publish(List.of("old"),"new"); verify(index,never()).discard("old");
    verify(metadata).save(eq(connection),argThat(d->d.contentHash().equals(updated.contentHash()) && "2026-11-15".equals(d.document().validUntil())),eq("new"),eq("bge-m3"),eq("v1"));
  }
}

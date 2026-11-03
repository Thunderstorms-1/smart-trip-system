package com.smarttrip.rag;

import com.smarttrip.config.RagProperties;
import com.smarttrip.rag.knowledge.*;
import com.smarttrip.rag.knowledge.KnowledgeDocumentReader.PreparedDocument;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class RagKnowledgeLifecycleTests {
  KnowledgeDocumentReader reader=mock(KnowledgeDocumentReader.class);
  KnowledgeMetadataService metadata=mock(KnowledgeMetadataService.class);
  KnowledgeIndexService index=mock(KnowledgeIndexService.class);
  ElasticsearchKnowledgeClient client=mock(ElasticsearchKnowledgeClient.class);
  OllamaKnowledgeEmbedding embedding=mock(OllamaKnowledgeEmbedding.class);
  Connection connection=mock(Connection.class);
  RagProperties properties=new RagProperties();
  KnowledgeIngestionService service;
  PreparedDocument document;
  @BeforeEach void setup() throws Exception {
    service=new KnowledgeIngestionService(reader,metadata,index,client,embedding,properties);
    var source=new KnowledgeDocument("fixture","Title","fixture.md",List.of("苏州"),List.of("江苏"),"CULTURE",List.of("ALL"),
        "Source","https://example.org",null,"PUBLIC",null,"1",true,true);
    var chunk=new KnowledgeChunk("fixture","chunk-1","Title","Content",source.destinations(),source.regions(),source.category(),source.applicableSeasons(),
        source.sourceName(),source.sourceUrl(),null,"2026-10-28T00:00:00Z","PUBLIC",null,"1","hash","bge-m3","v1",true,true);
    document=new PreparedDocument(source,"document-hash",List.of(chunk));
    when(reader.readManifest(any())).thenReturn(List.of(document)); when(client.indexName()).thenReturn("alias");
    when(client.mappedDimensions()).thenReturn(3); when(metadata.beginRun(anyString(),anyString())).thenReturn("import-1");
    when(index.currentIndices()).thenReturn(List.of("old-index")); when(index.createStaging(3)).thenReturn("new-index");
    when(index.copyUnchanged(anyList(),eq("new-index"),anyCollection())).thenReturn(2); when(index.count("new-index")).thenReturn(3);
    when(embedding.model()).thenReturn("bge-m3"); when(embedding.version()).thenReturn("v1"); when(embedding.embed(anyString())).thenReturn(new float[]{1,2,3});
    when(metadata.exclusively(anyString(),any(),any())).thenAnswer(call->{
      try { return ((KnowledgeMetadataService.Work<?>)call.getArgument(1)).run(connection); }
      catch(Exception error) { ((Runnable)call.getArgument(2)).run(); throw error; }
    });
  }
  @Test void unchangedImportDoesNotEmbedOrCreateAnotherGeneration() throws Exception {
    when(metadata.unchanged(connection,document,"bge-m3","v1")).thenReturn(true); when(index.count("old-index")).thenReturn(3);
    var result=service.importManifest(Path.of("manifest.json"),false);
    assertEquals("UNCHANGED",result.status()); verify(embedding,never()).embed(anyString()); verify(index,never()).createStaging(anyInt());
    verify(index,never()).publish(anyList(),anyString());
  }
  @Test void documentUpdateExcludesOldChunksAndPublishesOnlyAfterCountVerification() {
    var result=service.importManifest(Path.of("manifest.json"),false);
    assertEquals("SUCCESS",result.status()); assertEquals(3,result.chunkCount());
    verify(index).copyUnchanged(eq(List.of("old-index")),eq("new-index"),eq(Set.of("fixture")));
    var order=inOrder(index); order.verify(index).putChunk(eq("new-index"),any(),any(),eq(3));
    order.verify(index).refresh("new-index"); order.verify(index).count("new-index"); order.verify(index).publish(List.of("old-index"),"new-index");
    verify(index,never()).discard("old-index");
  }
  @Test void failedChunkWritePreservesOldAliasAndDiscardsOnlyStaging() {
    doThrow(new RagException("INDEX_WRITE_FAILED","fixture")).when(index).putChunk(anyString(),any(),any(),anyInt());
    assertEquals("INDEX_WRITE_FAILED",assertThrows(RagException.class,()->service.importManifest(Path.of("m"),false)).code());
    verify(index,never()).publish(anyList(),anyString()); verify(index).discard("new-index"); verify(index,never()).discard("old-index");
    verify(metadata).failRun("import-1","INDEX_WRITE_FAILED");
  }
  @Test void ambiguousPublicationFailureRestoresOldAliasBeforeDiscardingStaging() {
    doThrow(new RagException("RETRIEVAL_TIMEOUT","fixture")).when(index).publish(anyList(),anyString());
    assertThrows(RagException.class,()->service.importManifest(Path.of("m"),false));
    var order=inOrder(index); order.verify(index).publish(List.of("old-index"),"new-index");
    order.verify(index).restore(List.of("old-index"),"new-index"); order.verify(index).discard("new-index");
  }
  @Test void failedRecoveryKeepsBothSnapshotsForOperatorReconciliation() {
    doThrow(new RagException("RETRIEVAL_TIMEOUT","fixture")).when(index).publish(anyList(),anyString());
    doThrow(new RagException("INDEX_RECOVERY_REQUIRED","fixture")).when(index).restore(anyList(),anyString());
    assertEquals("INDEX_RECOVERY_REQUIRED",assertThrows(RagException.class,()->service.importManifest(Path.of("m"),false)).code());
    verify(index,never()).discard(anyString()); verify(metadata).failRun("import-1","INDEX_RECOVERY_REQUIRED");
  }
  @Test void countMismatchNeverReplacesReadableOldIndex() {
    when(index.count("new-index")).thenReturn(2);
    assertEquals("INDEX_WRITE_FAILED",assertThrows(RagException.class,()->service.importManifest(Path.of("m"),false)).code());
    verify(index,never()).publish(anyList(),anyString());
  }
  @Test void modelRebuildDoesNotCopyOldModelVectors() throws Exception {
    when(index.count("new-index")).thenReturn(1);
    assertEquals("SUCCESS",service.importManifest(Path.of("m"),true).status());
    verify(client,never()).validateIndex(anyInt()); verify(index,never()).copyUnchanged(anyList(),anyString(),anyCollection());
    verify(metadata).markAllDeleted(connection); verify(index).publish(List.of("old-index"),"new-index");
  }
  @Test void deletionRemovesAllOldDocumentChunksWithoutCallingOllama() {
    when(index.count("new-index")).thenReturn(2);
    assertEquals("SUCCESS",service.deleteDocument("fixture").status());
    verify(index).copyUnchanged(List.of("old-index"),"new-index",Set.of("fixture"));
    verify(embedding,never()).embed(anyString()); verify(index).publish(List.of("old-index"),"new-index");
  }
  @Test void mappingUsesActualDimensionsAndStockCjkAnalyzer() {
    var actual=new KnowledgeIndexService(client,properties).mapping(7);
    var mappings=(Map<?,?>)actual.get("mappings"); var fields=(Map<?,?>)mappings.get("properties");
    assertEquals(7,((Map<?,?>)fields.get("embedding")).get("dims"));
    assertEquals("cjk",((Map<?,?>)fields.get("content")).get("analyzer"));
    assertEquals("strict",mappings.get("dynamic"));
  }
}
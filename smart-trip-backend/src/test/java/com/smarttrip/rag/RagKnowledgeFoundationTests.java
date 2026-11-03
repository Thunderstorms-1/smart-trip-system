package com.smarttrip.rag;

import com.fasterxml.jackson.databind.*;
import com.smarttrip.config.RagProperties;
import com.smarttrip.rag.knowledge.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RagKnowledgeFoundationTests {
  @TempDir Path temporary;
  private final ObjectMapper json=new ObjectMapper();
  private HttpServer server;
  @AfterEach void close() { if(server!=null) server.stop(0); }
  private RagProperties fixture(int status,String body,AtomicInteger calls,AtomicReference<String> request) throws Exception {
    server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/",exchange->{
      calls.incrementAndGet();
      request.set(exchange.getRequestURI()+"\n"+new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
      exchange.getResponseHeaders().add("Content-Type","application/json");
      byte[] bytes=body.getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(status,bytes.length);
      try(var output=exchange.getResponseBody()) { output.write(bytes); }
    }); server.start();
    RagProperties properties=new RagProperties();
    properties.setEmbeddingBaseUrl("http://127.0.0.1:"+server.getAddress().getPort());
    properties.setElasticsearchUrl(properties.getEmbeddingBaseUrl());
    return properties;
  }
  @Test void realSpringAiSdkPostsEmbeddingExactlyOnceWithoutPullingModels() throws Exception {
    AtomicInteger calls=new AtomicInteger(); AtomicReference<String> request=new AtomicReference<>();
    var properties=fixture(200,"{\"model\":\"bge-m3\",\"embeddings\":[[0.1,0.2,0.3]],\"prompt_eval_count\":3}",calls,request);
    float[] vector=new OllamaKnowledgeEmbedding(properties).embed("苏州园林");
    assertArrayEquals(new float[]{.1f,.2f,.3f},vector);
    assertEquals(1,calls.get()); assertTrue(request.get().startsWith("/api/embed\n"));
    JsonNode body=json.readTree(request.get().substring(request.get().indexOf('\n')+1));
    assertEquals("bge-m3",body.path("model").asText()); assertEquals("苏州园林",body.path("input").get(0).asText());
    assertFalse(body.path("truncate").asBoolean(true)); assertFalse(body.has("dimensions"));
  }
  @Test void missingOllamaModelProducesSanitizedCodeAndNoRetry() throws Exception {
    AtomicInteger calls=new AtomicInteger();
    var properties=fixture(404,"{\"error\":\"model missing secret-private-input\"}",calls,new AtomicReference<>());
    RagException failure=assertThrows(RagException.class,()->new OllamaKnowledgeEmbedding(properties).embed("private-query"));
    assertEquals("EMBEDDING_MODEL_MISSING",failure.code()); assertEquals(1,calls.get());
    assertFalse(failure.getMessage().contains("secret")); assertNull(failure.getCause());
  }
  @Test void embeddingRejectsZeroVectorInsteadOfAssumingSuccess() throws Exception {
    var properties=fixture(200,"{\"model\":\"bge-m3\",\"embeddings\":[[0,0,0]]}",new AtomicInteger(),new AtomicReference<>());
    assertEquals("EMBEDDING_OUTPUT_INVALID",assertThrows(RagException.class,()->new OllamaKnowledgeEmbedding(properties).embed("text")).code());
  }
  @Test void mappingRequiresMeasuredDimensionsAndExactModelVersion() throws Exception {
    String mapping="{\"physical\":{\"mappings\":{\"_meta\":{\"embeddingModel\":\"bge-m3\",\"embeddingVersion\":\"v1\",\"vectorDimensions\":3},\"properties\":{\"embedding\":{\"dims\":3}}}}}";
    var properties=fixture(200,mapping,new AtomicInteger(),new AtomicReference<>());
    var client=new ElasticsearchKnowledgeClient(properties);
    assertDoesNotThrow(()->client.validateIndex(3));
    assertEquals("DIMENSION_MISMATCH",assertThrows(RagException.class,()->client.validateIndex(4)).code());
    properties.setEmbeddingVersion("v2");
    assertEquals("EMBEDDING_MODEL_MISMATCH",assertThrows(RagException.class,()->client.validateIndex(3)).code());
  }
  @Test void elasticsearchAuthFailureNeverReturnsServerBodyOrCredentials() throws Exception {
    var properties=fixture(401,"{\"error\":\"secret-password\"}",new AtomicInteger(),new AtomicReference<>());
    properties.setElasticsearchUsername("elastic"); properties.setElasticsearchPassword("secret-password");
    RagException failure=assertThrows(RagException.class,()->new ElasticsearchKnowledgeClient(properties).search(Map.of("size",1)));
    assertEquals("ES_AUTH_FAILED",failure.code()); assertFalse(failure.getMessage().contains("secret-password"));
  }
  @Test void elasticsearchTimeoutIsBoundedAndNotRetried() throws Exception {
    AtomicInteger calls=new AtomicInteger();
    server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/",exchange->{ calls.incrementAndGet(); try { Thread.sleep(300); } catch(InterruptedException ignored) {}
      try { exchange.sendResponseHeaders(200,0); exchange.close(); } catch(Exception ignored) {} }); server.start();
    RagProperties properties=new RagProperties(); properties.setElasticsearchUrl("http://127.0.0.1:"+server.getAddress().getPort());
    properties.setRetrievalTimeoutMillis(100);
    RagException failure=assertThrows(RagException.class,()->new ElasticsearchKnowledgeClient(properties).search(Map.of()));
    assertEquals("RETRIEVAL_TIMEOUT",failure.code()); assertEquals(1,calls.get());
  }
  private KnowledgeDocument document(String path) {
    return new KnowledgeDocument("fixture","旅行参考",path,List.of("苏州"),List.of("江苏"),"CULTURE",List.of("ALL"),
        "项目授权摘记","https://example.org/travel",null,"PUBLIC",null,"1",true,true);
  }
  @Test void readerKeepsSectionsSeparateAndUsesDeterministicChunkIds() throws Exception {
    String markdown="# 交通\n"+"交通安排应预留换乘时间。".repeat(80)+"\n# 风险\n"+"应遵守景区安全提示。".repeat(80);
    Path file=temporary.resolve("guide.md"); Files.writeString(file,markdown);
    RagProperties properties=new RagProperties(); properties.setChunkTokens(96); properties.setChunkOverlapTokens(12);
    var reader=new KnowledgeDocumentReader(properties);
    var first=reader.prepare(document("guide.md"),file); var second=reader.prepare(document("guide.md"),file);
    assertTrue(first.chunks().size()>2); assertEquals(first.contentHash(),second.contentHash());
    assertEquals(first.chunks().stream().map(KnowledgeChunk::chunkId).toList(),second.chunks().stream().map(KnowledgeChunk::chunkId).toList());
    assertTrue(first.chunks().stream().noneMatch(c->c.content().contains("交通")&&c.content().contains("安全提示")));
    assertTrue(first.chunks().stream().anyMatch(c->c.title().contains("交通")));
    Files.writeString(file,markdown+"\n新增资料。");
    assertNotEquals(first.contentHash(),reader.prepare(document("guide.md"),file).contentHash());
  }
  @Test void privateOrUnauthorizedDocumentsAreRejectedBeforeReading() {
    var allowed=document("missing.md");
    var privateDoc=new KnowledgeDocument(allowed.documentId(),allowed.title(),allowed.path(),allowed.destinations(),allowed.regions(),allowed.category(),
        allowed.applicableSeasons(),allowed.sourceName(),allowed.sourceUrl(),null,"PRIVATE",7L,"1",true,true);
    assertEquals("DOCUMENT_ACCESS_DENIED",assertThrows(RagException.class,()->new KnowledgeDocumentReader(new RagProperties()).prepare(privateDoc,temporary.resolve("missing.md"))).code());
  }
  @Test void manifestCannotReadOutsideItsDirectory() throws Exception {
    Path nested=Files.createDirectory(temporary.resolve("nested")); Files.writeString(temporary.resolve("outside.md"),"# unrelated\ncontent");
    Path manifest=nested.resolve("manifest.json"); Files.writeString(manifest,json.writeValueAsString(Map.of("documents",List.of(document("../outside.md")))));
    assertEquals("DOCUMENT_PATH_DENIED",assertThrows(RagException.class,()->new KnowledgeDocumentReader(new RagProperties()).readManifest(manifest)).code());
  }
  @Test void realCuratedCorpusParsesAndHasNoPrivateOrUnattributedChunks() throws Exception {
    Path manifest=Path.of("").toRealPath().getParent().resolve("knowledge/travel-rag/manifest.json");
    var documents=new KnowledgeDocumentReader(new RagProperties()).readManifest(manifest);
    int chunks=documents.stream().mapToInt(d->d.chunks().size()).sum();
    assertEquals(8,documents.size()); assertTrue(chunks>=8);
    for(var document:documents) for(var chunk:document.chunks()) {
      assertTrue(chunk.authorized()); assertEquals("PUBLIC",chunk.visibility()); assertNull(chunk.ownerId());
      assertTrue(chunk.sourceUrl().startsWith("https://")); assertFalse(chunk.sourceName().isBlank());
    }
    System.out.println("RAG_CORPUS documents="+documents.size()+" chunks="+chunks);
    documents.forEach(d->System.out.println("RAG_CORPUS "+d.document().documentId()+" chunks="+d.chunks().size()));
  }
}
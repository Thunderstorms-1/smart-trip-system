package com.smarttrip;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.smarttrip.common.BusinessException;
import com.smarttrip.config.*;
import com.smarttrip.entity.TravelRequirement;
import com.smarttrip.rag.*;
import com.smarttrip.rag.knowledge.*;
import com.smarttrip.service.*;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** Opt-in real HTTP/MySQL/isolated Redis/BGE-M3/formal read-only ES; only the ChatModel is fixed. */
@EnabledIfEnvironmentVariable(named="SMARTTRIP_RAG_CLOSURE_LIVE", matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
    "server.address=127.0.0.1", "spring.flyway.enabled=false",
    "spring.data.redis.host=${SMARTTRIP_TEST_REDIS_HOST:127.0.0.1}",
    "spring.data.redis.port=${SMARTTRIP_TEST_REDIS_PORT}", "spring.data.redis.database=14",
    "security.jwt-secret=isolated-rag-http-fixture-secret-only-at-least-32-bytes",
    "security.auth-limits.register-ip-limit=1000", "security.auth-limits.login-ip-limit=1000",
    "ai.llm.enabled=false", "ai.llm.free-quota-verified=false", "ai.llm.api-key=",
    "ai.llm.base-url=http://127.0.0.1:1", "geo.enabled=false",
    "smarttrip.rag.enabled=true", "smarttrip.rag.preview-only=true", "smarttrip.rag.fallback-to-original=false",
    "smarttrip.rag.rerank-enabled=false", "smarttrip.rag.admin-enabled=false", "smarttrip.rag.evaluate=false",
    "mybatis.configuration.log-impl=org.apache.ibatis.logging.nologging.NoLoggingImpl"
})
@Import(AiRagHttpClosureIntegrationTests.RealTestWiring.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class AiRagHttpClosureIntegrationTests {
  static final ObjectMapper JSON=new ObjectMapper();
  static final String RUN=UUID.randomUUID().toString().replace("-","");
  static final String USER_PREFIX="rag_e2e_"+RUN.substring(0,8)+"_";
  static final Map<String,TravelRagContext> RETRIEVED=new ConcurrentHashMap<>();
  static final Map<String,Capture> CAPTURES=new ConcurrentHashMap<>();
  static final Map<String,AtomicInteger> MODEL_CALLS=new ConcurrentHashMap<>();
  static final List<Map<String,Object>> EVIDENCE=new CopyOnWriteArrayList<>();
  static final AtomicInteger INTERPRET_CALLS=new AtomicInteger();
  static String required(String name){String value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalStateException("Missing explicit test setting: "+name);return value;}
  record Capture(String promptHash,String contextHash,int promptCharacters,int contextCharacters,List<String> sourceIds,boolean persistedBeforeModel) {}
  record Account(long id,String token){@Override public String toString(){return "Account[redacted]";}}
  record Response(int status,JsonNode body){JsonNode data(){return body.path("data");}}

  static class GuardedDataSource extends AbstractDataSource {
    final String url=required("SMARTTRIP_TEST_DB_URL");
    GuardedDataSource(){if(!url.matches("jdbc:mysql://127\\.0\\.0\\.1:3306/smart_trip_test(?:\\?.*)?"))throw new IllegalStateException("Refusing non-test database");}
    @Override public Connection getConnection() throws SQLException {
      Connection connection=DriverManager.getConnection(url,required("SMARTTRIP_TEST_DB_USER"),System.getenv().getOrDefault("SMARTTRIP_TEST_DB_PASSWORD",""));
      if(!"smart_trip_test".equals(connection.getCatalog())){connection.close();throw new SQLException("Refusing non-test catalog");}
      return connection;
    }
    @Override public Connection getConnection(String user,String password)throws SQLException{throw new SQLFeatureNotSupportedException("Only controlled test credentials");}
  }
  static class ReadOnlyKnowledge extends ElasticsearchKnowledgeClient {
    final Map<String,JsonNode> actualChunks=new ConcurrentHashMap<>();
    final AtomicInteger requests=new AtomicInteger();
    ReadOnlyKnowledge(RagProperties p){super(p);}
    @Override public JsonNode request(String method,String path,Object body,int timeout){
      String alias="/smarttrip-travel-v1";
      boolean allowed="GET".equals(method)&&Set.of("/", "/_alias/smarttrip-travel-v1",alias+"/_mapping",alias+"/_count").contains(path)
          ||"POST".equals(method)&&path.equals(alias+"/_search");
      if(!allowed)throw new IllegalStateException("Formal knowledge write blocked by acceptance guard");
      requests.incrementAndGet();return super.request(method,path,body,timeout);
    }
    @Override public JsonNode search(Object body){
      JsonNode result=super.search(body);
      for(var hit:result.path("hits").path("hits")){
        var source=hit.path("_source");
        if(source.hasNonNull("chunkId"))actualChunks.put(source.path("chunkId").asText(),source.deepCopy());
      }
      return result;
    }
  }
  static class RealEmbedding extends OllamaKnowledgeEmbedding {
    final AtomicInteger calls=new AtomicInteger();
    RealEmbedding(RagProperties p){super(p);}
    @Override public float[] embed(String text){calls.incrementAndGet();return super.embed(text);}
  }
  static class RecordingRetrieval extends TravelRagService {
    final AtomicInteger calls=new AtomicInteger();
    RecordingRetrieval(RagProperties p,TravelRetrievalQueryBuilder q,ElasticsearchHybridRetriever r,
        TravelRerankService rank,RagContextBuilder c,ObjectProvider<MeterRegistry> m){super(p,q,r,rank,c,m);}
    @Override public TravelRagContext retrieve(TravelRequirement requirement,String taskId){
      calls.incrementAndGet();var result=super.retrieve(requirement,taskId);RETRIEVED.put(taskId,result);return result;
    }
  }
  static class FixedProvider extends AiProvider {
    final JdbcTemplate db;final ReadOnlyKnowledge knowledge;
    FixedProvider(JdbcTemplate db,ReadOnlyKnowledge knowledge){super(new AiProperties());this.db=db;this.knowledge=knowledge;}
    @Override public boolean available(){return true;}
    @Override public TravelRequirement interpret(String description){INTERPRET_CALLS.incrementAndGet();throw new BusinessException(400,"This fixture does not call semantic interpretation");}
    @Override public String complete(String input){
      String id=MDC.get("taskId");
      if(id==null)throw new IllegalStateException("Missing actual task context");
      MODEL_CALLS.computeIfAbsent(id,key->new AtomicInteger()).incrementAndGet();
      var real=RETRIEVED.get(id);
      try {
        if(real==null||real.promptContext().isBlank()||!input.endsWith("\n\n"+real.promptContext()))
          throw new IllegalStateException("Actual retrieval context missing from model input");
        var persisted=JSON.readTree(db.queryForObject("SELECT proposal_meta_json FROM ai_task WHERE id=?",String.class,id));
        if(!real.promptContext().equals(persisted.path("_ragContext").asText())
            ||!JSON.valueToTree(real.snapshot()).path("sources").equals(persisted.path("rag").path("sources")))
          throw new IllegalStateException("Actual context was not persisted before model invocation");
        var payload=JSON.readTree(real.promptContext().substring(RagContextBuilder.RULES.length()+1));
        if(payload.path("sources").isEmpty())throw new IllegalStateException("No real selected knowledge");
        var ids=new ArrayList<String>();
        for(var source:payload.path("sources")){
          String chunkId=source.path("chunkId").asText();var actual=knowledge.actualChunks.get(chunkId);
          if(actual==null||!actual.path("content").equals(source.path("content"))
              ||!actual.path("documentId").equals(source.path("documentId")))
            throw new IllegalStateException("Prompt content differs from real ES hit");
          ids.add(chunkId);
        }
        CAPTURES.put(id,new Capture(sha(input),sha(real.promptContext()),input.length(),real.promptContext().length(),List.copyOf(ids),true));
      }catch(Exception e){throw new BusinessException(502,"RAG acceptance context assertion failed: "+e.getClass().getSimpleName());}
      if(input.contains("RAG_HTTP_FAILURE"))throw new BusinessException(502,"固定模型供应商失败夹具");
      if(input.contains("RAG_HTTP_EMPTY"))return AiGenerationClosureIntegrationTests.EMPTY_PLAN_RESPONSE;
      return AiGenerationClosureIntegrationTests.eightDayDraft().toString();
    }
  }
  @TestConfiguration
  static class RealTestWiring {
    @Bean @Primary DataSource guardedRagClosureDataSource(){return new GuardedDataSource();}
    @Bean @Primary ReadOnlyKnowledge guardedFormalKnowledge(RagProperties p){return new ReadOnlyKnowledge(p);}
    @Bean @Primary RealEmbedding countedLocalEmbedding(RagProperties p){return new RealEmbedding(p);}
    @Bean @Primary RecordingRetrieval recordedRealRag(RagProperties p,TravelRetrievalQueryBuilder q,
        ElasticsearchHybridRetriever r,TravelRerankService rank,RagContextBuilder c,ObjectProvider<MeterRegistry> m){return new RecordingRetrieval(p,q,r,rank,c,m);}
    @Bean @Primary FixedProvider fixedRagClosureProvider(JdbcTemplate db,ReadOnlyKnowledge knowledge){return new FixedProvider(db,knowledge);}
  }

  @LocalServerPort int port;
  @Autowired JdbcTemplate db;
  @Autowired RecordingRetrieval retrieval;
  @Autowired RealEmbedding embedding;
  @Autowired ReadOnlyKnowledge knowledge;
  @Autowired ApplicationContext app;
  @Autowired AiProperties ai;
  @Autowired PlatformTransactionManager transactionManager;
  final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  final AtomicInteger accounts=new AtomicInteger();
  final List<Long> ownedAccounts=new CopyOnWriteArrayList<>();
  final Map<String,Long> initialCounts=new TreeMap<>();

  @BeforeAll void verifyBoundaries(){
    assertEquals("smart_trip_test",db.queryForObject("SELECT DATABASE()",String.class));
    assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM sys_user WHERE LEFT(username,?)=?",Integer.class,USER_PREFIX.length(),USER_PREFIX));
    assertFalse(ai.isEnabled());assertFalse(ai.isFreeQuotaVerified());
    for(String table:List.of("sys_user","ai_task","trip_plan","trip_day","trip_item","trip_member","trip_revision","trip_expense","trip_packing_item","trip_share","trip_invite","trip_interaction"))
      initialCounts.put(table,db.queryForObject("SELECT COUNT(*) FROM "+table,Long.class));
    assertEquals(401,unchecked(null,"GET","/api/rag/status?probe=true",null).status());
  }
  static String sha(String text){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
  Account account()throws Exception{
    String name=USER_PREFIX+accounts.incrementAndGet(),password="RagFixture!"+UUID.randomUUID();
    long user=ok(call(null,"POST","/api/auth/register",Map.of("username",name,"password",password,"nickname","隔离RAG验收"))).asLong();
    assertTrue(user>0);ownedAccounts.add(user);
    var login=ok(call(null,"POST","/api/auth/login",Map.of("username",name,"password",password)));
    assertFalse(login.path("token").asText().isBlank());return new Account(user,login.path("token").asText());
  }
  ObjectNode requirement(String marker){
    var value=JSON.createObjectNode().put("description","苏州出发，稻城亚丁8天7人，人均3000至4500元，轻松旅行。")
        .put("origin","苏州").put("days",8).put("startDate","2027-10-10").put("includesTravelDays",true)
        .put("travelers",7).put("budget",3000).put("budgetMax",4500).put("budgetScope","PER_PERSON").put("currency","CNY")
        .put("pace","RELAXED").put("notes",marker);
    value.putArray("destinations").add("稻城亚丁");value.putArray("interests").add("自然风光").add("摄影");return value;
  }
  ObjectNode submission(String key,JsonNode requirement){var v=JSON.createObjectNode().put("requestKey",key).put("type","GENERATE").put("confirmed",true);v.set("requirement",requirement);return v;}
  ObjectNode edit(JsonNode draft,long revision){var v=JSON.createObjectNode().put("draftRevision",revision);v.set("draft",draft);return v;}
  JsonNode confirm(Account a,String marker)throws Exception{
    var review=JSON.createObjectNode().put("extract",false);review.set("requirement",requirement(marker));
    var result=ok(call(a,"POST","/api/ai/tasks/requirements",review));
    assertTrue(result.path("unresolved").isEmpty(),"Confirmed fixture requirements must be complete");
    assertEquals("CNY",result.path("requirement").path("currency").asText());return result.path("requirement");
  }
  JsonNode await(Account a,String id)throws Exception{
    long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(110);
    while(System.nanoTime()<deadline){var value=ok(call(a,"GET","/api/ai/tasks/"+id,null));
      if(!Set.of("QUEUED","RUNNING").contains(value.path("status").asText()))return value;Thread.sleep(100);}
    throw new AssertionError("Actual task did not terminate before bounded timeout");
  }
  Response call(Account a,String method,String path,Object body)throws Exception{
    var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(20));
    if(a!=null)request.header("Authorization","Bearer "+a.token());
    if(body==null)request.method(method,HttpRequest.BodyPublishers.noBody());
    else request.header("Content-Type","application/json").method(method,HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
    var response=http.send(request.build(),HttpResponse.BodyHandlers.ofString());return new Response(response.statusCode(),JSON.readTree(response.body()));
  }
  Response unchecked(Account a,String method,String path,Object body){try{return call(a,method,path,body);}catch(Exception e){throw new IllegalStateException("Local HTTP failed");}}
  JsonNode ok(Response response){assertEquals(200,response.status(),"Real local HTTP request failed");assertEquals(200,response.body().path("code").asInt());return response.data();}
  int quota(Account a){return db.queryForObject("SELECT ai_quota_used FROM sys_user WHERE id=?",Integer.class,a.id());}
  int trips(Account a){return db.queryForObject("SELECT COUNT(*) FROM trip_plan WHERE user_id=?",Integer.class,a.id());}
  JsonNode metadata(Account a,String id)throws Exception{return JSON.readTree(db.queryForObject("SELECT proposal_meta_json FROM ai_task WHERE id=? AND user_id=?",String.class,id,a.id()));}
  void assertEvidence(Account a,String id,JsonNode task)throws Exception{
    var context=RETRIEVED.get(id);var capture=CAPTURES.get(id);assertNotNull(context);assertNotNull(capture);
    assertTrue(capture.persistedBeforeModel());assertEquals(sha(context.promptContext()),capture.contextHash());
    var sources=JSON.valueToTree(context.snapshot()).path("sources");assertFalse(sources.isEmpty());
    assertEquals(sources,task.path("generation").path("rag").path("sources"));
    var persisted=metadata(a,id);assertEquals(sources,persisted.path("rag").path("sources"));
    assertEquals(context.promptContext(),persisted.path("_ragContext").asText());
    assertFalse(task.toString().contains("_ragContext"));assertFalse(task.toString().contains("responseText"));
    assertFalse(task.toString().contains("query_vector"));
    for(var source:sources){var actual=knowledge.actualChunks.get(source.path("chunkId").asText());assertNotNull(actual);
      assertEquals("PUBLIC",actual.path("visibility").asText());assertTrue(actual.path("authorized").asBoolean());assertTrue(actual.path("ownerId").isNull()||actual.path("ownerId").isMissingNode());}
    assertEquals(1,MODEL_CALLS.get(id).get());
  }
  void evidence(String test,String id,Map<String,Object> extra){
    var capture=CAPTURES.get(id);var data=new LinkedHashMap<String,Object>();data.put("scenario",test);data.put("taskId",id);
    data.put("promptSha256",capture.promptHash());data.put("contextSha256",capture.contextHash());
    data.put("promptCharacters",capture.promptCharacters());data.put("contextCharacters",capture.contextCharacters());
    data.put("selectedChunkIds",capture.sourceIds());data.put("contextPersistedBeforeModel",capture.persistedBeforeModel());data.put("modelCalls",MODEL_CALLS.get(id).get());
    data.putAll(extra);EVIDENCE.add(data);
  }

  @Test void realRetrievalAndFixedGenerationEditConcurrentSaveAndWorkbench()throws Exception{
    var owner=account();var stranger=account();var confirmed=confirm(owner,"RAG_HTTP_SUCCESS");
    var request=submission(UUID.randomUUID().toString(),confirmed);
    String id=ok(call(owner,"POST","/api/ai/tasks",request)).path("id").asText();
    var preview=await(owner,id);assertEquals("PREVIEW",preview.path("status").asText());
    AiGenerationClosureIntegrationTests.assertEightDayTrip(preview.path("result"));assertEvidence(owner,id,preview);assertEquals(0,trips(owner));
    int retrievalCount=retrieval.calls.get(),embeddingCount=embedding.calls.get();
    assertEquals(id,ok(call(owner,"POST","/api/ai/tasks",request)).path("id").asText());
    var restored=ok(call(owner,"GET","/api/ai/tasks/"+id,null));assertEquals(preview.path("result"),restored.path("result"));
    assertEquals("PREVIEW",ok(call(owner,"POST","/api/ai/tasks/"+id+"/recover",null)).path("status").asText());
    for(String verb:List.of("GET","PUT","POST")){
      String path="/api/ai/tasks/"+id+(verb.equals("PUT")?"/draft":verb.equals("POST")?"/apply":"");
      Object body=verb.equals("PUT")?edit(preview.path("result"),0):verb.equals("POST")?Map.of("draftRevision",0):null;
      assertEquals(404,call(stranger,verb,path,body).status());
    }
    assertEquals(404,call(stranger,"POST","/api/ai/tasks/"+id+"/recover",null).status());
    assertEquals(409,call(owner,"PUT","/api/ai/tasks/"+id+"/draft",edit(preview.path("result"),99)).status());
    ObjectNode draft=preview.path("result").deepCopy();draft.put("title","真实RAG固定响应·人工编辑");
    ((ObjectNode)draft.path("days").get(0).path("items").get(0)).put("costEstimate",900);
    var changed=ok(call(owner,"PUT","/api/ai/tasks/"+id+"/draft",edit(draft,0)));
    assertEquals(1,changed.path("generation").path("draftRevision").asInt());assertFalse(changed.path("generation").path("saveBlocked").asBoolean());
    assertEquals(0,new BigDecimal("28100").compareTo(changed.path("result").path("totalBudget").decimalValue()));
    assertEquals(409,call(owner,"PUT","/api/ai/tasks/"+id+"/draft",edit(draft,0)).status());
    assertEquals(409,call(owner,"POST","/api/ai/tasks/"+id+"/apply",Map.of("draftRevision",0)).status());
    var gate=new CountDownLatch(1);var workers=Executors.newFixedThreadPool(2);JsonNode one,two;
    try{var first=workers.submit(()->{gate.await();return ok(call(owner,"POST","/api/ai/tasks/"+id+"/apply",Map.of("draftRevision",1,"title","已确认RAG八天行程")));});
      var second=workers.submit(()->{gate.await();return ok(call(owner,"POST","/api/ai/tasks/"+id+"/apply",Map.of("draftRevision",1,"title","已确认RAG八天行程")));});
      gate.countDown();one=first.get(25,TimeUnit.SECONDS);two=second.get(25,TimeUnit.SECONDS);
    }finally{workers.shutdownNow();}
    long trip=one.path("id").asLong();assertTrue(trip>0);assertEquals(trip,two.path("id").asLong());assertEquals(1,trips(owner));
    var workbench=ok(call(owner,"GET","/api/workbench/"+trip,null));assertEquals(one.path("days"),workbench.path("days"));
    assertEquals(8,workbench.path("days").size());assertEquals(24,AiGenerationClosureIntegrationTests.itemCount(workbench));
    assertEquals(0,new BigDecimal("28100").compareTo(workbench.path("totalBudget").decimalValue()));
    assertTrue(Set.of(403,404).contains(call(stranger,"GET","/api/workbench/"+trip,null).status()));
    ObjectNode further=workbench.deepCopy();further.put("title","工作台继续编辑后重读");
    var savedAgain=ok(call(owner,"PUT","/api/workbench/"+trip,further));
    assertTrue(savedAgain.path("version").asLong()>workbench.path("version").asLong());
    assertEquals(409,call(owner,"PUT","/api/workbench/"+trip,further).status());
    assertEquals(savedAgain.path("days"),ok(call(owner,"GET","/api/workbench/"+trip,null)).path("days"));
    var applied=ok(call(owner,"GET","/api/ai/tasks/"+id,null));assertEquals("APPLIED",applied.path("status").asText());assertEvidence(owner,id,applied);
    assertEquals("APPLIED",ok(call(owner,"POST","/api/ai/tasks/"+id+"/recover",null)).path("status").asText());
    assertEquals(trip,ok(call(owner,"POST","/api/ai/tasks/"+id+"/apply",Map.of("draftRevision",1))).path("id").asLong());
    assertEquals(id,ok(call(owner,"POST","/api/ai/tasks",request)).path("id").asText());
    assertEquals(retrievalCount,retrieval.calls.get());assertEquals(embeddingCount,embedding.calls.get());assertEquals(1,quota(owner));
    evidence("success-edit-concurrent-apply-workbench",id,Map.of("days",8,"activities",24,"estimatedTotalCny",28100,"oneFormalTrip",true,
      "ownershipDenied",true,"staleDraftAndTripVersionsRejected",true,"noRepeatedRetrievalOrModel",true,"quotaUsed",quota(owner)));
  }
  @Test void actualRetrievalThenProviderFailureRefundAndSameKeyRetry()throws Exception{
    var owner=account();var confirmed=confirm(owner,"RAG_HTTP_FAILURE");var request=submission(UUID.randomUUID().toString(),confirmed);
    String id=ok(call(owner,"POST","/api/ai/tasks",request)).path("id").asText();var failed=await(owner,id);
    assertEquals("FAILED",failed.path("status").asText());assertEvidence(owner,id,failed);assertEquals(0,quota(owner));assertEquals(0,trips(owner));
    int retrievalCount=retrieval.calls.get(),embeddingCount=embedding.calls.get();
    assertEquals(id,ok(call(owner,"POST","/api/ai/tasks",request)).path("id").asText());
    assertEquals(422,call(owner,"POST","/api/ai/tasks/"+id+"/recover",null).status());
    assertEquals(1,MODEL_CALLS.get(id).get());assertEquals(retrievalCount,retrieval.calls.get());assertEquals(embeddingCount,embedding.calls.get());
    assertEquals(0,db.queryForObject("SELECT quota_reserved FROM ai_task WHERE id=?",Integer.class,id));
    evidence("provider-failure-refund-and-idempotent-retry",id,Map.of("quotaUsed",0,"formalTrips",0,"recoverWithoutUsableResponse",422,"refundConfirmed",true));
  }
  @Test void recordedResponseRecoveryKeepsActualKnowledgeAndUsesNoSecondModel()throws Exception{
    var owner=account();var request=submission(UUID.randomUUID().toString(),confirm(owner,"RAG_HTTP_EMPTY"));
    String id=ok(call(owner,"POST","/api/ai/tasks",request)).path("id").asText();var failed=await(owner,id);
    assertEquals("FAILED",failed.path("status").asText());assertEquals(0,quota(owner));assertEvidence(owner,id,failed);
    ObjectNode meta=(ObjectNode)metadata(owner,id);JsonNode originalSources=meta.path("rag").path("sources").deepCopy();String originalContext=meta.path("_ragContext").asText();
    // Historical-failure fixture ONLY in this run's isolated task: keep its actual RAG snapshot,
    // replace the fixed empty-plan recording with the existing complete fixed model response.
    ObjectNode diagnostic=(ObjectNode)meta.path("_modelDiagnostics");
    diagnostic.put("responseText",AiGenerationClosureIntegrationTests.eightDayDraft().toString()).put("finishReason","STOP");
    assertEquals(1,db.update("UPDATE ai_task SET proposal_meta_json=? WHERE id=? AND user_id=? AND status='FAILED'",meta.toString(),id,owner.id()));
    int retrievalCount=retrieval.calls.get(),embeddingCount=embedding.calls.get();
    var restored=ok(call(owner,"POST","/api/ai/tasks/"+id+"/recover",null));assertEquals("PREVIEW",restored.path("status").asText());
    AiGenerationClosureIntegrationTests.assertEightDayTrip(restored.path("result"));assertEvidence(owner,id,restored);
    assertEquals(originalSources,metadata(owner,id).path("rag").path("sources"));assertEquals(originalContext,metadata(owner,id).path("_ragContext").asText());
    assertEquals(restored.path("result"),ok(call(owner,"POST","/api/ai/tasks/"+id+"/recover",null)).path("result"));
    var draft=restored.path("result").deepCopy();((ObjectNode)draft).put("title","恢复的真实RAG固定草稿");
    var changed=ok(call(owner,"PUT","/api/ai/tasks/"+id+"/draft",edit(draft,0)));assertEquals(1,changed.path("generation").path("draftRevision").asInt());
    var applied=ok(call(owner,"POST","/api/ai/tasks/"+id+"/apply",Map.of("draftRevision",1)));
    var workbench=ok(call(owner,"GET","/api/workbench/"+applied.path("id").asLong(),null));assertEquals(applied.path("days"),workbench.path("days"));
    assertEquals(retrievalCount,retrieval.calls.get());assertEquals(embeddingCount,embedding.calls.get());assertEquals(1,MODEL_CALLS.get(id).get());assertEquals(0,quota(owner));
    evidence("isolated-historical-fixed-response-recovery",id,Map.of("responseFixtureSeededOnlyInOwnedTask",true,"days",8,"activities",24,"ragSnapshotUnchanged",true,"noSecondModelOrRetrieval",true,"quotaUnchangedAfterRefund",true));
  }

  @AfterAll void cleanupOnlyThisRun()throws Exception{
    var ids=db.queryForList("SELECT id FROM sys_user WHERE LEFT(username,?)=?",Long.class,USER_PREFIX.length(),USER_PREFIX);
    for(Long id:ids){
      long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
      while(db.queryForObject("SELECT COUNT(*) FROM ai_task WHERE user_id=? AND status IN ('QUEUED','RUNNING')",Integer.class,id)>0&&System.nanoTime()<deadline)Thread.sleep(50);
      assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM ai_task WHERE user_id=? AND status IN ('QUEUED','RUNNING')",Integer.class,id));
    }
    new TransactionTemplate(transactionManager).executeWithoutResult(status->{
      for(Long user:ids){
        var trips=db.queryForList("SELECT id FROM trip_plan WHERE user_id=?",Long.class,user);
        db.update("DELETE FROM ai_task WHERE user_id=?",user);
        for(Long trip:trips){
          db.update("DELETE FROM trip_item WHERE day_id IN (SELECT id FROM trip_day WHERE trip_id=?)",trip);
          for(String table:List.of("trip_revision","trip_expense","trip_packing_item","trip_member","trip_invite","trip_share","trip_interaction","trip_item","trip_day"))
            db.update("DELETE FROM "+table+" WHERE trip_id=?",trip);
          db.update("DELETE FROM trip_plan WHERE id=? AND user_id=?",trip,user);
        }
        db.update("DELETE FROM sys_user WHERE id=? AND LEFT(username,?)=?",user,USER_PREFIX.length(),USER_PREFIX);
      }
    });
    for(var entry:initialCounts.entrySet())assertEquals(entry.getValue(),db.queryForObject("SELECT COUNT(*) FROM "+entry.getKey(),Long.class),"Isolated test rows were not fully cleaned: "+entry.getKey());
    assertEquals(0,INTERPRET_CALLS.get());
    for(var provider:app.getBeansOfType(AiProvider.class).values())assertNull(ReflectionTestUtils.getField(provider,"chatClient"),"Paid ChatClient must not be initialized");
    var report=new LinkedHashMap<String,Object>();report.put("database","smart_trip_test");report.put("scenarios",EVIDENCE);
    report.put("realRetrievalCalls",retrieval.calls.get());report.put("realEmbeddingCalls",embedding.calls.get());report.put("readOnlyEsRequests",knowledge.requests.get());
    report.put("fixedProviderCalls",MODEL_CALLS.values().stream().mapToInt(AtomicInteger::get).sum());report.put("paidChatModelCalls",0);
    report.put("temporaryAccountsRemoved",ids.size());report.put("testBusinessRowCountsRestored",true);report.put("initialCounts",initialCounts);
    Path output=Path.of(System.getProperty("smarttrip.build-dir","target")).toAbsolutePath().resolve("rag-http-closure-evidence.json");
    Files.createDirectories(output.getParent());Files.writeString(output,JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));
    System.out.println("RAG_HTTP_ACCEPTANCE_EVIDENCE="+output);
  }
}

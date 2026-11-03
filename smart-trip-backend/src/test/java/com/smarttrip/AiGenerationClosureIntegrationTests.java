package com.smarttrip;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.smarttrip.common.BusinessException;
import com.smarttrip.config.AiProperties;
import com.smarttrip.entity.*;
import com.smarttrip.service.*;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Fixed model responses, real HTTP/controllers/MySQL/Redis. Never calls a supplier or deletes data. */
@EnabledIfEnvironmentVariable(named = "SMARTTRIP_INTEGRATION", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
  "spring.datasource.url=${SMARTTRIP_TEST_DB_URL:jdbc:mysql://localhost:3306/smart_trip_test?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai}",
  "spring.data.redis.host=${SMARTTRIP_TEST_REDIS_HOST:localhost}",
  "spring.data.redis.port=${SMARTTRIP_TEST_REDIS_PORT:6379}",
  "spring.data.redis.database=15",
  "security.jwt-secret=closure-test-only-jwt-secret-at-least-32-bytes",
  "security.auth-limits.login-ip-limit=1000", "ai.llm.enabled=false", "geo.enabled=false"
})
@Import(AiGenerationClosureIntegrationTests.FixedModel.class)
class AiGenerationClosureIntegrationTests {
  static final ObjectMapper JSON = new ObjectMapper();
  static final AtomicInteger interpretations = new AtomicInteger(), generations = new AtomicInteger();
  static final String IDEA = "从上海去成都一天，2027-04-16出发，2人，总预算300元，不含往返交通，轻松慢游，必须去核心公园";
  static final String PASSWORD = "ClosureFixture2026!";
  // Deliberately incomplete fixed output, not a replay of the unavailable original response.
  static final String EMPTY_PLAN_RESPONSE = """
      {"title":"固定测试·空日程","destination":"稻城亚丁","startDate":"2026-10-28",
       "totalDays":10,"totalBudget":27000,"summary":"固定测试空日程结构，不是原始模型响应。","days":[]}
      """;
  @LocalServerPort int port;
  @Autowired UserService users;
  @Autowired JdbcTemplate db;
  final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  Account owner;
  record Account(long id, String token) {}
  record Api(int status, JsonNode body) { JsonNode data() { return body.path("data"); } }

  @TestConfiguration
  static class FixedModel {
    @Bean @Primary
    AiProvider closureFixtureProvider() {
      return new AiProvider(new AiProperties()) {
        @Override public boolean available() { return true; }
        @Override public TravelRequirement interpret(String description) {
          interpretations.incrementAndGet();
          return JSON.convertValue(requirement(description), TravelRequirement.class);
        }
        @Override public String complete(String input) {
          generations.incrementAndGet();
          if (input.contains("FAIL_CLOSURE_FIXTURE")) throw new BusinessException(502, "固定响应模拟供应商失败");
          if (input.contains("EIGHT_DAY_FIXTURE")) return eightDayDraft().toString();
          if (input.contains("EMPTY_PLAN_FIXTURE")) return EMPTY_PLAN_RESPONSE;
          if (input.contains("DAOCHENG_INCIDENT_FIXTURE")) return incidentFixture().path("responseText").asText();
          return """
              {"title":"固定响应成都慢游","destination":"成都","startDate":"2027-04-16",
               "totalDays":1,"totalBudget":999,"summary":"费用为人民币估算，地点与交通需自行核实。",
               "days":[{"dayTheme":"留一整天给城市","items":[
                 {"itemName":"核心公园","itemType":"SCENIC","startTime":"08:00","costTimeHours":3,"costEstimate":20},
                 {"itemName":"湖边步道","itemType":"SCENIC","startTime":"11:00","costTimeHours":3,"costEstimate":20},
                 {"itemName":"旧街区","itemType":"SCENIC","startTime":"14:00","costTimeHours":3,"costEstimate":20}]}]}
              """;
        }
      };
    }
  }

  @BeforeEach
  void isolatedAccount() throws Exception {
    assertEquals("smart_trip_test", db.queryForObject("SELECT DATABASE()", String.class));
    interpretations.set(0);
    generations.set(0);
    owner = newAccount();
  }

  @Test
  void interpretationDraftEditRestoreConcurrentSaveAndWorkbenchFormOneRealPersistenceChain() throws Exception {
    String interpretationKey = UUID.randomUUID().toString();
    var understand = JSON.createObjectNode().put("requestKey", interpretationKey).put("type", "INTERPRET");
    understand.set("requirement", JSON.createObjectNode().put("description", IDEA));
    String interpretationId = ok(call(owner, "POST", "/api/ai/tasks", understand)).path("id").asText();
    var understood = awaitTask(interpretationId);
    assertEquals("SUCCEEDED", understood.path("status").asText());
    assertEquals(interpretationId, ok(call(owner, "POST", "/api/ai/tasks", understand)).path("id").asText());
    var reviewBody = JSON.createObjectNode().put("extract", false);
    reviewBody.set("requirement", understood.path("interpretation").path("requirement"));
    var confirmed = ok(call(owner, "POST", "/api/ai/tasks/requirements", reviewBody)).path("requirement");
    String key = UUID.randomUUID().toString();
    var submission = generationRequest(key, confirmed, true);
    String id = ok(call(owner, "POST", "/api/ai/tasks", submission)).path("id").asText();
    var preview = awaitTask(id);
    assertEquals("PREVIEW", preview.path("status").asText(), preview.toString());
    assertTrue(preview.path("tripId").isNull());
    assertEquals(0, tripCount());
    assertEquals(60, preview.path("result").path("totalBudget").decimalValue().intValueExact());
    assertTrue(preview.path("generation").path("corrections").toString().contains("明细"));
    assertFalse(preview.path("generation").path("saveBlocked").asBoolean(), preview.toString());
    assertTrue(preview.path("generation").path("warnings").toString().contains("节奏"));
    assertEquals(id, ok(call(owner, "POST", "/api/ai/tasks", submission)).path("id").asText());
    ObjectNode draft = preview.path("result").deepCopy();
    draft.put("title", "用户编辑的成都一天").put("totalBudget", 123456);
    var items = (ArrayNode) draft.path("days").get(0).path("items");
    ((ObjectNode) items.get(0)).put("costEstimate", 40);
    ((ObjectNode) items.get(1)).put("itemName", "湖边步道·慢走").put("startTime", "11:30");
    items.remove(2);
    items.add(JSON.createObjectNode().put("itemName", "自选午后餐食").put("itemType", "FOOD")
        .put("startTime", "15:00").put("costTimeHours", 1).put("costEstimate", 35));
    long revision = preview.path("generation").path("draftRevision").asLong(0);
    var updated = ok(call(owner, "PUT", "/api/ai/tasks/" + id + "/draft", edit(draft, revision)));
    assertEquals(revision + 1, updated.path("generation").path("draftRevision").asLong());
    var restored = ok(call(owner, "GET", "/api/ai/tasks/" + id, null));
    assertEquals(updated.path("result"), restored.path("result"));
    assertEquals(95, restored.path("result").path("totalBudget").decimalValue().intValueExact());
    assertEquals(0, tripCount());
    var apply = Map.of("title", "我的成都慢行", "draftRevision", revision + 1);
    var gate = new CountDownLatch(1);
    var workers = Executors.newFixedThreadPool(2);
    JsonNode first, second;
    try {
      var one = workers.submit(() -> { gate.await(); return ok(call(owner, "POST", "/api/ai/tasks/" + id + "/apply", apply)); });
      var two = workers.submit(() -> { gate.await(); return ok(call(owner, "POST", "/api/ai/tasks/" + id + "/apply", apply)); });
      gate.countDown();
      first = one.get(15, TimeUnit.SECONDS);
      second = two.get(15, TimeUnit.SECONDS);
    } finally { workers.shutdownNow(); }
    long trip = first.path("id").asLong();
    assertTrue(trip > 0);
    assertEquals(trip, second.path("id").asLong());
    assertEquals(1, tripCount());
    assertEquals(1, db.queryForObject("SELECT is_ai_generated FROM trip_plan WHERE id=? AND user_id=?", Integer.class, trip, owner.id()).intValue());
    var saved = ok(call(owner, "GET", "/api/workbench/" + trip, null));
    assertEquals("我的成都慢行", saved.path("title").asText());
    assertEquals("湖边步道·慢走", saved.path("days").get(0).path("items").get(1).path("itemName").asText());
    assertEquals(3, saved.path("days").get(0).path("items").size());
    var members = ok(call(owner, "GET", "/api/membership/trips/" + trip, null));
    assertTrue(members.isArray());
    assertTrue(java.util.stream.StreamSupport.stream(members.spliterator(), false)
        .anyMatch(m -> m.path("userId").asLong() == owner.id() && "OWNER".equals(m.path("role").asText())));
    ObjectNode continued = saved.deepCopy();
    continued.put("title", "工作台继续编辑后的旅程");
    var savedAgain = ok(call(owner, "PUT", "/api/workbench/" + trip, continued));
    assertEquals("工作台继续编辑后的旅程", savedAgain.path("title").asText());
    assertTrue(savedAgain.path("version").asLong() > saved.path("version").asLong());
    assertEquals("工作台继续编辑后的旅程", ok(call(owner, "GET", "/api/workbench/" + trip, null)).path("title").asText());
    assertEquals(trip, ok(call(owner, "POST", "/api/ai/tasks/" + id + "/apply", apply)).path("id").asLong());
    assertEquals(1, interpretations.get());
    assertEquals(1, generations.get());
    assertEquals(2, quotaUsed());
  }

  @Test
  void missingRequirementsOwnershipRevisionHardRulesAndExpiryCannotCreateATrip() throws Exception {
    var req = requirement(IDEA);
    assertEquals(400, call(owner, "POST", "/api/ai/tasks", generationRequest(UUID.randomUUID().toString(), req, false)).status());
    ObjectNode incomplete = req.deepCopy();
    incomplete.remove("destinations");
    assertEquals(400, call(owner, "POST", "/api/ai/tasks", generationRequest(UUID.randomUUID().toString(), incomplete, true)).status());
    assertEquals(0, generations.get());
    String id = ok(call(owner, "POST", "/api/ai/tasks", generationRequest(UUID.randomUUID().toString(), req, true))).path("id").asText();
    var preview = awaitTask(id);
    assertEquals("PREVIEW", preview.path("status").asText(), preview.toString());
    ObjectNode draft = preview.path("result").deepCopy();
    var stranger = newAccount();
    assertEquals(404, call(stranger, "GET", "/api/ai/tasks/" + id, null).status());
    assertEquals(404, call(stranger, "PUT", "/api/ai/tasks/" + id + "/draft", edit(draft, 0)).status());
    assertEquals(404, call(stranger, "POST", "/api/ai/tasks/" + id + "/apply", Map.of("draftRevision", 0)).status());
    assertEquals(409, call(owner, "PUT", "/api/ai/tasks/" + id + "/draft", edit(draft, 99)).status());
    assertEquals(409, call(owner, "POST", "/api/ai/tasks/" + id + "/apply", Map.of("draftRevision", 99)).status());
    ObjectNode wrongDestination = draft.deepCopy();
    wrongDestination.put("destination", "京都");
    var wrong = ok(call(owner, "PUT", "/api/ai/tasks/" + id + "/draft", edit(wrongDestination, 0)));
    assertTrue(wrong.path("generation").path("saveBlocked").asBoolean(), wrong.toString());
    assertEquals(422, call(owner, "POST", "/api/ai/tasks/" + id + "/apply", Map.of("draftRevision", 1)).status());
    ObjectNode overBudget = draft.deepCopy();
    ((ObjectNode) overBudget.path("days").get(0).path("items").get(0)).put("costEstimate", 301);
    var costly = ok(call(owner, "PUT", "/api/ai/tasks/" + id + "/draft", edit(overBudget, 1)));
    assertTrue(hasIssue(costly, "BUDGET_EXCEEDED"), costly.toString());
    assertTrue(costly.path("generation").path("saveBlocked").asBoolean());
    assertEquals(422, call(owner, "POST", "/api/ai/tasks/" + id + "/apply", Map.of("draftRevision", 2)).status());
    ObjectNode overlap = draft.deepCopy();
    ((ObjectNode) overlap.path("days").get(0).path("items").get(0)).put("scheduleLocked", true);
    ((ObjectNode) overlap.path("days").get(0).path("items").get(1)).put("startTime", "09:00").put("scheduleLocked", true);
    var conflicting = ok(call(owner, "PUT", "/api/ai/tasks/" + id + "/draft", edit(overlap, 2)));
    assertTrue(conflicting.path("generation").path("saveBlocked").asBoolean(), conflicting.toString());
    assertTrue(hasIssue(conflicting, "FIXED_NODE_CONFLICT"), conflicting.toString());
    assertEquals("09:00", conflicting.path("result").path("days").get(0).path("items").get(1).path("startTime").asText());
    assertEquals(422, call(owner, "POST", "/api/ai/tasks/" + id + "/apply", Map.of("draftRevision", 3)).status());
    assertEquals(conflicting.path("result"), ok(call(owner, "GET", "/api/ai/tasks/" + id, null)).path("result"));
    ObjectNode resolved = conflicting.path("result").deepCopy();
    ((ObjectNode) resolved.path("days").get(0).path("items").get(1)).put("startTime", "11:00");
    var corrected = ok(call(owner, "PUT", "/api/ai/tasks/" + id + "/draft", edit(resolved, 3)));
    assertEquals(4, corrected.path("generation").path("draftRevision").asInt());
    assertFalse(corrected.path("generation").path("saveBlocked").asBoolean(), corrected.toString());
    assertTrue(corrected.path("generation").path("issues").isEmpty(), corrected.toString());
    assertEquals(1, generations.get());
    assertEquals(1, db.update("UPDATE ai_task SET proposal_meta_json=JSON_SET(proposal_meta_json,'$.expiresAt',?) WHERE id=? AND user_id=?",
        Instant.now().minusSeconds(1).toString(), id, owner.id()));
    assertEquals(410, call(owner, "PUT", "/api/ai/tasks/" + id + "/draft", edit(resolved, 4)).status());
    assertEquals(410, call(owner, "POST", "/api/ai/tasks/" + id + "/apply", Map.of("draftRevision", 4)).status());
    assertEquals(404, call(owner, "GET", "/api/ai/tasks/" + UUID.randomUUID(), null).status());
    assertEquals(0, tripCount());
  }

  @Test
  void providerFailureRefundsAndSameRequestKeyDoesNotCallAgain() throws Exception {
    String key = UUID.randomUUID().toString();
    var submission = generationRequest(key, requirement(IDEA + " FAIL_CLOSURE_FIXTURE"), true);
    String id = ok(call(owner, "POST", "/api/ai/tasks", submission)).path("id").asText();
    assertEquals("FAILED", awaitTask(id).path("status").asText());
    assertEquals(0, quotaUsed());
    assertEquals(0, tripCount());
    assertEquals(id, ok(call(owner, "POST", "/api/ai/tasks", submission)).path("id").asText());
    assertEquals(1, generations.get());
    assertEquals(0, interpretations.get());
    assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM ai_task WHERE user_id=?", Integer.class, owner.id()).intValue());
  }

  @Test
  void fixedEmptyDaysFailsRefundsAndKeepsTenDaySixPersonRequirementPrivateDiagnostics() throws Exception {
    var req = requirement("无");
    req.put("origin", "苏州").put("days", 10).put("startDate", "2026-10-28").put("travelers", 6)
        .put("budget", 3000).put("budgetMax", 4500).put("budgetScope", "PER_PERSON")
        .put("currency", "CNY").put("notes", "EMPTY_PLAN_FIXTURE").put("includesTravelDays", true);
    req.putArray("destinations").add("稻城亚丁");
    req.putArray("mustVisit");
    var reviewBody = JSON.createObjectNode().put("extract", false);
    reviewBody.set("requirement", req);
    var confirmed = ok(call(owner, "POST", "/api/ai/tasks/requirements", reviewBody)).path("requirement");
    var submission = generationRequest(UUID.randomUUID().toString(), confirmed, true);
    String id = ok(call(owner, "POST", "/api/ai/tasks", submission)).path("id").asText();
    var failed = awaitTask(id);
    assertEquals("FAILED", failed.path("status").asText(), failed.toString());
    assertTrue(failed.path("errorMessage").asText().contains("内容不完整"), failed.toString());
    assertTrue(failed.path("result").isMissingNode() || failed.path("result").isNull());
    assertEquals(0, tripCount());
    assertEquals(0, quotaUsed());
    String storedMetadata = db.queryForObject(
        "SELECT proposal_meta_json FROM ai_task WHERE id=? AND user_id=?", String.class, id, owner.id());
    var metadata = JSON.readTree(storedMetadata);
    var diagnostics = metadata.path("_modelDiagnostics");
    assertEquals(EMPTY_PLAN_RESPONSE, diagnostics.path("responseText").asText());
    assertEquals(EMPTY_PLAN_RESPONSE.length(), diagnostics.path("responseCharacters").asInt());
    assertEquals("PARSE_JSON", diagnostics.path("stage").asText());
    assertEquals("MODEL_OUTPUT_INCOMPLETE", diagnostics.path("failureCategory").asText());
    assertEquals(confirmed, metadata.path("requirement"));
    var publicTask = ok(call(owner, "GET", "/api/ai/tasks/" + id, null));
    assertFalse(publicTask.path("generation").has("_modelDiagnostics"));
    assertFalse(publicTask.toString().contains("responseText"));
    assertEquals(confirmed, publicTask.path("generation").path("requirement"));
    assertEquals(10, publicTask.path("generation").path("requirement").path("days").asInt());
    assertEquals(6, publicTask.path("generation").path("requirement").path("travelers").asInt());
    assertEquals("2026-10-28", publicTask.path("generation").path("requirement").path("startDate").asText());
    assertEquals(id, ok(call(owner, "POST", "/api/ai/tasks", submission)).path("id").asText());
    assertEquals(1, generations.get());
    assertEquals(0, interpretations.get());
    assertEquals(0, quotaUsed());
    assertEquals(0, tripCount());
    assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM ai_task WHERE user_id=?", Integer.class, owner.id()).intValue());
  }

  @Test
  void confirmedEightDayGroupBudgetSurvivesPreviewAndWorkbenchPersistence() throws Exception {
    var req = requirement("从苏州出发去稻城亚丁，2027-10-10出发，8天，7人，人均预算3000~4500元");
    req.put("origin", "苏州").put("days", 8).put("startDate", "2027-10-10").put("travelers", 7)
        .put("budget", 3000).put("budgetMax", 4500).put("budgetScope", "PER_PERSON")
        .put("currency", "CNY").put("notes", "EIGHT_DAY_FIXTURE").putNull("includesTravelDays");
    req.putArray("destinations").add("稻城亚丁");
    req.putArray("mustVisit").add("稻城亚丁");
    var reviewBody = JSON.createObjectNode().put("extract", false);
    reviewBody.set("requirement", req);
    var review = ok(call(owner, "POST", "/api/ai/tasks/requirements", reviewBody));
    assertEquals("RULES", review.path("source").asText());
    var confirmed = review.path("requirement");
    assertEquals("CNY", confirmed.path("currency").asText());
    assertEquals("PER_PERSON", confirmed.path("budgetScope").asText());
    assertEquals(7, confirmed.path("travelers").asInt());
    assertEquals(3000, confirmed.path("budget").asInt());
    assertEquals(4500, confirmed.path("budgetMax").asInt());
    var submission = generationRequest(UUID.randomUUID().toString(), confirmed, true);
    String id = ok(call(owner, "POST", "/api/ai/tasks", submission)).path("id").asText();
    var terminal = awaitTask(id);
    assertEquals("PREVIEW", terminal.path("status").asText(), terminal.toString());
    assertTrue(terminal.path("tripId").isNull());
    assertEquals(0, tripCount());
    var restored = ok(call(owner, "GET", "/api/ai/tasks/" + id, null));
    assertEquals("PREVIEW", restored.path("status").asText());
    assertEquals(terminal.path("result"), restored.path("result"));
    assertEquals("苏州", restored.path("generation").path("requirement").path("origin").asText());
    assertEquals(7, restored.path("generation").path("requirement").path("travelers").asInt());
    assertEightDayTrip(restored.path("result"));
    assertEquals(0, tripCount());
    assertEquals(1, db.update(
        "UPDATE ai_task SET request_hash=? WHERE id=? AND user_id=? AND task_type='GENERATE' AND status='PREVIEW'",
        CommunityService.hash("legacy-generation-prompt-fixture"), id, owner.id()));
    var retried = ok(call(owner, "POST", "/api/ai/tasks", submission));
    assertEquals(id, retried.path("id").asText());
    assertEquals(1, generations.get());
    var changedDays = ((ObjectNode) confirmed).deepCopy().put("days", 9);
    assertEquals(409, call(owner, "POST", "/api/ai/tasks",
        generationRequest(submission.path("requestKey").asText(), changedDays, true)).status());
    var changedBudget = ((ObjectNode) confirmed).deepCopy().put("budgetMax", 4600);
    assertEquals(409, call(owner, "POST", "/api/ai/tasks",
        generationRequest(submission.path("requestKey").asText(), changedBudget, true)).status());
    assertEquals(1, generations.get());
    assertEquals(0, tripCount());
    var saved = ok(call(owner, "POST", "/api/ai/tasks/" + id + "/apply",
        Map.of("title", "固定测试·稻城亚丁八天七人行",
            "draftRevision", restored.path("generation").path("draftRevision").asLong(0))));
    long tripId = saved.path("id").asLong();
    assertTrue(tripId > 0);
    assertEquals(1, tripCount());
    var workbench = ok(call(owner, "GET", "/api/workbench/" + tripId, null));
    assertEquals("固定测试·稻城亚丁八天七人行", workbench.path("title").asText());
    assertEquals(saved.path("days"), workbench.path("days"));
    assertEightDayTrip(workbench);
    assertEquals("APPLIED", ok(call(owner, "GET", "/api/ai/tasks/" + id, null)).path("status").asText());
    assertEquals(1, generations.get());
    assertEquals(0, interpretations.get());
    assertEquals(1, quotaUsed());
  }

  @Test
  void recordedIncidentRetainsEveryDayAndFeeWhileRepairingTimesAndBlockingOverBudgetApply() throws Exception {
    var fixture = incidentFixture();
    ObjectNode req = fixture.path("requirement").deepCopy();
    req.put("notes", "DAOCHENG_INCIDENT_FIXTURE");
    var submission = generationRequest(UUID.randomUUID().toString(), req, true);
    String id = ok(call(owner, "POST", "/api/ai/tasks", submission)).path("id").asText();
    var preview = awaitTask(id);
    assertEquals("PREVIEW", preview.path("status").asText(), preview.toString());
    assertIncidentDraft(preview.path("result"), fixture);
    assertTrue(preview.path("generation").path("saveBlocked").asBoolean(), preview.toString());
    assertTrue(hasIssue(preview, "BUDGET_EXCEEDED"), preview.toString());
    assertFalse(preview.path("generation").path("corrections").isEmpty());
    for (var day : preview.path("result").path("days")) assertKnownTimesDoNotOverlap(day);
    assertEquals(6, preview.path("generation").path("requirement").path("travelers").asInt());
    assertEquals("PER_PERSON", preview.path("generation").path("requirement").path("budgetScope").asText());
    assertEquals(4500, preview.path("generation").path("requirement").path("budgetMax").asInt());
    assertEquals(422, call(owner, "POST", "/api/ai/tasks/" + id + "/apply", Map.of("draftRevision", 0)).status());
    var restored = ok(call(owner, "GET", "/api/ai/tasks/" + id, null));
    assertEquals(preview.path("result"), restored.path("result"));
    assertPrivateResponseHidden(restored);
    var metadata = JSON.readTree(db.queryForObject(
        "SELECT proposal_meta_json FROM ai_task WHERE id=? AND user_id=?", String.class, id, owner.id()));
    assertEquals(fixture.path("responseText").asText(), metadata.path("_modelDiagnostics").path("responseText").asText());
    assertEquals(0, tripCount());
    assertEquals(1, generations.get());
    assertEquals(0, interpretations.get());
    assertEquals(1, quotaUsed());
  }

  @Test
  void ownerCanRecoverRecordedFailedResponseAndManuallyEditBudgetBeforeSavingWithoutAnotherModelCall() throws Exception {
    var fixture = incidentFixture();
    ObjectNode failedRequirement = fixture.path("requirement").deepCopy();
    failedRequirement.put("notes", "FAIL_CLOSURE_FIXTURE");
    String id = ok(call(owner, "POST", "/api/ai/tasks",
        generationRequest(UUID.randomUUID().toString(), failedRequirement, true))).path("id").asText();
    assertEquals("FAILED", awaitTask(id).path("status").asText());
    assertEquals(0, quotaUsed());
    assertEquals(422, call(owner, "POST", "/api/ai/tasks/" + id + "/recover", null).status());
    // Seed only this isolated account's failed task with the recorded, sanitized incident response.
    ObjectNode metadata = (ObjectNode) JSON.readTree(db.queryForObject(
        "SELECT proposal_meta_json FROM ai_task WHERE id=? AND user_id=?", String.class, id, owner.id()));
    metadata.set("requirement", fixture.path("requirement"));
    metadata.put("expiresAt", Instant.now().plusSeconds(3600).toString()).put("draftRevision", 0);
    var diagnostic = metadata.putObject("_modelDiagnostics");
    diagnostic.put("responseText", fixture.path("responseText").asText())
        .put("responseCharacters", fixture.path("responseText").asText().length())
        .put("finishReason", fixture.path("finishReason").asText())
        .put("outputTokens", fixture.path("outputTokens").asInt())
        .put("maxOutputTokens", fixture.path("maxOutputTokens").asInt())
        .put("stage", "VALIDATE_PREVIEW").put("errorCode", 422);
    assertEquals(1, db.update(
        "UPDATE ai_task SET proposal_meta_json=? WHERE id=? AND user_id=? AND status='FAILED' AND result_json IS NULL",
        metadata.toString(), id, owner.id()));
    var failed = ok(call(owner, "GET", "/api/ai/tasks/" + id, null));
    assertTrue(failed.path("recoverable").asBoolean(), failed.toString());
    assertPrivateResponseHidden(failed);
    var stranger = newAccount();
    assertEquals(404, call(stranger, "POST", "/api/ai/tasks/" + id + "/recover", null).status());
    int supplierCallsBeforeRecovery = generations.get(), quotaBeforeRecovery = quotaUsed();
    var recovered = ok(call(owner, "POST", "/api/ai/tasks/" + id + "/recover", null));
    assertEquals(id, recovered.path("id").asText());
    assertEquals("PREVIEW", recovered.path("status").asText(), recovered.toString());
    assertEquals(fixture.path("requirement"), recovered.path("generation").path("requirement"));
    assertIncidentDraft(recovered.path("result"), fixture);
    assertTrue(hasIssue(recovered, "BUDGET_EXCEEDED"));
    assertTrue(recovered.path("generation").path("saveBlocked").asBoolean());
    assertPrivateResponseHidden(recovered);
    assertEquals(recovered, ok(call(owner, "POST", "/api/ai/tasks/" + id + "/recover", null)));
    assertEquals(422, call(owner, "POST", "/api/ai/tasks/" + id + "/apply", Map.of("draftRevision", 0)).status());
    assertEquals(supplierCallsBeforeRecovery, generations.get());
    assertEquals(quotaBeforeRecovery, quotaUsed());
    assertEquals(0, tripCount());
    ObjectNode edited = recovered.path("result").deepCopy();
    // This explicit user edit is test input; the application must never discount the recorded fees itself.
    for (var day : edited.path("days")) for (var item : day.path("items"))
      ((ObjectNode) item).put("costEstimate", item.path("costEstimate").decimalValue().multiply(new BigDecimal("0.70")));
    var changed = ok(call(owner, "PUT", "/api/ai/tasks/" + id + "/draft", edit(edited, 0)));
    assertEquals(1, changed.path("generation").path("draftRevision").asInt());
    assertEquals(0, new BigDecimal("24752").compareTo(changed.path("result").path("totalBudget").decimalValue()));
    assertFalse(changed.path("generation").path("saveBlocked").asBoolean(), changed.toString());
    assertTrue(changed.path("generation").path("issues").isEmpty(), changed.toString());
    assertEquals(changed.path("result"), ok(call(owner, "GET", "/api/ai/tasks/" + id, null)).path("result"));
    assertEquals(409, call(owner, "POST", "/api/ai/tasks/" + id + "/apply", Map.of("draftRevision", 0)).status());
    var saved = ok(call(owner, "POST", "/api/ai/tasks/" + id + "/apply",
        Map.of("draftRevision", 1, "title", "隔离测试·人工确认费用后的十天行程")));
    long trip = saved.path("id").asLong();
    assertTrue(trip > 0);
    var workbench = ok(call(owner, "GET", "/api/workbench/" + trip, null));
    assertEquals(10, workbench.path("days").size());
    assertEquals(35, itemCount(workbench));
    assertEquals(0, new BigDecimal("24752").compareTo(workbench.path("totalBudget").decimalValue()));
    assertEquals(saved.path("days"), workbench.path("days"));
    var applied = ok(call(owner, "POST", "/api/ai/tasks/" + id + "/recover", null));
    assertEquals("APPLIED", applied.path("status").asText());
    assertEquals(trip, applied.path("tripId").asLong());
    assertPrivateResponseHidden(applied);
    assertEquals(supplierCallsBeforeRecovery, generations.get());
    assertEquals(1, generations.get());
    assertEquals(0, interpretations.get());
    assertEquals(quotaBeforeRecovery, quotaUsed());
    assertEquals(1, tripCount());
  }

  static JsonNode incidentFixture() {
    try (var input = AiGenerationClosureIntegrationTests.class.getResourceAsStream("/ai/daocheng-generation-incident.json")) {
      if (input == null) throw new IllegalStateException("Missing recorded incident regression resource");
      return JSON.readTree(input);
    } catch (java.io.IOException e) { throw new IllegalStateException("Cannot read recorded incident regression resource", e); }
  }

  static boolean hasIssue(JsonNode task, String code) {
    for (var issue : task.path("generation").path("issues")) if (code.equals(issue.path("code").asText())) return true;
    return false;
  }

  static int itemCount(JsonNode trip) {
    int count = 0;
    for (var day : trip.path("days")) count += day.path("items").size();
    return count;
  }

  static void assertPrivateResponseHidden(JsonNode task) {
    assertFalse(task.path("generation").has("_modelDiagnostics"));
    assertFalse(task.toString().contains("responseText"));
    assertFalse(task.toString().contains("responseCharacters"));
  }

  static void assertIncidentDraft(JsonNode draft, JsonNode fixture) throws Exception {
    var original = JSON.readTree(fixture.path("responseText").asText());
    assertEquals("稻城亚丁", draft.path("destination").asText());
    assertEquals("2026-10-28", draft.path("startDate").asText());
    assertEquals(10, draft.path("totalDays").asInt());
    assertEquals(10, draft.path("days").size());
    assertEquals(35, itemCount(draft));
    assertEquals(0, new BigDecimal("35360").compareTo(draft.path("totalBudget").decimalValue()));
    for (int d = 0; d < 10; d++) {
      var actualDay = draft.path("days").get(d);
      assertEquals(d + 1, actualDay.path("dayIndex").asInt());
      assertEquals(LocalDate.parse("2026-10-28").plusDays(d).toString(), actualDay.path("dayDate").asText());
      Map<String, JsonNode> originalItems = new LinkedHashMap<>();
      for (var item : original.path("days").get(d).path("items")) originalItems.put(item.path("itemName").asText(), item);
      assertEquals(originalItems.size(), actualDay.path("items").size());
      for (var actual : actualDay.path("items")) {
        var source = originalItems.remove(actual.path("itemName").asText());
        assertNotNull(source, "No invented or duplicated activity may replace the recorded response");
        assertEquals(0, source.path("costEstimate").decimalValue().compareTo(actual.path("costEstimate").decimalValue()));
        assertEquals(0, source.path("costTimeHours").decimalValue().compareTo(actual.path("costTimeHours").decimalValue()));
      }
      assertTrue(originalItems.isEmpty(), "No recorded activity may be silently dropped");
    }
  }

  static void assertKnownTimesDoNotOverlap(JsonNode day) {
    int previousEnd = -1;
    for (var item : day.path("items")) {
      if (item.path("startTime").isNull() || item.path("startTime").isMissingNode()) continue;
      String[] clock = item.path("startTime").asText().split(":");
      int start = Integer.parseInt(clock[0]) * 60 + Integer.parseInt(clock[1]);
      int duration = item.path("costTimeHours").decimalValue().multiply(BigDecimal.valueOf(60)).intValueExact();
      assertTrue(start >= previousEnd, "Known activities still overlap: " + item.path("itemName").asText());
      assertTrue(start + duration <= 1440);
      previousEnd = start + duration;
    }
  }

  static ObjectNode eightDayDraft() {
    var draft = JSON.createObjectNode().put("title", "固定测试·稻城亚丁八天")
        .put("destination", "稻城亚丁").put("startDate", "2027-10-10")
        .put("totalDays", 8).put("totalBudget", 28000).put("travelGroup", "7人同行")
        .put("summary", "固定模型测试草稿；费用是全体7人合计的人民币估算，地点、交通与开放信息待核实。");
    var days = draft.putArray("days");
    for (int i = 0; i < 8; i++) {
      var day = days.addObject().put("dayIndex", i + 1)
          .put("dayDate", LocalDate.parse("2027-10-10").plusDays(i).toString())
          .put("dayTheme", "固定测试第" + (i + 1) + "天")
          .put("daySummary", "仅验证八天完整结构和全体费用口径，不表示真实路线或报价。");
      var items = day.putArray("items");
      items.addObject().put("itemName", "稻城亚丁·第" + (i + 1) + "天固定测试活动")
          .put("itemType", "SCENIC").put("startTime", "09:00").put("costTimeHours", 3)
          .put("costEstimate", 800).put("environmentType", "UNKNOWN")
          .put("transportTips", "交通方式与费用覆盖范围待核实")
          .put("aiTips", "固定测试全体7人合计估算；不是真实票价");
      items.addObject().put("itemName", "第" + (i + 1) + "天固定测试餐食")
          .put("itemType", "FOOD").put("startTime", "12:00").put("costTimeHours", 1)
          .put("costEstimate", 700).put("environmentType", "UNKNOWN")
          .put("transportTips", "实际地点与交通待核实")
          .put("aiTips", "固定测试全体7人合计餐费估算");
      items.addObject().put("itemName", "第" + (i + 1) + "天固定测试住宿办理")
          .put("itemType", "HOTEL").put("startTime", "16:00").put("costTimeHours", 1)
          .put("costEstimate", 2000).put("environmentType", "UNKNOWN")
          .put("transportTips", "住宿地点与交通待核实")
          .put("aiTips", "固定测试全体7人合计住宿估算，非实时房价");
    }
    return draft;
  }

  static void assertEightDayTrip(JsonNode trip) {
    assertEquals("稻城亚丁", trip.path("destination").asText());
    assertEquals("2027-10-10", trip.path("startDate").asText());
    assertEquals(8, trip.path("totalDays").asInt());
    var days = trip.path("days");
    assertTrue(days.isArray());
    assertEquals(8, days.size());
    BigDecimal total = BigDecimal.ZERO;
    for (int i = 0; i < 8; i++) {
      var day = days.get(i);
      assertEquals(i + 1, day.path("dayIndex").asInt());
      assertEquals(LocalDate.parse("2027-10-10").plusDays(i).toString(), day.path("dayDate").asText());
      assertFalse(day.path("dayTheme").asText().isBlank());
      assertFalse(day.path("daySummary").asText().isBlank());
      var items = day.path("items");
      assertTrue(items.isArray());
      assertEquals(3, items.size());
      for (var item : items) {
        assertFalse(item.path("itemName").asText().isBlank());
        assertTrue(Set.of("SCENIC", "FOOD", "HOTEL", "TRAFFIC", "OTHER").contains(item.path("itemType").asText()));
        assertTrue(item.path("startTime").asText().matches("(?:[01]\\d|2[0-3]):[0-5]\\d"));
        assertTrue(item.path("costTimeHours").isNumber());
        assertTrue(item.path("costTimeHours").decimalValue().signum() > 0);
        assertTrue(item.path("costEstimate").isNumber());
        assertTrue(item.path("costEstimate").decimalValue().signum() > 0);
        assertFalse(item.path("transportTips").asText().isBlank());
        assertFalse(item.path("aiTips").asText().isBlank());
        total = total.add(item.path("costEstimate").decimalValue());
      }
    }
    assertEquals(0, total.compareTo(new BigDecimal("28000")));
    assertEquals(0, total.compareTo(trip.path("totalBudget").decimalValue()));
    assertTrue(total.compareTo(new BigDecimal("31500")) <= 0);
    assertTrue(total.compareTo(new BigDecimal("21000")) >= 0);
  }

  static ObjectNode requirement(String description) {
    var r = JSON.createObjectNode().put("description", description).put("origin", "上海")
        .put("days", 1).put("startDate", "2027-04-16").put("travelers", 2)
        .put("includesTravelDays", false).put("budget", 300).put("budgetScope", "TOTAL")
        .put("currency", "CNY").put("pace", "RELAXED");
    r.putArray("destinations").add("成都");
    r.putArray("mustVisit").add("核心公园");
    return r;
  }

  ObjectNode generationRequest(String key, JsonNode requirement, boolean confirmed) {
    var body = JSON.createObjectNode().put("requestKey", key).put("type", "GENERATE").put("confirmed", confirmed);
    body.set("requirement", requirement);
    return body;
  }

  ObjectNode edit(JsonNode draft, long revision) {
    var body = JSON.createObjectNode().put("draftRevision", revision);
    body.set("draft", draft);
    return body;
  }

  Account newAccount() throws Exception {
    var registration = new RegisterDTO();
    registration.setUsername("ai_closure_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
    registration.setPassword(PASSWORD);
    registration.setNickname("隔离AI闭环验证");
    long user = users.register(registration);
    var login = ok(call(null, "POST", "/api/auth/login", Map.of("username", registration.getUsername(), "password", PASSWORD)));
    return new Account(user, login.path("token").asText());
  }

  JsonNode awaitTask(String id) throws Exception {
    for (int i = 0; i < 300; i++) {
      var task = ok(call(owner, "GET", "/api/ai/tasks/" + id, null));
      if (!Set.of("QUEUED", "RUNNING").contains(task.path("status").asText())) return task;
      Thread.sleep(50);
    }
    throw new AssertionError("Fixture task did not finish: " + id);
  }

  Api call(Account account, String method, String path, Object body) throws Exception {
    var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(10));
    if (account != null) request.header("Authorization", "Bearer " + account.token());
    if (body == null) request.method(method, HttpRequest.BodyPublishers.noBody());
    else request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
    var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    return new Api(response.statusCode(), JSON.readTree(response.body()));
  }

  JsonNode ok(Api response) {
    assertEquals(200, response.status(), response.body().toString());
    assertEquals(200, response.body().path("code").asInt());
    return response.data();
  }

  int tripCount() { return db.queryForObject("SELECT COUNT(*) FROM trip_plan WHERE user_id=?", Integer.class, owner.id()); }
  int quotaUsed() { return db.queryForObject("SELECT ai_quota_used FROM sys_user WHERE id=?", Integer.class, owner.id()); }

  @Test
  void confirmedBudgetRaiseRevalidatesTheSameIncidentDraftAndPersistsAuditWithoutAnotherModelCall() throws Exception {
    var fixture = incidentFixture();
    ObjectNode req = fixture.path("requirement").deepCopy();
    req.put("notes", "DAOCHENG_INCIDENT_FIXTURE");
    var submission = generationRequest(UUID.randomUUID().toString(), req, true);
    String id = ok(call(owner, "POST", "/api/ai/tasks", submission)).path("id").asText();
    var preview = awaitTask(id);
    assertEquals("PREVIEW", preview.path("status").asText(), preview.toString());
    assertTrue(hasIssue(preview, "BUDGET_EXCEEDED"));
    assertTrue(preview.hasNonNull("createdAt"));
    assertTrue(preview.hasNonNull("startedAt"));
    Instant.parse(preview.path("startedAt").asText());
    var phases = preview.path("generation").path("phaseHistory");
    assertEquals(List.of("QUEUED", "MODEL", "MODEL_COMPLETED", "NORMALIZING", "VALIDATING", "PREVIEW"),
        java.util.stream.StreamSupport.stream(phases.spliterator(), false)
            .map(phase -> phase.path("phase").asText()).toList());
    Instant previous = Instant.MIN;
    for (var phase : phases) {
      Instant at = Instant.parse(phase.path("at").asText());
      assertFalse(at.isBefore(previous));
      previous = at;
    }
    var events = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/ai/tasks/" + id + "/events"))
        .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + owner.token())
        .header("Accept", "text/event-stream").GET().build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(200, events.statusCode());
    assertTrue(events.body().contains("phaseHistory"));
    assertTrue(events.body().contains("MODEL_COMPLETED"));
    assertFalse(events.body().contains("responseText"));
    String key = UUID.randomUUID().toString();
    var budgetRequest = Map.of("budgetMax", 8000, "confirmed", true, "draftRevision", 0, "requestKey", key);
    int usedBefore = quotaUsed();
    var raised = ok(call(owner, "PUT", "/api/ai/tasks/" + id + "/budget", budgetRequest));
    assertEquals(id, raised.path("id").asText());
    assertEquals("PREVIEW", raised.path("status").asText());
    assertEquals(1, raised.path("generation").path("draftRevision").asInt());
    assertEquals(preview.path("result"), raised.path("result"));
    assertEquals(phases, raised.path("generation").path("phaseHistory"));
    assertFalse(raised.path("generation").path("saveBlocked").asBoolean(), raised.toString());
    var original = raised.path("generation").path("originalRequirement");
    assertEquals(preview.path("generation").path("requirement"), original);
    var current = raised.path("generation").path("requirement");
    assertEquals(3000, current.path("budget").asInt());
    assertEquals(8000, current.path("budgetMax").asInt());
    assertEquals("PER_PERSON", current.path("budgetScope").asText());
    assertEquals(6, current.path("travelers").asInt());
    assertEquals("CNY", current.path("currency").asText());
    var audit = raised.path("generation").path("budgetChanges");
    assertEquals(1, audit.size());
    assertEquals(key, audit.get(0).path("requestKey").asText());
    assertEquals(owner.id(), audit.get(0).path("userId").asLong());
    assertEquals(27000, audit.get(0).path("before").path("totalCap").asInt());
    assertEquals(48000, audit.get(0).path("after").path("totalCap").asInt());
    Instant.parse(audit.get(0).path("changedAt").asText());
    assertEquals(raised, ok(call(owner, "GET", "/api/ai/tasks/" + id, null)));
    assertEquals(raised, ok(call(owner, "PUT", "/api/ai/tasks/" + id + "/budget", budgetRequest)));
    assertEquals(409, call(owner, "PUT", "/api/ai/tasks/" + id + "/budget",
        Map.of("budgetMax", 8500, "confirmed", true, "draftRevision", 0, "requestKey", key)).status());
    // Legacy prompt hashes must still resolve against the original confirmed requirement.
    assertEquals(1, db.update("UPDATE ai_task SET request_hash=? WHERE id=? AND user_id=?",
        CommunityService.hash("legacy-before-budget-revision"), id, owner.id()));
    assertEquals(id, ok(call(owner, "POST", "/api/ai/tasks", submission)).path("id").asText());
    ObjectNode changedSubmission = submission.deepCopy();
    changedSubmission.set("requirement", current);
    assertEquals(409, call(owner, "POST", "/api/ai/tasks", changedSubmission).status());
    assertEquals(1, generations.get());
    assertEquals(usedBefore, quotaUsed());
    assertEquals(0, tripCount());
    assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM ai_task WHERE user_id=?", Integer.class, owner.id()).intValue());
    assertEquals(409, call(owner, "POST", "/api/ai/tasks/" + id + "/apply", Map.of("draftRevision", 0)).status());
    var saved = ok(call(owner, "POST", "/api/ai/tasks/" + id + "/apply", Map.of("draftRevision", 1)));
    var workbench = ok(call(owner, "GET", "/api/workbench/" + saved.path("id").asLong(), null));
    assertEquals(10, workbench.path("days").size());
    assertEquals(35, itemCount(workbench));
    assertEquals(preview.path("result").path("totalBudget"), workbench.path("totalBudget"));
    assertEquals(1, tripCount());
    assertEquals(1, generations.get());
    assertEquals(usedBefore, quotaUsed());
  }

  @Test
  void budgetRaiseCannotBypassOwnershipConfirmationRevisionExpiryOrActualIncrease() throws Exception {
    String id = ok(call(owner, "POST", "/api/ai/tasks",
        generationRequest(UUID.randomUUID().toString(), requirement(IDEA), true))).path("id").asText();
    var preview = awaitTask(id);
    assertEquals("PREVIEW", preview.path("status").asText(), preview.toString());
    var stranger = newAccount();
    String path = "/api/ai/tasks/" + id + "/budget";
    String key = UUID.randomUUID().toString();
    var valid = Map.of("budgetMax", 400, "confirmed", true, "draftRevision", 0, "requestKey", key);
    assertEquals(404, call(stranger, "PUT", path, valid).status());
    assertEquals(400, call(owner, "PUT", path,
        Map.of("budgetMax", 400, "confirmed", false, "draftRevision", 0, "requestKey", key)).status());
    assertEquals(409, call(owner, "PUT", path,
        Map.of("budgetMax", 400, "confirmed", true, "draftRevision", 1, "requestKey", key)).status());
    assertEquals(400, call(owner, "PUT", path,
        Map.of("budgetMax", 300, "confirmed", true, "draftRevision", 0, "requestKey", key)).status());
    assertEquals(preview.path("generation"), ok(call(owner, "GET", "/api/ai/tasks/" + id, null)).path("generation"));
    var metadata = (ObjectNode) JSON.readTree(db.queryForObject(
        "SELECT proposal_meta_json FROM ai_task WHERE id=? AND user_id=?", String.class, id, owner.id()));
    metadata.put("expiresAt", Instant.now().minusSeconds(1).toString());
    assertEquals(1, db.update("UPDATE ai_task SET proposal_meta_json=? WHERE id=? AND user_id=? AND status='PREVIEW'",
        metadata.toString(), id, owner.id()));
    assertEquals(410, call(owner, "PUT", path, valid).status());
    assertEquals(1, generations.get());
    assertEquals(1, quotaUsed());
    assertEquals(0, tripCount());
  }
}

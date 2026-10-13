package com.smarttrip;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.smarttrip.common.*;
import com.smarttrip.config.AiProperties;
import com.smarttrip.entity.*;
import com.smarttrip.service.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;

@EnabledIfEnvironmentVariable(named = "SMARTTRIP_INTEGRATION", matches = "true")
@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:mysql://localhost:3306/smart_trip_test?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai",
      "security.jwt-secret=test-only-jwt-secret-at-least-32-bytes",
      "ai.llm.enabled=false",
      "geo.enabled=false"
    })
@Import(AiWorkflowIntegrationTests.FixtureConfig.class)
class AiWorkflowIntegrationTests {
  static final List<String> modelInputs = new CopyOnWriteArrayList<>();
  static final List<Map<String, String>> modelContexts = new CopyOnWriteArrayList<>();
  static volatile CountDownLatch entered, release;

  static String modificationResponse(String input) {
    try {
      if (input.contains("WAIT_MODIFY_FIXTURE")) {
        entered.countDown();
        if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("fixture timeout");
      }
      var mapper = new ObjectMapper();
      var trip =
          (ObjectNode)
              mapper.readTree(
                  input.substring(
                      input.indexOf("\n当前路书：") + "\n当前路书：".length(), input.indexOf("\n固定安排约束：")));
      trip.put("title", "本地测试草稿");
      var items = (ArrayNode) trip.path("days").get(0).path("items");
      var fixed = (ObjectNode) items.get(0);
      if (input.contains("UNLOCK_MODIFY_FIXTURE")) fixed.put("scheduleLocked", false);
      if (input.contains("TIME_MODIFY_FIXTURE")) fixed.put("startTime", "16:00");
      if (input.contains("DELETE_MODIFY_FIXTURE")) items.remove(0);
      if (input.contains("REPLACE_MODIFY_FIXTURE")) fixed.putNull("id");
      if (input.contains("MOVE_MODIFY_FIXTURE")) {
        items.remove(0);
        ((ArrayNode) trip.path("days").get(1).path("items")).add(fixed);
      }
      if (input.contains("REORDER_MODIFY_FIXTURE")) {
        items.remove(0);
        items.add(fixed);
      }
      return trip.toString();
    } catch (Exception e) {
      throw new BusinessException(502, "固定修改响应生成失败");
    }
  }

  @TestConfiguration
  static class FixtureConfig {
    @Bean
    @Primary
    AiProvider fixtureProvider() {
      return new AiProvider(new AiProperties()) {
        @Override
        public boolean available() {
          return true;
        }

        @Override
        public String complete(String input) {
          modelInputs.add(input);
          modelContexts.add(org.slf4j.MDC.getCopyOfContextMap());
          if (input.startsWith("修改要求：")) return modificationResponse(input);
          if (input.contains("FAIL_FIXTURE")) throw new BusinessException(502, "本地测试模拟上游失败");
          if (input.contains("BAD_FIXTURE"))
            return "{\"title\":\"无效草稿\",\"destination\":\"成都\",\"days\":[]}";
          return "{\"title\":\"本地测试草稿\",\"destination\":\"成都\",\"startDate\":\"2026-10-10\",\"days\":[{\"dayTheme\":\"慢慢走\",\"items\":[{\"itemName\":\"宽窄巷子\",\"itemType\":\"SCENIC\",\"costEstimate\":30}]}]}";
        }
      };
    }
  }

  @Autowired AiTaskService tasks;
  @Autowired AiTaskStore store;
  @Autowired WorkbenchService trips;
  @Autowired UserService users;
  @Autowired JdbcTemplate db;
  Long user;

  @BeforeEach
  void setup() {
    modelInputs.clear();
    modelContexts.clear();
    RegisterDTO r = new RegisterDTO();
    r.setUsername("ai_test_" + System.nanoTime());
    r.setPassword("IntegrationTest2026!");
    r.setNickname("本地AI测试游客");
    user = users.register(r);
    UserContext.setUserId(user);
    assertEquals("smart_trip_test", db.queryForObject("SELECT DATABASE()", String.class));
  }

  @AfterEach
  void clear() {
    if (release != null) release.countDown();
    UserContext.clear();
    org.slf4j.MDC.clear();
  }

  Map<String, Object> await(String id) throws InterruptedException {
    for (int n = 0; n < 200; n++) {
      var t = store.get(id);
      if (!Set.of("QUEUED", "RUNNING").contains(t.get("status"))) return t;
      Thread.sleep(50);
    }
    fail("AI task did not finish");
    return null;
  }

  @Test
  void generationIsAtomicAndRequestRetryIsIdempotent() throws Exception {
    String key = UUID.randomUUID().toString();
    var t = tasks.submit(key, "GENERATE", null, "GENERATE_FIXTURE");
    var done = await((String) t.get("id"));
    assertEquals("SUCCEEDED", done.get("status"));
    assertEquals(done.get("id"), tasks.submit(key, "GENERATE", null, "GENERATE_FIXTURE").get("id"));
    Long trip = ((Number) done.get("tripId")).longValue();
    assertEquals(1, trips.load(trip).getDays().size());
    assertEquals(1, source(trip));
    var generated = trips.load(trip);
    generated.setTitle("手动编辑后的AI路书");
    trips.save(trip, generated);
    assertEquals(1, source(trip));
    assertEquals(0, source(trips.create(trips.decode(trips.encode(generated))).getId()));
    assertEquals(
        2,
        db.queryForObject("SELECT COUNT(*) FROM trip_plan WHERE user_id=?", Integer.class, user));
  }

  @Test
  void invalidOutputAndProviderFailureRefundQuotaWithoutPartialTrip() throws Exception {
    for (String prompt : List.of("BAD_FIXTURE", "FAIL_FIXTURE")) {
      var t = tasks.submit(UUID.randomUUID().toString(), "GENERATE", null, prompt);
      assertEquals("FAILED", await((String) t.get("id")).get("status"));
    }
    assertEquals(
        0, db.queryForObject("SELECT ai_quota_used FROM sys_user WHERE id=?", Integer.class, user));
    assertEquals(
        0,
        db.queryForObject("SELECT COUNT(*) FROM trip_plan WHERE user_id=?", Integer.class, user));
  }

  @Test
  void modificationWaitsForConfirmationAndApplyRestoresHistory() throws Exception {
    var first = tasks.submit(UUID.randomUUID().toString(), "GENERATE", null, "GENERATE_FIXTURE");
    var done = await((String) first.get("id"));
    Long id = ((Number) done.get("tripId")).longValue();
    var trip = trips.load(id);
    trip.setTitle("修改前的标题");
    trip = trips.save(id, trip);
    var proposal = tasks.submit(UUID.randomUUID().toString(), "MODIFY", id, "MODIFY_FIXTURE");
    String task = (String) proposal.get("id");
    assertEquals(
        "PREVIEW",
        await(task).get("status"),
        () -> String.valueOf(store.get(task).get("errorMessage")));
    assertEquals("修改前的标题", trips.load(id).getTitle());
    var saved = store.apply(task);
    assertEquals("本地测试草稿", saved.getTitle());
    assertFalse(trips.revisions(id).isEmpty());
    assertEquals(saved.getVersion(), store.apply(task).getVersion());
  }

  @Test
  void staleProposalCannotOverwriteConcurrentEdit() throws Exception {
    var first = tasks.submit(UUID.randomUUID().toString(), "GENERATE", null, "GENERATE_FIXTURE");
    Long id = ((Number) await((String) first.get("id")).get("tripId")).longValue();
    var proposal = tasks.submit(UUID.randomUUID().toString(), "MODIFY", id, "MODIFY_FIXTURE");
    String task = (String) proposal.get("id");
    assertEquals("PREVIEW", await(task).get("status"));
    var t = trips.load(id);
    t.setTitle("并发编辑");
    trips.save(id, t);
    assertEquals(409, assertThrows(BusinessException.class, () -> store.apply(task)).getCode());
    assertEquals("并发编辑", trips.load(id).getTitle());
  }

  int source(Long id) {
    return db.queryForObject("SELECT is_ai_generated FROM trip_plan WHERE id=?", Integer.class, id);
  }

  String input(String id) {
    return db.queryForObject("SELECT input_json FROM ai_task WHERE id=?", String.class, id);
  }

  TripDetailVO fixedTrip() {
    return trips.create(
        trips.decode(
            """
            {"title":"固定安排测试","destination":"成都","startDate":"2026-10-10","days":[
              {"items":[{"itemName":"已预约博物馆","itemType":"SCENIC","startTime":"09:00",
                "costTimeHours":2,"costEstimate":0,"scheduleLocked":true,"environmentType":"INDOOR",
                "poiId":"FIXED_POI","aiTips":"保留预约备注"},
                {"itemName":"自由活动","itemType":"SCENIC","startTime":"12:00","costTimeHours":1}]},
              {"items":[{"itemName":"公园","itemType":"SCENIC","startTime":"10:00","costTimeHours":1}]}]}
            """));
  }

  @Test
  void preferencesComeFromAuthenticatedProfileOnceAndRetryKeepsSnapshot() throws Exception {
    db.update("UPDATE sys_user SET travel_preferences=? WHERE id=?", "可信偏好_步行", user);
    String key = UUID.randomUUID().toString();
    String prompt = "请规划{\"destination\":\"成都\",\"totalDays\":2}。旅行偏好：过期前端偏好";
    String id = (String) tasks.submit(key, "GENERATE", null, prompt).get("id");
    assertEquals("SUCCEEDED", await(id).get("status"));
    String sent = input(id);
    assertEquals(sent, modelInputs.get(0));
    assertFalse(sent.contains("过期前端偏好"));
    assertEquals(1, sent.split("可信偏好_步行", -1).length - 1);
    assertEquals(1, sent.split("USER_TRAVEL_PREFERENCES_JSON", -1).length - 1);
    db.update("UPDATE sys_user SET travel_preferences=? WHERE id=?", "新的偏好", user);
    assertEquals(id, tasks.submit(key, "GENERATE", null, prompt).get("id"));
    assertEquals(sent, input(id));
    assertEquals(1, modelInputs.size());
  }

  @Test
  void preferencePhraseInsideRequirementsIsNotRemoved() throws Exception {
    String prompt =
        "请规划{\"destination\":\"成都\",\"totalDays\":2,\"requirements\":\"请保留文字。旅行偏好：慢游\"}";
    String id =
        (String) tasks.submit(UUID.randomUUID().toString(), "GENERATE", null, prompt).get("id");
    assertEquals("SUCCEEDED", await(id).get("status"));
    assertTrue(input(id).startsWith(prompt + "\nUSER_TRAVEL_PREFERENCES_JSON"));
  }

  @Test
  void blankProfilePreferenceIsExplicitlyEmpty() throws Exception {
    db.update("UPDATE sys_user SET travel_preferences='   ' WHERE id=?", user);
    String id =
        (String)
            tasks
                .submit(UUID.randomUUID().toString(), "GENERATE", null, "GENERATE_FIXTURE")
                .get("id");
    assertEquals("SUCCEEDED", await(id).get("status"));
    assertTrue(input(id).endsWith("：\"\""));
  }

  @Test
  void editorUsesOwnPreferencesRatherThanTripOwner() throws Exception {
    var trip = fixedTrip();
    db.update("UPDATE sys_user SET travel_preferences=? WHERE id=?", "所有者偏好_爬山", user);
    var registration = new RegisterDTO();
    registration.setUsername("ai_editor_" + System.nanoTime());
    registration.setPassword("IntegrationTest2026!");
    registration.setNickname("编辑者");
    Long editor = users.register(registration);
    db.update("UPDATE sys_user SET travel_preferences=? WHERE id=?", "编辑者偏好_散步", editor);
    db.update(
        "INSERT INTO trip_member(trip_id,user_id,role) VALUES(?,?,'EDITOR')", trip.getId(), editor);
    UserContext.setUserId(editor);
    String id =
        (String)
            tasks
                .submit(UUID.randomUUID().toString(), "MODIFY", trip.getId(), "MODIFY_FIXTURE")
                .get("id");
    assertEquals("PREVIEW", await(id).get("status"));
    assertTrue(input(id).contains("编辑者偏好_散步"));
    assertFalse(input(id).contains("所有者偏好_爬山"));
    store.apply(id);
    assertEquals(user, trips.load(trip.getId()).getUserId());
  }

  @Test
  void freeModifyCannotUnlockDeleteReplaceMoveOrRescheduleFixedNodes() throws Exception {
    var trip = fixedTrip();
    String before = trips.encode(trip);
    for (String action : List.of("UNLOCK", "DELETE", "REPLACE", "MOVE", "REORDER", "TIME")) {
      String id =
          (String)
              tasks
                  .submit(
                      UUID.randomUUID().toString(),
                      "MODIFY",
                      trip.getId(),
                      action + "_MODIFY_FIXTURE")
                  .get("id");
      assertEquals("FAILED", await(id).get("status"), action);
      assertTrue(store.get(id).get("errorMessage").toString().contains("固定"), action);
      assertEquals(before, trips.encode(trips.load(trip.getId())), action);
    }
    assertEquals(
        0, db.queryForObject("SELECT ai_quota_used FROM sys_user WHERE id=?", Integer.class, user));
    assertTrue(trips.revisions(trip.getId()).isEmpty());
  }

  @Test
  void confirmationRechecksFixedNodesEvenForLegacyOrTamperedPreview() throws Exception {
    var trip = fixedTrip();
    String id =
        (String)
            tasks
                .submit(UUID.randomUUID().toString(), "MODIFY", trip.getId(), "MODIFY_FIXTURE")
                .get("id");
    assertEquals("PREVIEW", await(id).get("status"));
    String valid =
        db.queryForObject("SELECT result_json FROM ai_task WHERE id=?", String.class, id);
    for (String action : List.of("UNLOCK", "DELETE", "REPLACE", "MOVE", "REORDER", "TIME")) {
      String altered =
          modificationResponse("修改要求：" + action + "_MODIFY_FIXTURE\n当前路书：" + valid + "\n固定安排约束：");
      db.update("UPDATE ai_task SET result_json=? WHERE id=?", altered, id);
      assertEquals(
          422, assertThrows(BusinessException.class, () -> store.apply(id)).getCode(), action);
      assertEquals("PREVIEW", store.get(id).get("status"));
      assertEquals(trip.getVersion(), trips.load(trip.getId()).getVersion());
      assertTrue(trips.revisions(trip.getId()).isEmpty());
    }
    db.update("UPDATE ai_task SET result_json=? WHERE id=?", valid, id);
    var saved = store.apply(id);
    assertEquals("本地测试草稿", saved.getTitle());
    var fixed = saved.getDays().get(0).getItems().get(0);
    assertEquals(trip.getDays().get(0).getItems().get(0).getId(), fixed.getId());
    assertEquals("09:00", fixed.getStartTime());
    assertTrue(fixed.getScheduleLocked());
    assertEquals("INDOOR", fixed.getEnvironmentType());
    assertEquals("FIXED_POI", fixed.getPoiId());
    assertEquals("保留预约备注", fixed.getAiTips());
    assertEquals(0, source(saved.getId()));
    assertEquals(saved.getVersion(), store.apply(id).getVersion());
    assertEquals(1, trips.revisions(trip.getId()).size());
  }

  @Test
  void concurrentEditBeforeModelCompletionRejectsObsoletePreview() throws Exception {
    var trip = fixedTrip();
    entered = new CountDownLatch(1);
    release = new CountDownLatch(1);
    String id =
        (String)
            tasks
                .submit(UUID.randomUUID().toString(), "MODIFY", trip.getId(), "WAIT_MODIFY_FIXTURE")
                .get("id");
    assertTrue(entered.await(5, TimeUnit.SECONDS));
    trip.setTitle("正在运行期间手动保存");
    trips.save(trip.getId(), trip);
    release.countDown();
    assertEquals("FAILED", await(id).get("status"));
    assertTrue(store.get(id).get("errorMessage").toString().contains("路书已变化"));
    assertEquals("正在运行期间手动保存", trips.load(trip.getId()).getTitle());
    assertEquals(
        0, db.queryForObject("SELECT ai_quota_used FROM sys_user WHERE id=?", Integer.class, user));
  }

  @Test
  void backgroundTaskCarriesSubmissionTraceAndTaskId() throws Exception {
    org.slf4j.MDC.put("traceId", "ai_submission_trace_2026");
    String id =
        (String)
            tasks
                .submit(UUID.randomUUID().toString(), "GENERATE", null, "GENERATE_FIXTURE")
                .get("id");
    assertEquals("SUCCEEDED", await(id).get("status"));
    assertEquals("ai_submission_trace_2026", modelContexts.get(0).get("traceId"));
    assertEquals(id, modelContexts.get(0).get("taskId"));
    assertEquals("ai_submission_trace_2026", org.slf4j.MDC.get("traceId"));
    assertNull(org.slf4j.MDC.get("taskId"));
  }
}

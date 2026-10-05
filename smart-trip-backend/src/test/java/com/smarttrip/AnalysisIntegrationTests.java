package com.smarttrip;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.smarttrip.common.*;
import com.smarttrip.config.AiProperties;
import com.smarttrip.entity.*;
import com.smarttrip.service.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
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
@Import(AnalysisIntegrationTests.Fixtures.class)
class AnalysisIntegrationTests {
  static AtomicInteger calls = new AtomicInteger();

  @TestConfiguration
  static class Fixtures {
    @Bean
    @Primary
    AiProvider provider() {
      return new AiProvider(new AiProperties()) {
        @Override
        public boolean available() {
          return true;
        }

        @Override
        public String complete(String input) {
          calls.incrementAndGet();
          try {
            var mapper = new ObjectMapper();
            var payload =
                mapper.readTree(input.substring(input.indexOf('{'), input.indexOf("\n已查询依据：")));
            var out = mapper.createObjectNode();
            var days = out.putArray("days");
            var reasons = out.putArray("reasons");
            boolean invalid = input.contains("INVALID_ADJUST_FIXTURE");
            for (var d : payload.path("base").path("days")) {
              boolean selected = false;
              for (var id : payload.path("dayIds")) if (id.equals(d.path("id"))) selected = true;
              if (!selected) continue;
              var day = days.addObject();
              day.put("dayId", d.path("id").asLong());
              var items = day.putArray("items");
              int n = 0;
              for (var i : d.path("items")) {
                var row = items.addObject();
                row.put("itemId", i.path("id").asLong());
                if (n++ == 1) {
                  row.put("startTime", "11:00");
                  var reason = reasons.addObject();
                  reason.putArray("itemIds").add(i.path("id").asLong());
                  reason
                      .putArray("evidenceIds")
                      .add(payload.path("issues").get(0).path("id").asText());
                  reason.put("text", "错开两个安排的时间");
                }
                if (invalid) {
                  items.add(row.deepCopy());
                  break;
                }
              }
            }
            return out.toString();
          } catch (Exception e) {
            throw new BusinessException(502, "固定响应生成失败");
          }
        }
      };
    }
  }

  @Autowired WorkbenchService trips;
  @Autowired UserService users;
  @Autowired ItineraryAnalysisService analyses;
  @Autowired CommunityService community;
  @Autowired AiTaskService tasks;
  @Autowired AiTaskStore store;
  @Autowired JdbcTemplate db;
  Long user;
  TripDetailVO base;

  @BeforeEach
  void setup() {
    var register = new RegisterDTO();
    register.setUsername("analysis_test_" + System.nanoTime());
    register.setPassword("AnalysisTest2026!");
    register.setNickname("诊断测试游客");
    user = users.register(register);
    UserContext.setUserId(user);
    assertEquals("smart_trip_test", db.queryForObject("SELECT DATABASE()", String.class));
    base =
        trips.create(
            trips.decode(
                "{\"title\":\"诊断测试\",\"destination\":\"成都\",\"days\":[{\"items\":[{\"itemName\":\"公园\",\"itemType\":\"SCENIC\",\"startTime\":\"09:00\",\"costTimeHours\":2,\"costEstimate\":50,\"poiId\":\"B00001\",\"environmentType\":\"OUTDOOR\",\"aiTips\":\"保留备注\"},{\"itemName\":\"博物馆\",\"itemType\":\"SCENIC\",\"startTime\":\"10:00\",\"costTimeHours\":1,\"costEstimate\":0,\"environmentType\":\"INDOOR\"}]}]}"));
  }

  @AfterEach
  void cleanup() {
    UserContext.clear();
  }

  ItineraryAnalysisService.Report report() {
    return analyses.create(
        base.getId(), base.getVersion(), List.of(base.getDays().get(0).getId()), "walking");
  }

  Map<String, Object> await(String id) throws Exception {
    for (int n = 0; n < 200; n++) {
      var t = store.get(id);
      if (!Set.of("QUEUED", "RUNNING").contains(t.get("status"))) return t;
      Thread.sleep(25);
    }
    fail("task timed out");
    return null;
  }

  String submit(ItineraryAnalysisService.Report r) throws Exception {
    var t = tasks.submitAdjustment(UUID.randomUUID().toString(), base.getId(), r.id());
    String id = (String) t.get("id");
    await(id);
    return id;
  }

  @Test
  void proposalRequiresConfirmationThenPreservesIdsExpensesAndHistory() throws Exception {
    Long itemId = base.getDays().get(0).getItems().get(1).getId();
    db.update(
        "INSERT INTO"
            + " trip_expense(trip_id,user_id,category,expense_name,amount,expense_date,source_item_id)"
            + " VALUES(?,?,'OTHER','关联记账',12,CURRENT_DATE,?)",
        base.getId(),
        user,
        itemId);
    String id = submit(report());
    var t = store.get(id);
    assertEquals("PREVIEW", t.get("status"));
    assertNotNull(t.get("proposal"));
    assertEquals(
        "10:00", trips.load(base.getId()).getDays().get(0).getItems().get(1).getStartTime());
    var saved = store.apply(id);
    assertEquals("11:00", saved.getDays().get(0).getItems().get(1).getStartTime());
    assertEquals(itemId, saved.getDays().get(0).getItems().get(1).getId());
    assertEquals("B00001", saved.getDays().get(0).getItems().get(0).getPoiId());
    assertEquals("OUTDOOR", saved.getDays().get(0).getItems().get(0).getEnvironmentType());
    assertEquals(
        itemId,
        db.queryForObject(
            "SELECT source_item_id FROM trip_expense WHERE trip_id=?", Long.class, base.getId()));
    assertEquals(1, trips.revisions(base.getId()).size());
    assertEquals(saved.getVersion(), store.apply(id).getVersion());
    assertEquals(1, trips.revisions(base.getId()).size());
  }

  @Test
  void concurrentTripChangeKeepsProposalButCannotOverwrite() throws Exception {
    String id = submit(report());
    var newer = trips.load(base.getId());
    newer.setTitle("并发修改");
    trips.save(base.getId(), newer);
    assertEquals(409, assertThrows(BusinessException.class, () -> store.apply(id)).getCode());
    assertEquals("PREVIEW", store.get(id).get("status"));
    assertEquals("并发修改", trips.load(base.getId()).getTitle());
  }

  @Test
  void invalidModelRefundsQuotaAndNeverSavesPartialTrip() throws Exception {
    base.setSummary("INVALID_ADJUST_FIXTURE");
    base = trips.save(base.getId(), base);
    String id = submit(report());
    assertEquals("FAILED", store.get(id).get("status"));
    assertEquals(
        "10:00", trips.load(base.getId()).getDays().get(0).getItems().get(1).getStartTime());
    assertEquals(
        0, db.queryForObject("SELECT ai_quota_used FROM sys_user WHERE id=?", Integer.class, user));
  }

  @Test
  void fixedArrangementCannotBeChangedByAi() throws Exception {
    base.getDays().get(0).getItems().get(1).setScheduleLocked(true);
    base = trips.save(base.getId(), base);
    String id = submit(report());
    assertEquals("FAILED", store.get(id).get("status"));
    assertTrue(trips.load(base.getId()).getDays().get(0).getItems().get(1).getScheduleLocked());
  }

  @Test
  void retryIsIdempotentAndDifferentInputCannotReuseKey() throws Exception {
    var r = report();
    String key = UUID.randomUUID().toString();
    int before = calls.get();
    var t = tasks.submitAdjustment(key, base.getId(), r.id());
    await((String) t.get("id"));
    assertEquals(t.get("id"), tasks.submitAdjustment(key, base.getId(), r.id()).get("id"));
    assertEquals(before + 1, calls.get());
    var newer = report();
    assertEquals(
        409,
        assertThrows(
                BusinessException.class,
                () -> tasks.submitAdjustment(key, base.getId(), newer.id()))
            .getCode());
  }

  @Test
  void expiredSourceCannotBeApplied() throws Exception {
    String id = submit(report());
    var meta = (Map<String, Object>) store.get(id).get("proposal");
    meta.put("expiresAt", "2000-01-01T00:00:00Z");
    db.update("UPDATE ai_task SET proposal_meta_json=? WHERE id=?", trips.encode(meta), id);
    assertEquals(409, assertThrows(BusinessException.class, () -> store.apply(id)).getCode());
    assertEquals(base.getVersion(), trips.load(base.getId()).getVersion());
  }

  @Test
  void revokedEditorPermissionBlocksConfirmation() throws Exception {
    var r = new RegisterDTO();
    r.setUsername("owner_" + System.nanoTime());
    r.setPassword("AnalysisTest2026!");
    r.setNickname("原所有者");
    Long owner = users.register(r);
    db.update("UPDATE trip_plan SET user_id=? WHERE id=?", owner, base.getId());
    db.update(
        "INSERT INTO trip_member(trip_id,user_id,role) VALUES(?,?,'EDITOR')", base.getId(), user);
    String id = submit(report());
    db.update(
        "UPDATE trip_member SET is_deleted=1 WHERE trip_id=? AND user_id=?", base.getId(), user);
    assertEquals(403, assertThrows(BusinessException.class, () -> store.apply(id)).getCode());
  }

  @Test
  void oldSnapshotsRestoreAndLegacyWritesKeepNewAttributes() {
    var first = base;
    var dto = trips.decode(trips.encode(base));
    dto.getDays().get(0).getItems().get(0).setScheduleLocked(true);
    base = trips.save(base.getId(), dto);
    var legacy = trips.decode(trips.encode(base));
    legacy.getDays().get(0).getItems().get(0).setEnvironmentType(null);
    legacy.getDays().get(0).getItems().get(0).setScheduleLocked(null);
    legacy.setSummary("旧客户端请求");
    base = trips.save(base.getId(), legacy);
    assertTrue(base.getDays().get(0).getItems().get(0).getScheduleLocked());
    assertEquals("OUTDOOR", base.getDays().get(0).getItems().get(0).getEnvironmentType());
    var old = trips.decode(trips.encode(first));
    old.getDays()
        .get(0)
        .getItems()
        .forEach(
            i -> {
              i.setEnvironmentType(null);
              i.setScheduleLocked(null);
            });
    db.update(
        "UPDATE trip_revision SET snapshot_json=? WHERE trip_id=? AND version=0",
        trips.encode(old),
        base.getId());
    Long revision =
        db.queryForObject(
            "SELECT id FROM trip_revision WHERE trip_id=? AND version=0", Long.class, base.getId());
    var restored = trips.restore(base.getId(), revision, base.getVersion());
    assertEquals("UNKNOWN", restored.getDays().get(0).getItems().get(0).getEnvironmentType());
    assertFalse(restored.getDays().get(0).getItems().get(0).getScheduleLocked());
  }

  @Test
  void communityCloneKeepsEnvironmentButClearsBookingLocks() {
    base.getDays().get(0).getItems().get(0).setScheduleLocked(true);
    base = trips.save(base.getId(), base);
    var post = community.publish(base.getId());
    Long postId = ((Number) post.get("id")).longValue();
    var cloned = community.clonePost(postId);
    assertEquals("OUTDOOR", cloned.getDays().get(0).getItems().get(0).getEnvironmentType());
    assertFalse(cloned.getDays().get(0).getItems().get(0).getScheduleLocked());
  }

  @Test
  void viewerMayDiagnoseButCannotGenerateOrApplyAnotherUsersTask() throws Exception {
    String id = submit(report());
    var r = new RegisterDTO();
    r.setUsername("viewer_" + System.nanoTime());
    r.setPassword("AnalysisTest2026!");
    r.setNickname("只读成员");
    Long viewer = users.register(r);
    db.update(
        "INSERT INTO trip_member(trip_id,user_id,role) VALUES(?,?,'VIEWER')", base.getId(), viewer);
    UserContext.setUserId(viewer);
    var analysis = report();
    assertFalse(analysis.issues().isEmpty());
    assertEquals(
        403,
        assertThrows(
                BusinessException.class,
                () ->
                    tasks.submitAdjustment(
                        UUID.randomUUID().toString(), base.getId(), analysis.id()))
            .getCode());
    assertEquals(404, assertThrows(BusinessException.class, () -> store.apply(id)).getCode());
  }

  @Test
  void qualityComparisonSurvivesPreviewPersistenceAndIsRecheckedOnConfirmation() throws Exception {
    String id = submit(report());
    var preview = store.get(id);
    var meta = (Map<?, ?>) preview.get("proposal");
    var comparison = (Map<?, ?>) meta.get("qualityComparison");
    assertNotNull(comparison);
    assertEquals(2, ((Number) comparison.get("schemaVersion")).intValue());
    var saved = store.apply(id);
    assertEquals(
        "11:00", trips.load(saved.getId()).getDays().get(0).getItems().get(1).getStartTime());
  }

  @Test
  void tamperedCandidateCannotBypassConfirmationValidation() throws Exception {
    String id = submit(report());
    var result =
        (String) db.queryForObject("SELECT result_json FROM ai_task WHERE id=?", String.class, id);
    var candidate = trips.decode(result);
    candidate.getDays().get(0).getItems().get(1).setCostEstimate(new java.math.BigDecimal("999"));
    db.update("UPDATE ai_task SET result_json=? WHERE id=?", trips.encode(candidate), id);
    assertEquals(422, assertThrows(BusinessException.class, () -> store.apply(id)).getCode());
    assertEquals("PREVIEW", store.get(id).get("status"));
    assertEquals(
        "10:00", trips.load(base.getId()).getDays().get(0).getItems().get(1).getStartTime());
  }

  @Test
  void storedLegacyTaskCanStillConfirmWithoutV2Metadata() throws Exception {
    String id = submit(report());
    var input =
        (String) db.queryForObject("SELECT input_json FROM ai_task WHERE id=?", String.class, id);
    var mapper = new ObjectMapper();
    var legacy = (ObjectNode) mapper.readTree(input);
    legacy.put("schemaVersion", 1);
    legacy.remove(List.of("quality", "policy", "sources"));
    var meta =
        (ObjectNode)
            mapper.readTree(
                (String)
                    db.queryForObject(
                        "SELECT proposal_meta_json FROM ai_task WHERE id=?", String.class, id));
    meta.remove("qualityComparison");
    meta.putNull("expiresAt");
    db.update(
        "UPDATE ai_task SET input_json=?,proposal_meta_json=? WHERE id=?",
        legacy.toString(),
        meta.toString(),
        id);
    assertFalse(((Map<?, ?>) store.get(id).get("proposal")).containsKey("qualityComparison"));
    assertNotNull(store.apply(id));
  }
}

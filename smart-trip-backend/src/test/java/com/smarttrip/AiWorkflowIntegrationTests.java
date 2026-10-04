package com.smarttrip;

import static org.junit.jupiter.api.Assertions.*;

import com.smarttrip.common.*;
import com.smarttrip.config.AiProperties;
import com.smarttrip.entity.*;
import com.smarttrip.service.*;
import java.util.*;
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
    UserContext.clear();
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
    assertEquals(
        1,
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
}

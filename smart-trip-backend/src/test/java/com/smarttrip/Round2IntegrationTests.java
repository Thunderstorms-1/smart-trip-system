package com.smarttrip;

import static org.junit.jupiter.api.Assertions.*;

import com.smarttrip.common.*;
import com.smarttrip.entity.*;
import com.smarttrip.service.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfEnvironmentVariable(named = "SMARTTRIP_INTEGRATION", matches = "true")
@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:mysql://localhost:3306/smart_trip_test?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai",
      "security.jwt-secret=test-only-jwt-secret-at-least-32-bytes",
      "ai.llm.enabled=false",
      "geo.enabled=false"
    })
class Round2IntegrationTests {
  @Autowired WorkbenchService trips;
  @Autowired TripService legacy;
  @Autowired CommunityService community;
  @Autowired AiTaskStore tasks;
  @Autowired MembershipService membership;
  @Autowired UserService users;
  @Autowired JdbcTemplate db;
  @Autowired PlatformTransactionManager tm;
  Long user;

  Long account() {
    var r = new RegisterDTO();
    r.setUsername("round2_" + System.nanoTime());
    r.setNickname("测试游客");
    r.setPassword("Round2Test2026!");
    return users.register(r);
  }

  @BeforeEach
  void setup() {
    assertEquals("smart_trip_test", db.queryForObject("SELECT DATABASE()", String.class));
    user = account();
    UserContext.setUserId(user);
  }

  @AfterEach
  void clear() {
    UserContext.clear();
  }

  TripDetailVO draft(int days, int nodes) {
    var t = new TripDetailVO();
    t.setTitle("事务测试路书");
    t.setDestination("成都");
    t.setStartDate(LocalDate.of(2026, 10, 10));
    t.setDays(new ArrayList<>());
    for (int n = 0; n < days; n++) {
      var d = new TripDayVO();
      d.setDayTheme("第" + n + "天");
      d.setItems(new ArrayList<>());
      for (int k = 0; k < nodes; k++) {
        var i = new TripItem();
        i.setItemName("地点" + n + "-" + k);
        i.setCostEstimate(new BigDecimal("12.34"));
        d.getItems().add(i);
      }
      t.getDays().add(d);
    }
    return t;
  }

  long status(String name) {
    return Long.parseLong(
        String.valueOf(db.queryForMap("SHOW SESSION STATUS LIKE '" + name + "'").get("Value")));
  }

  long writes() {
    return status("Com_insert") + status("Com_update") + status("Com_delete");
  }

  @Test
  void singleRenameHasConstantWriteCountAndNoOpDoesNotCreateHistory() {
    var initial = trips.create(draft(20, 20));
    var tx = new TransactionTemplate(tm);
    tx.executeWithoutResult(
        s -> {
          var input = trips.load(initial.getId());
          long before = writes();
          input.getDays().get(0).getItems().get(0).setItemName("新名称");
          var saved = trips.save(input.getId(), input);
          long used = writes() - before;
          assertEquals(3, used, "history, header version and changed item only");
          assertEquals(1L, saved.getVersion());
          long again = writes();
          var unchanged = trips.save(saved.getId(), saved);
          assertEquals(again, writes());
          assertEquals(saved.getVersion(), unchanged.getVersion());
          assertEquals(1, trips.revisions(saved.getId()).size());
          long reads = status("Com_select");
          var loaded = trips.load(saved.getId());
          assertEquals(3, status("Com_select") - reads, "header, days and all items");
          assertEquals(400, loaded.getDays().stream().mapToInt(d -> d.getItems().size()).sum());
        });
  }

  @Test
  void moveDatesAndRestorePreserveIdsAndActualExpenses() {
    var t = trips.create(draft(2, 1));
    var original = trips.encode(t);
    Long item = t.getDays().get(0).getItems().get(0).getId();
    Long firstDay = t.getDays().get(0).getId();
    db.update(
        "INSERT INTO"
            + " trip_expense(trip_id,user_id,category,amount,expense_name,expense_date,source_item_id)"
            + " VALUES(?,?,'FOOD',5,'实际费用',CURRENT_DATE,?)",
        t.getId(),
        user,
        item);
    t.getDays().get(1).getItems().add(t.getDays().get(0).getItems().remove(0));
    t.setStartDate(t.getStartDate().plusDays(2));
    t = trips.save(t.getId(), t);
    assertEquals(item, t.getDays().get(1).getItems().get(1).getId());
    assertEquals(LocalDate.of(2026, 10, 13), t.getDays().get(1).getDayDate());
    var revision = trips.revisions(t.getId()).get(0);
    t = trips.restore(t.getId(), ((Number) revision.get("id")).longValue(), t.getVersion());
    assertEquals(firstDay, t.getDays().get(0).getId());
    assertEquals(item, t.getDays().get(0).getItems().get(0).getId());
    t.getDays().remove(0);
    t = trips.save(t.getId(), t);
    assertEquals(
        1, db.queryForObject("SELECT is_deleted FROM trip_item WHERE id=?", Integer.class, item));
    var oldRevision =
        trips.revisions(t.getId()).stream()
            .filter(r -> ((Number) r.get("version")).longValue() == 0)
            .findFirst()
            .orElseThrow();
    t = trips.restore(t.getId(), ((Number) oldRevision.get("id")).longValue(), t.getVersion());
    assertEquals(firstDay, t.getDays().get(0).getId());
    assertEquals(item, t.getDays().get(0).getItems().get(0).getId());
    assertEquals(
        1,
        db.queryForObject(
            "SELECT COUNT(*) FROM trip_expense WHERE trip_id=? AND source_item_id=? AND"
                + " is_deleted=0",
            Integer.class,
            t.getId(),
            item));
    assertEquals(trips.decode(original).getTotalBudget(), t.getTotalBudget());
  }

  @Test
  void staleAndForeignIdentifiersDoNotMutateHistory() {
    var a = trips.create(draft(1, 1));
    var b = trips.create(draft(1, 1));
    var stale = trips.decode(trips.encode(a));
    a.setTitle("已更新");
    a = trips.save(a.getId(), a);
    assertEquals(
        409,
        assertThrows(BusinessException.class, () -> trips.save(stale.getId(), stale)).getCode());
    a.getDays().get(0).getItems().get(0).setId(b.getDays().get(0).getItems().get(0).getId());
    final var bad = a;
    assertEquals(
        403, assertThrows(BusinessException.class, () -> trips.save(bad.getId(), bad)).getCode());
    assertEquals(1, trips.revisions(a.getId()).size());
  }

  @Test
  void failureAfterHeaderAndHistoryRollsBackWholeSave() {
    var t = trips.create(draft(1, 1));
    String marker = "FAIL_ROUND2_" + System.nanoTime();
    String trigger = "round2_fail_" + System.nanoTime();
    // Failure injection is confined to the asserted isolated test schema and removed in finally.
    db.execute(
        "CREATE TRIGGER "
            + trigger
            + " BEFORE UPDATE ON trip_item FOR EACH ROW BEGIN IF NEW.item_name='"
            + marker
            + "' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='fixture abort'; END IF; END");
    try {
      t.setTitle("不应提交");
      t.getDays().get(0).getItems().get(0).setItemName(marker);
      assertThrows(Exception.class, () -> trips.save(t.getId(), t));
      var actual = trips.load(t.getId());
      assertEquals(0L, actual.getVersion());
      assertEquals("事务测试路书", actual.getTitle());
      assertTrue(trips.revisions(t.getId()).isEmpty());
    } finally {
      db.execute("DROP TRIGGER " + trigger);
    }
  }

  @Test
  void legacyWritesAreRetiredWithoutChangingVersion() {
    var t = trips.create(draft(1, 1));
    assertEquals(
        410, assertThrows(BusinessException.class, () -> legacy.addTripDay(t.getId())).getCode());
    assertEquals(
        410,
        assertThrows(
                BusinessException.class,
                () -> legacy.deleteTripItem(t.getDays().get(0).getItems().get(0).getId()))
            .getCode());
    assertEquals(0L, trips.load(t.getId()).getVersion());
  }

  @Test
  void quotaRefundIsDateScopedAndIdempotent() {
    db.update("UPDATE sys_user SET ai_quota_used=3,quota_reset_date=CURRENT_DATE WHERE id=?", user);
    String old = UUID.randomUUID().toString(), today = UUID.randomUUID().toString();
    for (String id : List.of(old, today))
      db.update(
          "INSERT INTO"
              + " ai_task(id,user_id,request_key,task_type,status,input_json,quota_reserved,quota_reserved_date)"
              + " VALUES(?,?,?,'GENERATE','RUNNING','fixture',1,?)",
          id,
          user,
          id,
          LocalDate.now().minusDays(id.equals(old) ? 1 : 0));
    tasks.fail(old, "旧日失败");
    assertEquals(
        3, db.queryForObject("SELECT ai_quota_used FROM sys_user WHERE id=?", Integer.class, user));
    tasks.fail(today, "今日失败");
    tasks.fail(today, "重复失败");
    assertEquals(
        2, db.queryForObject("SELECT ai_quota_used FROM sys_user WHERE id=?", Integer.class, user));
  }

  @Test
  void cursorPagingHasStableTiesAndRejectsDifferentSearch() {
    for (int n = 0; n < 23; n++) community.publish(trips.create(draft(1, 0)).getId());
    var first = community.feed("", user, null);
    assertEquals(20, first.items().size());
    assertTrue(first.hasMore());
    var next = community.feed("", user, first.nextCursor());
    assertEquals(3, next.items().size());
    assertFalse(next.hasMore());
    Set<Long> ids = new HashSet<>();
    first.items().forEach(p -> ids.add(p.getId()));
    next.items().forEach(p -> assertTrue(ids.add(p.getId())));
    assertEquals(23, ids.size());
    assertEquals(
        400,
        assertThrows(BusinessException.class, () -> community.feed("其他", user, first.nextCursor()))
            .getCode());
  }

  @Test
  void memberRemovalSerializesWithPendingSave() throws Exception {
    var t = trips.create(draft(1, 1));
    Long editor = account();
    db.update(
        "INSERT INTO trip_member(trip_id,user_id,role) VALUES(?,?,'EDITOR')", t.getId(), editor);
    ExecutorService worker = Executors.newSingleThreadExecutor();
    var started = new CountDownLatch(1);
    final Future<?>[] pending = new Future<?>[1];
    try {
      new TransactionTemplate(tm)
          .executeWithoutResult(
              s -> {
                db.queryForObject(
                    "SELECT id FROM trip_plan WHERE id=? FOR UPDATE", Long.class, t.getId());
                pending[0] =
                    worker.submit(
                        () -> {
                          UserContext.setUserId(editor);
                          try {
                            started.countDown();
                            t.setTitle("已移除成员的保存");
                            trips.save(t.getId(), t);
                          } finally {
                            UserContext.clear();
                          }
                        });
                try {
                  assertTrue(started.await(3, TimeUnit.SECONDS));
                  assertThrows(
                      TimeoutException.class, () -> pending[0].get(100, TimeUnit.MILLISECONDS));
                } catch (InterruptedException e) {
                  throw new RuntimeException(e);
                }
                membership.remove(t.getId(), editor);
              });
      var failure =
          assertThrows(ExecutionException.class, () -> pending[0].get(5, TimeUnit.SECONDS));
      assertInstanceOf(BusinessException.class, failure.getCause());
      assertEquals(403, ((BusinessException) failure.getCause()).getCode());
      assertEquals(0L, trips.load(t.getId()).getVersion());
    } finally {
      worker.shutdownNow();
    }
  }
  @Test
  void likedAndFavoriteAssetsHideDisabledAuthors() {
    assertAssetsHideInactiveAuthor(0, 0);
  }

  @Test
  void likedAndFavoriteAssetsHideDeletedAuthors() {
    assertAssetsHideInactiveAuthor(1, 1);
  }

  void assertAssetsHideInactiveAuthor(int authorStatus, int authorDeleted) {
    new TransactionTemplate(tm).executeWithoutResult(transaction -> {
      try {
        Long author = account();
        UserContext.setUserId(author);
        long hiddenPost = ((Number) community.publish(trips.create(draft(1, 0)).getId()).get("id")).longValue();
        UserContext.setUserId(user);
        long visiblePost = ((Number) community.publish(trips.create(draft(1, 0)).getId()).get("id")).longValue();
        for (String type : List.of("LIKE", "FAVORITE")) {
          community.interact(hiddenPost, type, true);
          community.interact(visiblePost, type, true);
          assertEquals(2, community.assets(type).size());
        }
        db.update("UPDATE sys_user SET status=?,is_deleted=? WHERE id=?", authorStatus, authorDeleted, author);
        for (String type : List.of("LIKE", "FAVORITE")) {
          var assets = community.assets(type);
          assertEquals(1, assets.size());
          assertEquals(visiblePost, ((Number) assets.get(0).get("id")).longValue());
        }
        assertEquals(404, assertThrows(BusinessException.class, () -> community.detail(hiddenPost)).getCode());
        assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM post_interaction WHERE user_id=? AND post_id=?", Integer.class, user, hiddenPost));
        db.update("UPDATE sys_user SET status=1,is_deleted=0 WHERE id=?", author);
        for (String type : List.of("LIKE", "FAVORITE")) assertEquals(2, community.assets(type).size());
      } finally {
        UserContext.setUserId(user);
        transaction.setRollbackOnly();
      }
    });
  }
}

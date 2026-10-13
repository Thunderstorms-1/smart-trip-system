package com.smarttrip;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.smarttrip.common.*;
import com.smarttrip.controller.*;
import com.smarttrip.entity.*;
import com.smarttrip.service.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
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
class TravelToolsIntegrityTests {
  @Autowired TravelToolsService tools;
  @Autowired ToolService legacy;
  @Autowired ToolController legacyController;
  @Autowired TravelToolsController controller;
  @Autowired WorkbenchService trips;
  @Autowired MembershipService members;
  @Autowired UserService users;
  @Autowired JdbcTemplate db;
  @Autowired PlatformTransactionManager tm;
  Long owner, editor, trip, expenseId, packingId;
  MockMvc mvc;

  Long account() {
    var r = new RegisterDTO();
    r.setUsername("tools_" + System.nanoTime());
    r.setPassword("ToolsTest2026!");
    return users.register(r);
  }

  TripExpense expense() {
    var e = new TripExpense();
    e.setExpenseName("真实支出");
    e.setExpenseDate(LocalDate.of(2026, 10, 13));
    e.setAmount(new BigDecimal("12.345"));
    e.setCategory("OTHER");
    e.setRemark("原始备注");
    return e;
  }

  String key() {
    return UUID.randomUUID().toString();
  }

  @BeforeEach
  void setup() {
    assertEquals("smart_trip_test", db.queryForObject("SELECT DATABASE()", String.class));
    owner = account();
    editor = account();
    UserContext.setUserId(owner);
    trip =
        trips
            .create(
                trips.decode(
                    """
                    {"title":"工具并发测试","destination":"成都","days":[{"items":[
                    {"itemName":"公园","itemType":"SCENIC","costEstimate":0},
                    {"itemName":"博物馆","itemType":"SCENIC","costEstimate":0}]}]}
                    """))
            .getId();
    tools.add(trip, expense(), key(), null);
    expenseId = db.queryForObject("SELECT id FROM trip_expense WHERE trip_id=?", Long.class, trip);
    tools.addPacking(trip, "测试物品");
    packingId =
        db.queryForObject("SELECT id FROM trip_packing_item WHERE trip_id=?", Long.class, trip);
    db.update("INSERT INTO trip_member(trip_id,user_id,role) VALUES(?,?,'EDITOR')", trip, editor);
    mvc =
        MockMvcBuilders.standaloneSetup(legacyController, controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @AfterEach
  void clear() {
    UserContext.clear();
  }

  @Test
  void oldAndNewHttpEntrancesShareIdentityAndIgnoreSpoofedOwnership() throws Exception {
    var e = expense();
    e.setUserId(editor);
    e.setTripId(-1L);
    e.setId(expenseId);
    e.setIsDeleted(1);
    String requestKey = key();
    mvc.perform(
            post("/api/trip/" + trip + "/expense")
                .contentType("application/json")
                .header("Idempotency-Key", requestKey)
                .content(trips.encode(e)))
        .andExpect(status().isOk());
    mvc.perform(
            post("/api/tools/trips/" + trip + "/expenses")
                .contentType("application/json")
                .content(trips.encode(new TravelToolsController.ExpenseInput(e, requestKey, null))))
        .andExpect(status().isOk());
    assertEquals(
        2,
        db.queryForObject(
            "SELECT COUNT(*) FROM trip_expense WHERE trip_id=?", Integer.class, trip));
    assertEquals(
        owner,
        db.queryForObject(
            "SELECT user_id FROM trip_expense WHERE trip_id=? AND request_key=?",
            Long.class,
            trip,
            requestKey));
    mvc.perform(
            post("/api/trip/" + trip + "/expense")
                .contentType("application/json")
                .content(trips.encode(e)))
        .andExpect(status().isBadRequest());
    var invalid = expense();
    invalid.setCategory("UNKNOWN");
    for (String url :
        List.of("/api/trip/" + trip + "/expense", "/api/tools/trips/" + trip + "/expenses")) {
      boolean old = url.startsWith("/api/trip/");
      mvc.perform(
              post(url)
                  .contentType("application/json")
                  .header("Idempotency-Key", key())
                  .content(
                      trips.encode(
                          old
                              ? invalid
                              : new TravelToolsController.ExpenseInput(invalid, key(), null))))
          .andExpect(status().isBadRequest());
    }
    var unkeyed = expense();
    unkeyed.setTripId(trip);
    assertEquals(
        410, assertThrows(BusinessException.class, () -> legacy.addExpense(unkeyed)).getCode());
    assertEquals(
        409,
        assertThrows(BusinessException.class, () -> legacy.importExpensesFromTrip(trip)).getCode());
  }

  @Test
  void sameKeyCannotChangeAnyRequestFieldOrUser() {
    Long source = trips.load(trip).getDays().get(0).getItems().get(0).getId();
    String requestKey = key();
    tools.add(trip, expense(), requestKey, source);
    var changed = expense();
    changed.setRemark("不同备注");
    assertEquals(
        409,
        assertThrows(BusinessException.class, () -> tools.add(trip, changed, requestKey, source))
            .getCode());
    assertEquals(
        409,
        assertThrows(BusinessException.class, () -> tools.add(trip, expense(), requestKey, null))
            .getCode());
    var differentAmount = expense();
    differentAmount.setAmount(BigDecimal.ONE);
    assertEquals(
        409,
        assertThrows(
                BusinessException.class, () -> tools.add(trip, differentAmount, requestKey, source))
            .getCode());
    UserContext.setUserId(editor);
    assertEquals(
        409,
        assertThrows(BusinessException.class, () -> tools.add(trip, expense(), requestKey, source))
            .getCode());
  }

  @Test
  void originalRetryDoesNotUndoLaterEditDeletionOrRequireRemovedSource() {
    Long source = trips.load(trip).getDays().get(0).getItems().get(0).getId();
    String requestKey = key();
    tools.add(trip, expense(), requestKey, source);
    Long id =
        db.queryForObject(
            "SELECT id FROM trip_expense WHERE trip_id=? AND request_key=?",
            Long.class,
            trip,
            requestKey);
    String hash =
        db.queryForObject("SELECT request_hash FROM trip_expense WHERE id=?", String.class, id);
    var edited = expense();
    edited.setExpenseName("已编辑支出");
    tools.edit(id, edited);
    tools.add(trip, expense(), requestKey, source);
    assertEquals(
        "已编辑支出",
        db.queryForObject("SELECT expense_name FROM trip_expense WHERE id=?", String.class, id));
    tools.deleteExpense(id);
    db.update("UPDATE trip_item SET is_deleted=1 WHERE id=?", source);
    tools.add(trip, expense(), requestKey, source);
    assertEquals(
        1, db.queryForObject("SELECT is_deleted FROM trip_expense WHERE id=?", Integer.class, id));
    assertEquals(
        hash,
        db.queryForObject("SELECT request_hash FROM trip_expense WHERE id=?", String.class, id));
    assertEquals(
        1,
        db.queryForObject(
            "SELECT COUNT(*) FROM trip_expense WHERE trip_id=? AND request_key=?",
            Integer.class,
            trip,
            requestKey));
  }

  @Test
  void legacyRowsReceiveFingerprintOnlyAfterFullMatchingRetry() {
    String requestKey = key();
    tools.add(trip, expense(), requestKey, null);
    db.update(
        "UPDATE trip_expense SET request_hash=NULL WHERE trip_id=? AND request_key=?",
        trip,
        requestKey);
    var changed = expense();
    changed.setRemark("不同备注");
    assertEquals(
        409,
        assertThrows(BusinessException.class, () -> tools.add(trip, changed, requestKey, null))
            .getCode());
    tools.add(trip, expense(), requestKey, null);
    assertNotNull(
        db.queryForObject(
            "SELECT request_hash FROM trip_expense WHERE trip_id=? AND request_key=?",
            String.class,
            trip,
            requestKey));
  }

  @Test
  void zeroIsValidAndForeignSourcesAreRejectedWithoutInserting() {
    var zero = expense();
    zero.setAmount(BigDecimal.ZERO);
    tools.add(trip, zero, key(), null);
    var foreign = trips.create(trips.decode(trips.encode(trips.load(trip))));
    Long item = foreign.getDays().get(0).getItems().get(0).getId();
    assertEquals(
        403,
        assertThrows(BusinessException.class, () -> tools.add(trip, expense(), key(), item))
            .getCode());
    for (String invalid : List.of("-1", "10000000")) {
      var e = expense();
      e.setAmount(new BigDecimal(invalid));
      assertEquals(
          400,
          assertThrows(BusinessException.class, () -> tools.add(trip, e, key(), null)).getCode());
    }
    var e = expense();
    e.setRemark("x".repeat(256));
    assertEquals(
        400,
        assertThrows(BusinessException.class, () -> tools.add(trip, e, key(), null)).getCode());
    assertEquals(
        2,
        db.queryForObject(
            "SELECT COUNT(*) FROM trip_expense WHERE trip_id=?", Integer.class, trip));
  }

  @Test
  void concurrentCreationCommitsExactlyOneBill() throws Exception {
    String requestKey = key();
    var pool = Executors.newFixedThreadPool(8);
    var start = new CountDownLatch(1);
    try {
      List<Future<?>> jobs = new ArrayList<>();
      for (int n = 0; n < 8; n++)
        jobs.add(
            pool.submit(
                () -> {
                  UserContext.setUserId(owner);
                  try {
                    start.await();
                    tools.add(trip, expense(), requestKey, null);
                  } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                  } finally {
                    UserContext.clear();
                  }
                }));
      start.countDown();
      for (var job : jobs) job.get(10, TimeUnit.SECONDS);
      assertEquals(
          1,
          db.queryForObject(
              "SELECT COUNT(*) FROM trip_expense WHERE trip_id=? AND request_key=?",
              Integer.class,
              trip,
              requestKey));
    } finally {
      pool.shutdownNow();
    }
  }

  enum Write {
    ADD_EXPENSE,
    EDIT_EXPENSE,
    DELETE_EXPENSE,
    ADD_PACKING,
    TOGGLE,
    DELETE_PACKING,
    TEMPLATE,
    LEGACY_TOGGLE,
    LEGACY_ADD
  }

  void write(Write operation) {
    switch (operation) {
      case ADD_EXPENSE -> tools.add(trip, expense(), key(), null);
      case EDIT_EXPENSE -> {
        var e = expense();
        e.setExpenseName("不应保存");
        tools.edit(expenseId, e);
      }
      case DELETE_EXPENSE -> tools.deleteExpense(expenseId);
      case ADD_PACKING -> tools.addPacking(trip, "不应添加");
      case TOGGLE -> tools.packingItem(packingId, true, false);
      case DELETE_PACKING -> tools.packingItem(packingId, null, true);
      case TEMPLATE -> tools.template(trip);
      case LEGACY_TOGGLE -> legacy.togglePackingItem(packingId, 1);
      case LEGACY_ADD -> legacyController.addExpense(trip, expense(), key(), null);
    }
  }

  @ParameterizedTest
  @EnumSource(Write.class)
  void removalBeforePendingWriteBlocksEveryModernAndLegacyMutation(Write operation)
      throws Exception {
    var pool = Executors.newSingleThreadExecutor();
    var started = new CountDownLatch(1);
    final Future<?>[] pending = new Future<?>[1];
    try {
      new TransactionTemplate(tm)
          .executeWithoutResult(
              tx -> {
                db.queryForObject(
                    "SELECT id FROM trip_plan WHERE id=? FOR UPDATE", Long.class, trip);
                pending[0] =
                    pool.submit(
                        () -> {
                          UserContext.setUserId(editor);
                          try {
                            started.countDown();
                            write(operation);
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
                members.remove(trip, editor);
              });
      var failure =
          assertThrows(ExecutionException.class, () -> pending[0].get(5, TimeUnit.SECONDS));
      assertEquals(403, ((BusinessException) failure.getCause()).getCode());
      assertEquals(
          1,
          db.queryForObject(
              "SELECT COUNT(*) FROM trip_expense WHERE trip_id=? AND is_deleted=0",
              Integer.class,
              trip));
      assertEquals(
          "真实支出",
          db.queryForObject(
              "SELECT expense_name FROM trip_expense WHERE id=?", String.class, expenseId));
      assertEquals(
          1,
          db.queryForObject(
              "SELECT COUNT(*) FROM trip_packing_item WHERE trip_id=? AND is_deleted=0",
              Integer.class,
              trip));
      assertEquals(
          0,
          db.queryForObject(
              "SELECT is_checked FROM trip_packing_item WHERE id=?", Integer.class, packingId));
    } finally {
      pool.shutdownNow();
    }
  }

  @ParameterizedTest
  @EnumSource(Write.class)
  void viewersCannotMutateTools(Write operation) {
    db.update("UPDATE trip_member SET role='VIEWER' WHERE trip_id=? AND user_id=?", trip, editor);
    UserContext.setUserId(editor);
    assertEquals(403, assertThrows(BusinessException.class, () -> write(operation)).getCode());
  }

  @Test
  void writeThatOwnsLockMayCommitBeforeRemovalButLaterWritesAreDenied() throws Exception {
    var pool = Executors.newSingleThreadExecutor();
    var started = new CountDownLatch(1);
    final Future<?>[] pending = new Future<?>[1];
    try {
      UserContext.setUserId(editor);
      new TransactionTemplate(tm)
          .executeWithoutResult(
              tx -> {
                tools.add(trip, expense(), key(), null);
                pending[0] =
                    pool.submit(
                        () -> {
                          UserContext.setUserId(owner);
                          try {
                            started.countDown();
                            members.remove(trip, editor);
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
              });
      pending[0].get(5, TimeUnit.SECONDS);
      assertEquals(
          403,
          assertThrows(BusinessException.class, () -> tools.add(trip, expense(), key(), null))
              .getCode());
      assertEquals(
          2,
          db.queryForObject(
              "SELECT COUNT(*) FROM trip_expense WHERE trip_id=? AND is_deleted=0",
              Integer.class,
              trip));
    } finally {
      pool.shutdownNow();
      UserContext.setUserId(owner);
    }
  }

  @Test
  void templateIsRepeatableAndLegacyToggleSharesSavedState() {
    tools.template(trip);
    tools.template(trip);
    assertEquals(5, tools.packing(trip).size());
    legacy.togglePackingItem(packingId, 1);
    assertEquals(
        1,
        db.queryForObject(
            "SELECT is_checked FROM trip_packing_item WHERE id=?", Integer.class, packingId));
    assertEquals(
        400,
        assertThrows(BusinessException.class, () -> legacy.togglePackingItem(packingId, 2))
            .getCode());
    tools.packingItem(packingId, false, false);
    tools.packingItem(packingId, null, true);
    assertEquals(
        404,
        assertThrows(BusinessException.class, () -> tools.packingItem(packingId, true, false))
            .getCode());
    tools.deleteExpense(expenseId);
    assertEquals(
        404,
        assertThrows(BusinessException.class, () -> tools.edit(expenseId, expense())).getCode());
  }

  enum DeletedTarget {
    EXPENSE,
    PACKING
  }

  @ParameterizedTest
  @EnumSource(DeletedTarget.class)
  void deletedTargetIsRecheckedAfterWaitingForPlanLock(DeletedTarget target) throws Exception {
    var pool = Executors.newSingleThreadExecutor();
    var started = new CountDownLatch(1);
    final Future<?>[] pending = new Future<?>[1];
    try {
      new TransactionTemplate(tm)
          .executeWithoutResult(
              tx -> {
                db.queryForObject(
                    "SELECT id FROM trip_plan WHERE id=? FOR UPDATE", Long.class, trip);
                pending[0] =
                    pool.submit(
                        () -> {
                          UserContext.setUserId(editor);
                          try {
                            started.countDown();
                            if (target == DeletedTarget.EXPENSE) tools.edit(expenseId, expense());
                            else tools.packingItem(packingId, true, false);
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
                if (target == DeletedTarget.EXPENSE) tools.deleteExpense(expenseId);
                else tools.packingItem(packingId, null, true);
              });
      var failure =
          assertThrows(ExecutionException.class, () -> pending[0].get(5, TimeUnit.SECONDS));
      assertEquals(404, ((BusinessException) failure.getCause()).getCode());
      String table = target == DeletedTarget.EXPENSE ? "trip_expense" : "trip_packing_item";
      Long id = target == DeletedTarget.EXPENSE ? expenseId : packingId;
      assertEquals(
          1,
          db.queryForObject("SELECT is_deleted FROM " + table + " WHERE id=?", Integer.class, id));
    } finally {
      pool.shutdownNow();
    }
  }
}

package com.smarttrip;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.smarttrip.common.BusinessException;
import com.smarttrip.entity.TripDetailVO;
import com.smarttrip.entity.TripWriteDTO;
import com.smarttrip.entity.TravelRequirement;
import com.smarttrip.repository.TripRepository;
import com.smarttrip.service.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;

class AiGenerationPreviewTests {
  JdbcTemplate db;
  WorkbenchService trips;
  ResourceAccess access;
  AiTaskStore store;
  Map<String, Object> row;
  TripDetailVO saved;
  TravelRequirementService requirements;

  @BeforeEach
  void setup() {
    db = mock(JdbcTemplate.class);
    access = mock(ResourceAccess.class);
    when(access.userId()).thenReturn(7L);
    trips = spy(new WorkbenchService(db, mock(TripRepository.class), access));
    requirements = new TravelRequirementService();
    store = new AiTaskStore(db, trips, access, mock(ItineraryAnalysisService.class), requirements,
        new AiDraftNormalizer(), mock(AiProvider.class));
    row = new LinkedHashMap<>();
    row.put("id", "generation-task");
    row.put("user_id", 7L);
    row.put("task_type", "GENERATE");
    row.put("status", "RUNNING");
    row.put("quota_reserved", 1);
    row.put("quota_reserved_date", java.sql.Date.valueOf(java.time.LocalDate.now()));
    row.put("proposal_meta_json", trips.encode(Map.of(
        "requirement", requirement(), "expiresAt", Instant.now().plusSeconds(86400).toString())));
    when(db.queryForMap(anyString(), any(Object[].class))).thenAnswer(i -> row);
    when(db.queryForList(anyString(), any(Object[].class))).thenAnswer(i -> {
      if (i.getArgument(0, String.class).contains("AS userId")) {
        if (!Objects.equals(access.userId(), 7L)) return List.of();
        var view = new LinkedHashMap<String, Object>();
        view.put("id", "generation-task");
        view.put("status", row.get("status"));
        view.put("type", "GENERATE");
        view.put("tripId", row.get("trip_id"));
        view.put("resultJson", row.get("result_json"));
        view.put("proposalMeta", row.get("proposal_meta_json"));
        return List.of(view);
      }
      return List.of(row);
    });
    when(db.update(anyString(), any(Object[].class))).thenAnswer(i -> {
      String sql = i.getArgument(0);
      if (sql.contains("status='PREVIEW'")) {
        row.put("status", "PREVIEW");
        row.put("result_json", i.getArgument(1));
        row.put("proposal_meta_json", i.getArgument(2));
        row.put("quota_reserved", 0);
      } else if (sql.contains("SET result_json=?,proposal_meta_json=?")) {
        row.put("result_json", i.getArgument(1));
        row.put("proposal_meta_json", i.getArgument(2));
      } else if (sql.contains("SET proposal_meta_json=? WHERE")) {
        row.put("proposal_meta_json", i.getArgument(1));
      } else if (sql.contains("status='APPLIED'")) {
        row.put("status", "APPLIED");
        row.put("trip_id", i.getArgument(1));
      }
      return 1;
    });
    saved = draft();
    saved.setId(91L);
    saved.setVersion(0L);
    doReturn(saved).when(trips).createGenerated(any());
    doReturn(saved).when(trips).load(91L);
  }

  TravelRequirement requirement() {
    return new com.fasterxml.jackson.databind.ObjectMapper().convertValue(
        Map.of("description", "去京都一天", "destinations", List.of("京都"), "days", 1,
            "currency", "CNY", "budgetScope", "TOTAL", "budget", 500), TravelRequirement.class);
  }

  TripDetailVO draft() {
    return trips.decode("""
        {"title":"京都慢游","destination":"京都","summary":"沿着哲学之道慢游京都，费用为估算。","days":[{"dayTheme":"走进小城","items":[
          {"itemName":"哲学之道","itemType":"SCENIC","startTime":"09:00","costTimeHours":2,"costEstimate":100}]}]}
        """);
  }

  @Test
  void completionPersistsRecoverablePreviewWithoutCreatingTrip() {
    store.complete("generation-task", draft());
    assertEquals("PREVIEW", row.get("status"));
    assertNull(row.get("trip_id"));
    verify(trips, never()).createGenerated(any());
    var restored = store.get("generation-task");
    assertNotNull(restored.get("result"));
    assertTrue(((Map<?, ?>) restored.get("generation")).containsKey("requirement"));
    assertEquals(0, row.get("quota_reserved"));
  }

  @Test
  void confirmationUsesLockedTaskAndCreatesExactlyOnceWithEditedTitle() {
    store.complete("generation-task", draft());
    assertEquals(91L, store.apply("generation-task", "我的京都之行").getId());
    assertEquals(91L, store.apply("generation-task", "重复请求").getId());
    var capture = org.mockito.ArgumentCaptor.forClass(TripDetailVO.class);
    verify(trips).createGenerated(capture.capture());
    assertEquals("我的京都之行", capture.getValue().getTitle());
    assertEquals("APPLIED", row.get("status"));
    verify(db, atLeastOnce()).queryForMap(contains("FOR UPDATE"), any(Object[].class));
    verify(access, never()).writeLocked(any());
  }

  @Test
  void businessInvalidDraftRemainsEditableButCannotBeApplied() {
    var bad = draft();
    bad.setDestination("东京");
    store.complete("generation-task", bad);
    var preview = store.get("generation-task");
    assertEquals("PREVIEW", preview.get("status"));
    assertIssue(preview, "DESTINATION");
    assertEquals(422, assertThrows(BusinessException.class,
        () -> store.apply("generation-task")).getCode());
    verify(trips, never()).createGenerated(any());
    verify(db, never()).update(contains("ai_quota_used=GREATEST"), any(Object[].class));
  }

  @Test
  void workerFailureCanRefundOnlyOnce() {
    store.fail("generation-task", "输出无法读取");
    verify(db).update(contains("ai_quota_used=GREATEST"), eq(7L), any());
    row.put("status", "FAILED");
    store.fail("generation-task", "再次失败通知");
    verify(db, times(1)).update(contains("ai_quota_used=GREATEST"), eq(7L), any());
  }

  @Test
  void unknownCostAndDurationRemainNullAndBlockFinalSave() {
    var draft = draft();
    var item = draft.getDays().get(0).getItems().get(0);
    item.setCostEstimate(null);
    item.setCostTimeHours(null);
    store.complete("generation-task", draft);
    var preview = store.get("generation-task");
    assertEquals("PREVIEW", preview.get("status"));
    assertIssue(preview, "MISSING_COST");
    assertIssue(preview, "MISSING_DURATION");
    var resaved = store.updateDraft("generation-task", editableDraft(), 0L);
    assertIssue(resaved, "MISSING_COST");
    assertIssue(resaved, "MISSING_DURATION");
    assertEquals(1L, ((Number) ((Map<?, ?>) resaved.get("generation")).get("draftRevision")).longValue());
    var stored = trips.decode((String) row.get("result_json"));
    assertNull(stored.getTotalBudget());
    assertNull(stored.getDays().get(0).getItems().get(0).getCostEstimate());
    assertNull(stored.getDays().get(0).getItems().get(0).getCostTimeHours());
    assertEquals(422, assertThrows(BusinessException.class,
        () -> store.apply("generation-task")).getCode());
    verify(trips, never()).createGenerated(any());
  }

  @Test
  void aggregateIsRecomputedFromKnownCostsWithoutChangingTheConfirmedBudget() {
    var inaccurateTotal = draft();
    inaccurateTotal.setTotalBudget(new BigDecimal("999"));
    inaccurateTotal.setTotalDays(99);
    inaccurateTotal.getDays().get(0).setDayIndex(42);
    var unverified = inaccurateTotal.getDays().get(0).getItems().get(0);
    unverified.setLongitude(new BigDecimal("181"));
    unverified.setPoiId("UNVERIFIED_PLACE");
    unverified.setRating(5d);
    unverified.setCoverImage("https://unverified.invalid/image.png");
    store.complete("generation-task", inaccurateTotal);
    var preview = store.get("generation-task");
    assertEquals("PREVIEW", preview.get("status"));
    assertEquals(0, trips.decode((String) row.get("result_json")).getTotalBudget()
        .compareTo(new BigDecimal("100")));
    assertFalse((Boolean) ((Map<?, ?>) preview.get("generation")).get("saveBlocked"));
    assertTrue(((Map<?, ?>) preview.get("generation")).get("corrections").toString()
        .contains("活动费用明细重新计算"));
    var normalized = trips.decode((String) row.get("result_json"));
    assertEquals(1, normalized.getTotalDays());
    assertEquals(1, normalized.getDays().get(0).getDayIndex());
    assertNull(normalized.getDays().get(0).getItems().get(0).getLongitude());
    assertNull(normalized.getDays().get(0).getItems().get(0).getPoiId());
    assertNull(normalized.getDays().get(0).getItems().get(0).getRating());
    store.apply("generation-task");
    var captured = org.mockito.ArgumentCaptor.forClass(TripDetailVO.class);
    verify(trips).createGenerated(captured.capture());
    assertEquals(0, captured.getValue().getTotalBudget().compareTo(new BigDecimal("100")));
  }
  TripDetailVO editableDraft() {
    return new com.fasterxml.jackson.databind.ObjectMapper()
        .convertValue(trips.parseObject((String) row.get("result_json")), TripWriteDTO.class).toTrip();
  }

  @Test
  void draftEditingIsRevisionedAndOnlyConfirmationCreatesTheTrip() {
    store.complete("generation-task", draft());
    assertEquals(0L, ((Number) ((Map<?, ?>) store.get("generation-task").get("generation"))
        .get("draftRevision")).longValue());
    var edited = editableDraft();
    edited.setTitle("我编辑的京都草稿");
    edited.getDays().get(0).getItems().get(0).setCostEstimate(new BigDecimal("150"));
    var result = store.updateDraft("generation-task", edited, 0L);
    assertEquals("PREVIEW", result.get("status"));
    assertNull(result.get("tripId"));
    assertEquals(1L, ((Number) ((Map<?, ?>) result.get("generation")).get("draftRevision")).longValue());
    assertEquals(0, trips.decode((String) row.get("result_json")).getTotalBudget().compareTo(new BigDecimal("150")));
    verify(trips, never()).createGenerated(any());
    verify(db, never()).update(contains("UPDATE sys_user"), any(Object[].class));
    assertEquals(409, assertThrows(BusinessException.class,
        () -> store.apply("generation-task", null, 0L)).getCode());
    assertEquals(91L, store.apply("generation-task", null, 1L).getId());
    assertEquals(91L, store.apply("generation-task", null, 0L).getId());
    var capture = org.mockito.ArgumentCaptor.forClass(TripDetailVO.class);
    verify(trips).createGenerated(capture.capture());
    assertEquals("我编辑的京都草稿", capture.getValue().getTitle());
    assertEquals(0, capture.getValue().getTotalBudget().compareTo(new BigDecimal("150")));
  }

  @Test
  void invalidOrStaleDraftEditsLeaveTheStoredPreviewUntouched() {
    store.complete("generation-task", draft());
    Object originalResult = row.get("result_json"), originalMeta = row.get("proposal_meta_json");
    assertEquals(400, assertThrows(BusinessException.class,
        () -> store.updateDraft("generation-task", editableDraft(), null)).getCode());
    assertEquals(409, assertThrows(BusinessException.class,
        () -> store.updateDraft("generation-task", editableDraft(), 1L)).getCode());
    assertEquals(originalResult, row.get("result_json"));
    assertEquals(originalMeta, row.get("proposal_meta_json"));
    when(access.userId()).thenReturn(8L);
    assertEquals(404, assertThrows(BusinessException.class,
        () -> store.updateDraft("generation-task", editableDraft(), 0L)).getCode());
    when(access.userId()).thenReturn(7L);
    row.put("proposal_meta_json", trips.encode(Map.of("requirement", requirement(),
        "expiresAt", Instant.now().minusSeconds(1).toString())));
    assertEquals(410, assertThrows(BusinessException.class,
        () -> store.updateDraft("generation-task", editableDraft(), 0L)).getCode());
    row.put("status", "APPLIED");
    assertEquals(409, assertThrows(BusinessException.class,
        () -> store.updateDraft("generation-task", editableDraft(), 0L)).getCode());
    verify(trips, never()).createGenerated(any());
    verify(db, never()).update(contains("UPDATE sys_user"), any(Object[].class));
  }
  @Test
  void editableBusinessIssuesCanBeStoredAndRepairedWithoutCreatingTrip() {
    store.complete("generation-task", draft());
    var changed = editableDraft();
    changed.getDays().get(0).getItems().get(0).setCostEstimate(new BigDecimal("501"));
    changed.getDays().add(draft().getDays().get(0));
    var blocked = store.updateDraft("generation-task", changed, 0L);
    assertEquals("PREVIEW", blocked.get("status"));
    assertIssue(blocked, "BUDGET_EXCEEDED");
    assertIssue(blocked, "DAY_COUNT");
    assertEquals(422, assertThrows(BusinessException.class,
        () -> store.apply("generation-task", null, 1L)).getCode());
    verify(trips, never()).createGenerated(any());
    var repaired = store.updateDraft("generation-task", draft(), 1L);
    assertFalse((Boolean) ((Map<?, ?>) repaired.get("generation")).get("saveBlocked"));
    assertEquals(2L, ((Number) ((Map<?, ?>) repaired.get("generation")).get("draftRevision")).longValue());
    assertEquals(91L, store.apply("generation-task", null, 2L).getId());
    verify(trips).createGenerated(any());
  }

  @Test
  void emptyModelPlanIsFatalWhileWrongActualDaysRemainEditableAndBlocked() {
    var confirmed = new com.fasterxml.jackson.databind.ObjectMapper().convertValue(
        Map.of("destinations", List.of("京都"), "days", 10, "travelers", 6,
            "budget", 3000, "budgetMax", 4500, "budgetScope", "PER_PERSON", "currency", "CNY"),
        TravelRequirement.class);
    row.put("proposal_meta_json", trips.encode(Map.of("requirement", confirmed,
        "expiresAt", Instant.now().plusSeconds(86400).toString())));
    var empty = draft();
    empty.setDays(List.of());
    var incomplete = assertThrows(BusinessException.class, () -> store.complete("generation-task", empty));
    assertEquals(422, incomplete.getCode());
    assertTrue(incomplete.getMessage().contains("不完整"));
    assertFalse(incomplete.getMessage().contains("修改需求"));
    assertNull(row.get("result_json"));
    assertEquals("RUNNING", row.get("status"));
    store.complete("generation-task", draft());
    var preview = store.get("generation-task");
    assertEquals("PREVIEW", preview.get("status"));
    assertIssue(preview, "DAY_COUNT");
    assertEquals(422, assertThrows(BusinessException.class,
        () -> store.apply("generation-task")).getCode());
    verify(trips, never()).createGenerated(any());
  }

  void assertIssue(Map<String, Object> task, String code) {
    var generation = (Map<?, ?>) task.get("generation");
    assertEquals(Boolean.TRUE, generation.get("saveBlocked"));
    var issues = new com.fasterxml.jackson.databind.ObjectMapper()
        .valueToTree(generation.get("issues"));
    assertTrue(issues.findValuesAsText("code").contains(code), issues.toString());
  }

  @Test
  void modelDiagnosticsAreBoundedPrivateAndSurvivePreviewNormalization() {
    store.recordModelOutput("generation-task", Map.of(
        "responseText", "x".repeat(200010), "responseCharacters", 200010,
        "model", "fixture-model", "finishReason", "stop", "requestId", "fixture-request",
        "shape", Map.of("days", 0), "unexpected", "not-persisted"));
    var internal = (Map<?, ?>) trips.parseObject((String) row.get("proposal_meta_json"));
    var diagnostics = (Map<?, ?>) internal.get("_modelDiagnostics");
    assertEquals(200000, diagnostics.get("responseText").toString().length());
    assertFalse(diagnostics.containsKey("unexpected"));
    assertTrue(internal.containsKey("requirement"));
    assertTrue(internal.containsKey("expiresAt"));
    assertEquals(0, ((Number) internal.get("draftRevision")).intValue());
    assertFalse(((Map<?, ?>) store.get("generation-task").get("generation")).containsKey("_modelDiagnostics"));
    assertEquals("RUNNING", row.get("status"));
    assertEquals(1, row.get("quota_reserved"));
    store.recordModelOutput("generation-task", Map.of("stage", "PARSE_JSON", "failureCategory", "MODEL_OUTPUT_INCOMPLETE"));
    store.complete("generation-task", draft());
    var after = (Map<?, ?>) trips.parseObject((String) row.get("proposal_meta_json"));
    assertTrue(after.containsKey("_modelDiagnostics"));
    var recorded = (Map<?, ?>) after.get("_modelDiagnostics");
    assertEquals(200000, recorded.get("responseText").toString().length());
    assertEquals("stop", recorded.get("finishReason"));
    assertEquals("PARSE_JSON", recorded.get("stage"));
    assertFalse(((Map<?, ?>) store.get("generation-task").get("generation")).containsKey("_modelDiagnostics"));
    verify(trips, never()).createGenerated(any());
    verify(db, never()).update(contains("UPDATE sys_user"), any(Object[].class));
  }
  @Test
  void expiredOrForeignPreviewCannotBeSaved() {
    store.complete("generation-task", draft());
    row.put("proposal_meta_json", trips.encode(Map.of("requirement", requirement(),
        "expiresAt", Instant.now().minusSeconds(1).toString())));
    assertEquals(410, assertThrows(BusinessException.class,
        () -> store.apply("generation-task")).getCode());
    when(access.userId()).thenReturn(8L);
    assertEquals(404, assertThrows(BusinessException.class,
        () -> store.apply("generation-task")).getCode());
    verify(trips, never()).createGenerated(any());
  }

  @Test
  void explicitBudgetRaisePreservesOriginalConstraintAndAuditAndRetriesExactlyOnce() {
    var expensive = draft();
    expensive.getDays().get(0).getItems().get(0).setCostEstimate(new BigDecimal("600"));
    store.complete("generation-task", expensive);
    assertIssue(store.get("generation-task"), "BUDGET_EXCEEDED");
    String key = UUID.randomUUID().toString();
    var raised = store.raiseBudget("generation-task", new BigDecimal("650"), true, 0L, key);
    var generation = (Map<?, ?>) raised.get("generation");
    assertEquals(1L, ((Number) generation.get("draftRevision")).longValue());
    assertFalse((Boolean) generation.get("saveBlocked"));
    var current = (Map<?, ?>) generation.get("requirement");
    var original = (Map<?, ?>) generation.get("originalRequirement");
    assertEquals(500, ((Number) current.get("budget")).intValue());
    assertEquals(650, ((Number) current.get("budgetMax")).intValue());
    assertEquals("TOTAL", current.get("budgetScope"));
    assertEquals(500, ((Number) original.get("budget")).intValue());
    assertNull(original.get("budgetMax"));
    var audits = (List<?>) generation.get("budgetChanges");
    assertEquals(1, audits.size());
    var audit = (Map<?, ?>) audits.get(0);
    assertEquals(key, audit.get("requestKey"));
    assertEquals(7L, ((Number) audit.get("userId")).longValue());
    assertDoesNotThrow(() -> Instant.parse(audit.get("changedAt").toString()));
    assertEquals(500, ((Number) ((Map<?, ?>) audit.get("before")).get("totalCap")).intValue());
    assertEquals(650, ((Number) ((Map<?, ?>) audit.get("after")).get("totalCap")).intValue());
    assertEquals(raised, store.raiseBudget("generation-task", new BigDecimal("650.00"), true, 0L, key));
    assertEquals(409, assertThrows(BusinessException.class,
        () -> store.raiseBudget("generation-task", new BigDecimal("700"), true, 0L, key)).getCode());
    var twice = store.raiseBudget("generation-task", new BigDecimal("700"), true, 1L, UUID.randomUUID().toString());
    assertEquals(original, ((Map<?, ?>) twice.get("generation")).get("originalRequirement"));
    assertEquals(2, ((List<?>) ((Map<?, ?>) twice.get("generation")).get("budgetChanges")).size());
    assertEquals(twice, store.raiseBudget("generation-task", new BigDecimal("650"), true, 0L, key));
    assertEquals(0, trips.decode((String) row.get("result_json")).getTotalBudget().compareTo(new BigDecimal("600")));
    verify(trips, never()).createGenerated(any());
    verify(db, never()).update(contains("UPDATE sys_user"), any(Object[].class));
  }

  @Test
  void budgetRaiseRequiresOwnerConfirmationFreshRevisionLivePreviewAndActualIncrease() {
    store.complete("generation-task", draft());
    Object untouched = row.get("proposal_meta_json");
    String key = UUID.randomUUID().toString();
    assertEquals(400, assertThrows(BusinessException.class,
        () -> store.raiseBudget("generation-task", new BigDecimal("600"), false, 0L, key)).getCode());
    assertEquals(400, assertThrows(BusinessException.class,
        () -> store.raiseBudget("generation-task", new BigDecimal("600"), true, null, key)).getCode());
    assertEquals(409, assertThrows(BusinessException.class,
        () -> store.raiseBudget("generation-task", new BigDecimal("600"), true, 1L, key)).getCode());
    for (var amount : List.of(new BigDecimal("499"), new BigDecimal("500"), new BigDecimal("600.001")))
      assertEquals(400, assertThrows(BusinessException.class,
          () -> store.raiseBudget("generation-task", amount, true, 0L, key)).getCode());
    assertEquals(400, assertThrows(BusinessException.class,
        () -> store.raiseBudget("generation-task", new BigDecimal("600"), true, 0L, "invalid")).getCode());
    when(access.userId()).thenReturn(8L);
    assertEquals(404, assertThrows(BusinessException.class,
        () -> store.raiseBudget("generation-task", new BigDecimal("600"), true, 0L, key)).getCode());
    when(access.userId()).thenReturn(7L);
    assertEquals(untouched, row.get("proposal_meta_json"));
    row.put("proposal_meta_json", trips.encode(Map.of("requirement", requirement(),
        "expiresAt", Instant.now().minusSeconds(1).toString())));
    assertEquals(410, assertThrows(BusinessException.class,
        () -> store.raiseBudget("generation-task", new BigDecimal("600"), true, 0L, key)).getCode());
    row.put("status", "APPLIED");
    assertEquals(409, assertThrows(BusinessException.class,
        () -> store.raiseBudget("generation-task", new BigDecimal("600"), true, 0L, key)).getCode());
    verify(trips, never()).createGenerated(any());
    verify(db, never()).update(contains("UPDATE sys_user"), any(Object[].class));
  }

  @Test
  void perPersonBudgetRaiseMultipliesOnlyConfirmedCountAndDoesNotClearOtherIssues() {
    var confirmed = new com.fasterxml.jackson.databind.ObjectMapper().convertValue(
        Map.of("destinations", List.of("京都"), "days", 1, "travelers", 6,
            "budget", 3000, "budgetMax", 4500, "budgetScope", "PER_PERSON", "currency", "CNY"), TravelRequirement.class);
    row.put("proposal_meta_json", trips.encode(Map.of("requirement", confirmed,
        "expiresAt", Instant.now().plusSeconds(86400).toString())));
    var expensive = draft();
    expensive.getDays().get(0).getItems().get(0).setCostEstimate(new BigDecimal("35360"));
    expensive.getDays().get(0).getItems().get(0).setCostTimeHours(null);
    store.complete("generation-task", expensive);
    assertIssue(store.get("generation-task"), "BUDGET_EXCEEDED");
    var raised = store.raiseBudget("generation-task", new BigDecimal("6000"), true, 0L, UUID.randomUUID().toString());
    assertIssue(raised, "MISSING_DURATION");
    var generation = (Map<?, ?>) raised.get("generation");
    var audited = (Map<?, ?>) ((List<?>) generation.get("budgetChanges")).get(0);
    assertEquals(36000, ((Number) ((Map<?, ?>) audited.get("after")).get("totalCap")).intValue());
    var revised = (Map<?, ?>) generation.get("requirement");
    assertEquals(6, ((Number) revised.get("travelers")).intValue());
    assertEquals(3000, ((Number) revised.get("budget")).intValue());
    assertEquals("PER_PERSON", revised.get("budgetScope"));
    assertEquals("CNY", revised.get("currency"));
    assertFalse(generation.get("issues").toString().contains("BUDGET_EXCEEDED"));
  }

  @Test
  void fastCompletionRetainsActualPhaseHistoryAndStartTimestampOnReadback() {
    var queuedAt = Instant.now().minusSeconds(1);
    row.put("status", "QUEUED");
    row.put("proposal_meta_json", trips.encode(Map.of("requirement", requirement(),
        "expiresAt", Instant.now().plusSeconds(86400).toString(), "phase", "QUEUED",
        "phaseHistory", List.of(Map.of("phase", "QUEUED", "at", queuedAt.toString())))));
    var beforeStart = Instant.now();
    assertTrue(store.start("generation-task"));
    row.put("status", "RUNNING"); // This mock records metadata; a real database updates status in start().
    store.setPhase("generation-task", "MODEL_COMPLETED");
    store.setPhase("generation-task", "NORMALIZING");
    store.complete("generation-task", draft());
    var restored = store.get("generation-task");
    var generation = (Map<?, ?>) restored.get("generation");
    var history = (List<?>) generation.get("phaseHistory");
    assertEquals(List.of("QUEUED", "MODEL", "MODEL_COMPLETED", "NORMALIZING", "VALIDATING", "PREVIEW"),
        history.stream().map(value -> ((Map<?, ?>) value).get("phase")).toList());
    Instant previous = queuedAt;
    for (var event : history) {
      var at = Instant.parse(((Map<?, ?>) event).get("at").toString());
      assertFalse(at.isBefore(previous));
      previous = at;
    }
    assertFalse(Instant.parse(restored.get("startedAt").toString()).isBefore(beforeStart));
    assertEquals("PREVIEW", restored.get("phase"));
    assertEquals(generation.get("startedAt"), restored.get("startedAt"));
    var afterEdit = store.updateDraft("generation-task", editableDraft(), 0L);
    assertEquals(history, ((Map<?, ?>) afterEdit.get("generation")).get("phaseHistory"));
    assertEquals("PREVIEW", afterEdit.get("phase"));
  }
}

package com.smarttrip;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.smarttrip.common.BusinessException;
import com.smarttrip.service.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.*;
import org.springframework.transaction.support.SimpleTransactionStatus;

class AiRetryTests {
  JdbcTemplate db;
  AiTaskStore store;
  AiTaskService service;
  StringRedisTemplate redis;
  WorkbenchService trips;
  TravelRequirementService requirements;

  @BeforeEach
  void setup() {
    db = mock(JdbcTemplate.class);
    store = mock(AiTaskStore.class);
    var access = mock(ResourceAccess.class);
    when(access.userId()).thenReturn(1L);
    var provider = mock(AiProvider.class);
    when(provider.available()).thenReturn(true);
    redis = mock(StringRedisTemplate.class);
    when(redis.opsForValue()).thenReturn(mock(ValueOperations.class));
    var tm = mock(PlatformTransactionManager.class);
    when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    trips = new WorkbenchService(db, mock(com.smarttrip.repository.TripRepository.class), access);
    requirements = new TravelRequirementService();
    service =
        new AiTaskService(
            db,
            provider,
            store,
            trips,
            access,
            redis,
            tm,
            mock(ItineraryAnalysisService.class),
            requirements);
  }

  @AfterEach
  void close() {
    service.shutdown();
  }

  com.smarttrip.entity.TravelRequirement requirement(String destination) {
    return new com.fasterxml.jackson.databind.ObjectMapper().convertValue(
        Map.of("description", "去" + destination + "一天", "destinations", List.of(destination), "days", 1),
        com.smarttrip.entity.TravelRequirement.class);
  }

  Map<String, Object> row(com.smarttrip.entity.TravelRequirement requirement) {
    return Map.of(
        "id", "existing-task", "request_hash", CommunityService.hash(
            "GENERATE\nnull\n" + trips.encode(requirements.confirmed(requirement))));
  }

  @Test
  void concurrentUniqueKeyLoserCannotReuseDifferentInput() {
    when(db.queryForList(anyString(), any(Object[].class)))
        .thenReturn(List.of(), List.of(row(requirement("京都"))));
    when(db.update(anyString(), any(Object[].class)))
        .thenThrow(new DuplicateKeyException("fixture conflict"));
    assertEquals(
        409,
        assertThrows(
                BusinessException.class,
                () -> service.submitGeneration("same-request", requirement("东京"), true))
            .getCode());
    verifyNoInteractions(store);
  }

  @Test
  void concurrentIdenticalRetryReturnsExistingJob() {
    when(db.queryForList(anyString(), any(Object[].class)))
        .thenReturn(List.of(), List.of(row(requirement("京都"))));
    when(db.update(anyString(), any(Object[].class)))
        .thenThrow(new DuplicateKeyException("fixture conflict"));
    when(store.get("existing-task")).thenReturn(Map.of("id", "existing-task"));
    assertEquals(
        "existing-task", service.submitGeneration("same-request", requirement("京都"), true).get("id"));
    verify(store).get("existing-task");
  }

  @Test
  void terminalWorkerLockCanBeReclaimedWithItsExactToken() {
    var ops = redis.opsForValue();
    when(ops.setIfAbsent(anyString(), anyString(), any(java.time.Duration.class)))
        .thenReturn(false, true);
    when(ops.get("lock")).thenReturn("previous-task");
    when(db.queryForList(anyString(), any(Object[].class)))
        .thenReturn(List.of(Map.of("status", "PREVIEW")));
    assertEquals(
        true,
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(
            service, "acquire", "lock", "new-task", 1L));
    verify(redis)
        .execute(
            any(org.springframework.data.redis.core.script.RedisScript.class),
            eq(List.of("lock")),
            eq("previous-task"));
  }

  @Test
  void runningWorkerLockIsNeverCleared() {
    var ops = redis.opsForValue();
    when(ops.setIfAbsent(anyString(), anyString(), any(java.time.Duration.class)))
        .thenReturn(false);
    when(ops.get("lock")).thenReturn("running-task");
    when(db.queryForList(anyString(), any(Object[].class)))
        .thenReturn(List.of(Map.of("status", "RUNNING")));
    assertEquals(
        false,
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(
            service, "acquire", "lock", "new-task", 1L));
    verify(redis, never())
        .execute(
            any(org.springframework.data.redis.core.script.RedisScript.class),
            anyList(),
            any(Object[].class));
  }

  @Test
  void legacyGenerationRetryUsesOriginalRequirementAfterExplicitBudgetRevision() {
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    var original = requirements.confirmed(mapper.convertValue(
        Map.of("destinations", List.of("京都"), "days", 1,
            "budget", 500, "currency", "CNY", "budgetScope", "TOTAL"),
        com.smarttrip.entity.TravelRequirement.class));
    var revised = mapper.valueToTree(original);
    ((com.fasterxml.jackson.databind.node.ObjectNode) revised).put("budgetMax", 700);
    var legacy = Map.<String, Object>of("id", "existing-task", "task_type", "GENERATE", "request_hash", "legacy-hash",
        "proposal_meta_json", trips.encode(Map.of("originalRequirement", original, "requirement", revised)));
    when(db.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(legacy));
    when(store.get("existing-task")).thenReturn(Map.of("id", "existing-task"));
    assertEquals("existing-task", service.submitGeneration("same-request", original, true).get("id"));
    assertEquals(409, assertThrows(BusinessException.class, () -> service.submitGeneration("same-request",
        mapper.convertValue(revised, com.smarttrip.entity.TravelRequirement.class), true)).getCode());
    verify(db, never()).update(anyString(), any(Object[].class));
    verifyNoInteractions(redis);
  }
}

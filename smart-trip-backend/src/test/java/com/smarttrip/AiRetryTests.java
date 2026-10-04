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

  @BeforeEach
  void setup() {
    db = mock(JdbcTemplate.class);
    store = mock(AiTaskStore.class);
    var access = mock(ResourceAccess.class);
    when(access.userId()).thenReturn(1L);
    var provider = mock(AiProvider.class);
    when(provider.available()).thenReturn(true);
    var redis = mock(StringRedisTemplate.class);
    when(redis.opsForValue()).thenReturn(mock(ValueOperations.class));
    var tm = mock(PlatformTransactionManager.class);
    when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    service =
        new AiTaskService(db, provider, store, mock(WorkbenchService.class), access, redis, tm);
  }

  @AfterEach
  void close() {
    service.shutdown();
  }

  Map<String, Object> row(String prompt) {
    return Map.of(
        "id", "existing-task", "request_hash", CommunityService.hash("GENERATE\nnull\n" + prompt));
  }

  @Test
  void concurrentUniqueKeyLoserCannotReuseDifferentInput() {
    when(db.queryForList(anyString(), any(Object[].class)))
        .thenReturn(List.of(), List.of(row("original")));
    when(db.update(anyString(), any(Object[].class)))
        .thenThrow(new DuplicateKeyException("fixture conflict"));
    assertEquals(
        409,
        assertThrows(
                BusinessException.class,
                () -> service.submit("same-request", "GENERATE", null, "different"))
            .getCode());
    verifyNoInteractions(store);
  }

  @Test
  void concurrentIdenticalRetryReturnsExistingJob() {
    when(db.queryForList(anyString(), any(Object[].class)))
        .thenReturn(List.of(), List.of(row("original")));
    when(db.update(anyString(), any(Object[].class)))
        .thenThrow(new DuplicateKeyException("fixture conflict"));
    when(store.get("existing-task")).thenReturn(Map.of("id", "existing-task"));
    assertEquals(
        "existing-task", service.submit("same-request", "GENERATE", null, "original").get("id"));
    verify(store).get("existing-task");
  }
}

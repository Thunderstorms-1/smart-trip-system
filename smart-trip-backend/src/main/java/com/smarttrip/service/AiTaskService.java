package com.smarttrip.service;

import com.smarttrip.common.*;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AiTaskService {
  private final JdbcTemplate db;
  private final AiProvider provider;
  private final AiTaskStore store;
  private final WorkbenchService trips;
  private final ResourceAccess access;
  private final StringRedisTemplate redis;
  private final TransactionTemplate tx;
  private final ThreadPoolExecutor pool =
      new ThreadPoolExecutor(
          2,
          2,
          30,
          TimeUnit.SECONDS,
          new ArrayBlockingQueue<>(20),
          r -> {
            Thread t = new Thread(r, "trip-ai-worker");
            t.setDaemon(true);
            return t;
          });

  public AiTaskService(
      JdbcTemplate db,
      AiProvider provider,
      AiTaskStore store,
      WorkbenchService trips,
      ResourceAccess access,
      StringRedisTemplate redis,
      PlatformTransactionManager tm) {
    this.db = db;
    this.provider = provider;
    this.store = store;
    this.trips = trips;
    this.access = access;
    this.redis = redis;
    this.tx = new TransactionTemplate(tm);
  }

  @org.springframework.context.event.EventListener(
      org.springframework.boot.context.event.ApplicationReadyEvent.class)
  public void recover() {
    store.recover();
  }

  @PreDestroy
  public void shutdown() {
    pool.shutdownNow();
  }

  public Map<String, Object> submit(String key, String type, Long trip, String prompt) {
    Long user = access.userId();
    if (key == null
        || !key.matches("[A-Za-z0-9_-]{8,80}")
        || prompt == null
        || prompt.isBlank()
        || prompt.length() > 3000) throw new BusinessException(400, "请求标识或规划要求无效");
    if (!Set.of("GENERATE", "MODIFY").contains(type == null ? "" : type))
      throw new BusinessException(400, "任务类型无效");
    Long version = null;
    String input = prompt;
    if ("MODIFY".equals(type)) {
      access.write(trip);
      var current = trips.load(trip);
      version = current.getVersion();
      input = "修改要求：" + prompt + "\n当前路书：" + trips.encode(current);
    }
    var previous =
        db.queryForList(
            "SELECT id,input_json,task_type,trip_id FROM ai_task WHERE user_id=? AND request_key=?",
            user,
            key);
    if (!previous.isEmpty()) {
      var p = previous.get(0);
      if (!Objects.equals(p.get("input_json"), input) || !Objects.equals(p.get("task_type"), type))
        throw new BusinessException(409, "同一请求标识不能用于不同任务");
      return store.get((String) p.get("id"));
    }
    if (!provider.available()) throw new BusinessException(503, "AI免费额度尚未确认，暂未启用");
    try {
      redis.opsForValue().set("smarttrip:health", "ok", Duration.ofSeconds(10));
    } catch (Exception e) {
      throw new BusinessException(503, "任务服务暂不可用，请检查Redis");
    }
    String id = UUID.randomUUID().toString();
    final String request = input;
    final Long base = version;
    try {
      tx.executeWithoutResult(
          status ->
              db.update(
                  "INSERT INTO"
                      + " ai_task(id,user_id,request_key,task_type,status,trip_id,base_version,input_json)"
                      + " VALUES(?,?,?,?,'QUEUED',?,?,?)",
                  id,
                  user,
                  key,
                  type,
                  trip,
                  base,
                  request));
    } catch (org.springframework.dao.DuplicateKeyException e) {
      var existing =
          db.queryForObject(
              "SELECT id FROM ai_task WHERE user_id=? AND request_key=?", String.class, user, key);
      return store.get(existing);
    }
    try {
      pool.execute(() -> run(id, user, request));
    } catch (RejectedExecutionException e) {
      store.fail(id, "任务繁忙，请稍后重试");
    }
    return store.get(id);
  }

  private void run(String id, Long user, String input) {
    UserContext.setUserId(user);
    String lock = "smarttrip:ai:user:" + user;
    boolean acquired = false;
    try {
      acquired =
          Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(lock, id, Duration.ofSeconds(180)));
      if (!acquired) {
        store.fail(id, "已有AI任务运行中，请稍后重试");
        return;
      }
      if (!store.start(id)) return;
      String answer = provider.parse(provider.complete(input)).json();
      store.complete(id, trips.decode(answer));
    } catch (Exception e) {
      store.fail(id, e instanceof BusinessException ? e.getMessage() : "任务异常，配额已退回");
    } finally {
      if (acquired)
        try {
          redis.execute(
              new org.springframework.data.redis.core.script.DefaultRedisScript<Long>(
                  "if redis.call('get',KEYS[1])==ARGV[1] then return redis.call('del',KEYS[1]) else"
                      + " return 0 end",
                  Long.class),
              List.of(lock),
              id);
        } catch (Exception ignored) {
        }
      UserContext.clear();
    }
  }
}

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
  private final ItineraryAnalysisService analyses;
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
      PlatformTransactionManager tm,
      ItineraryAnalysisService analyses) {
    this.db = db;
    this.provider = provider;
    this.store = store;
    this.analyses = analyses;
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
    if ("GENERATE".equals(type) && trip != null) throw new BusinessException(400, "生成任务不应指定已有路书");
    if ("MODIFY".equals(type)) access.write(trip);
    String fingerprint = CommunityService.hash(type + "\n" + trip + "\n" + prompt);
    var previous =
        db.queryForList(
            "SELECT id,input_json,task_type,trip_id,request_hash FROM ai_task WHERE user_id=? AND"
                + " request_key=?",
            user,
            key);
    if (!previous.isEmpty()) return existing(previous.get(0), fingerprint, type, trip, prompt);
    Long version = null;
    String input = prompt;
    if ("MODIFY".equals(type)) {
      var current = trips.load(trip);
      version = current.getVersion();
      input = "修改要求：" + prompt + "\n当前路书：" + trips.encode(current);
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
                      + " ai_task(id,user_id,request_key,task_type,status,trip_id,base_version,input_json,request_hash)"
                      + " VALUES(?,?,?,?,'QUEUED',?,?,?,?)",
                  id,
                  user,
                  key,
                  type,
                  trip,
                  base,
                  request,
                  fingerprint));
    } catch (org.springframework.dao.DuplicateKeyException e) {
      var rows =
          db.queryForList(
              "SELECT id,input_json,task_type,trip_id,request_hash FROM ai_task WHERE user_id=? AND"
                  + " request_key=?",
              user,
              key);
      if (rows.isEmpty()) throw e;
      return existing(rows.get(0), fingerprint, type, trip, prompt);
    }
    try {
      pool.execute(() -> run(id, user, request, null));
    } catch (RejectedExecutionException e) {
      store.fail(id, "任务繁忙，请稍后重试");
    }
    return store.get(id);
  }

  public Map<String, Object> submitAdjustment(String key, Long trip, String analysisId) {
    Long user = access.userId();
    access.write(trip);
    if (key == null
        || !key.matches("[A-Za-z0-9_-]{8,80}")
        || analysisId == null
        || !analysisId.matches("[a-f0-9-]{36}")) throw new BusinessException(400, "请求标识或分析会话无效");
    String fingerprint = CommunityService.hash("ADJUST\n" + trip + "\n" + analysisId);
    var previous =
        db.queryForList(
            "SELECT id,request_hash FROM ai_task WHERE user_id=? AND request_key=?", user, key);
    if (!previous.isEmpty()) {
      if (!Objects.equals(previous.get(0).get("request_hash"), fingerprint))
        throw new BusinessException(409, "同一请求标识不能用于不同任务");
      return store.get((String) previous.get(0).get("id"));
    }
    var prepared = analyses.prepare(trip, analysisId);
    if (!provider.available()) throw new BusinessException(503, "AI免费权限尚未确认，基础检查仍可使用");
    try {
      redis.opsForValue().set("smarttrip:health", "ok", Duration.ofSeconds(10));
    } catch (Exception e) {
      throw new BusinessException(503, "任务服务暂不可用，请检查Redis");
    }
    String id = UUID.randomUUID().toString();
    try {
      tx.executeWithoutResult(
          status ->
              db.update(
                  "INSERT INTO"
                      + " ai_task(id,user_id,request_key,task_type,status,trip_id,base_version,input_json,request_hash)"
                      + " VALUES(?,?,?,'ADJUST','QUEUED',?,?,?,?)",
                  id,
                  user,
                  key,
                  trip,
                  prepared.base().getVersion(),
                  analyses.persistedInput(prepared),
                  fingerprint));
    } catch (org.springframework.dao.DuplicateKeyException e) {
      var rows =
          db.queryForList(
              "SELECT id,request_hash FROM ai_task WHERE user_id=? AND request_key=?", user, key);
      if (rows.isEmpty()) throw e;
      if (!Objects.equals(rows.get(0).get("request_hash"), fingerprint))
        throw new BusinessException(409, "同一请求标识不能用于不同任务");
      return store.get((String) rows.get(0).get("id"));
    }
    try {
      pool.execute(() -> run(id, user, analyses.modelInput(prepared), prepared));
    } catch (RejectedExecutionException e) {
      store.fail(id, "任务繁忙，请稍后重试");
    }
    return store.get(id);
  }

  private Map<String, Object> existing(
      Map<String, Object> row, String fingerprint, String type, Long trip, String prompt) {
    Object previousHash = row.get("request_hash");
    boolean matches = Objects.equals(previousHash, fingerprint);
    if (previousHash == null) {
      String previousInput = String.valueOf(row.get("input_json"));
      Long previousTrip =
          row.get("trip_id") == null ? null : ((Number) row.get("trip_id")).longValue();
      matches =
          Objects.equals(row.get("task_type"), type)
              && Objects.equals(previousTrip, trip)
              && ("MODIFY".equals(type)
                  ? previousInput.startsWith("修改要求：" + prompt + "\n当前路书：")
                  : previousInput.equals(prompt));
    }
    if (!matches) throw new BusinessException(409, "同一请求标识不能用于不同任务");
    return store.get((String) row.get("id"));
  }

  private boolean acquire(String lock, String id, Long user) {
    if (Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(lock, id, Duration.ofSeconds(180))))
      return true;
    String prior = redis.opsForValue().get(lock);
    if (prior != null) {
      var rows =
          db.queryForList("SELECT status FROM ai_task WHERE id=? AND user_id=?", prior, user);
      if (rows.isEmpty() || Set.of("QUEUED", "RUNNING").contains(rows.get(0).get("status")))
        return false;
      // Completion can be observed before the worker's finally block releases its lock.
      // Compare-and-delete only that terminal task's token; never clear a newer worker.
      release(lock, prior);
    }
    return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(lock, id, Duration.ofSeconds(180)));
  }

  private void release(String lock, String id) {
    redis.execute(
        new org.springframework.data.redis.core.script.DefaultRedisScript<Long>(
            "if redis.call('get',KEYS[1])==ARGV[1] then return redis.call('del',KEYS[1]) else"
                + " return 0 end",
            Long.class),
        List.of(lock),
        id);
  }

  private void run(
      String id, Long user, String input, ItineraryAnalysisService.Prepared adjustment) {
    UserContext.setUserId(user);
    String lock = "smarttrip:ai:user:" + user;
    boolean acquired = false;
    try {
      acquired = acquire(lock, id, user);
      if (!acquired) {
        store.fail(id, "已有AI任务运行中，请稍后重试");
        return;
      }
      if (!store.start(id)) return;
      String answer = provider.parse(provider.complete(input)).json();
      if (adjustment == null) store.complete(id, trips.decode(answer));
      else store.completeAdjustment(id, analyses.build(adjustment, answer));
    } catch (Exception e) {
      store.fail(id, e instanceof BusinessException ? e.getMessage() : "任务异常，配额已退回");
    } finally {
      if (acquired)
        try {
          release(lock, id);
        } catch (Exception ignored) {
        }
      UserContext.clear();
    }
  }
}

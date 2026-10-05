package com.smarttrip.service;

import com.smarttrip.common.*;
import com.smarttrip.entity.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AiTaskStore {
  private final JdbcTemplate db;
  private final WorkbenchService trips;
  private final ResourceAccess access;
  private final ItineraryAnalysisService analyses;

  public AiTaskStore(
      JdbcTemplate db,
      WorkbenchService trips,
      ResourceAccess access,
      ItineraryAnalysisService analyses) {
    this.db = db;
    this.trips = trips;
    this.access = access;
    this.analyses = analyses;
  }

  public Map<String, Object> get(String id) {
    var rows =
        db.queryForList(
            "SELECT id,user_id AS userId,status,task_type AS type,trip_id AS tripId,base_version AS"
                + " baseVersion,result_json AS resultJson,proposal_meta_json AS"
                + " proposalMeta,error_message AS errorMessage,create_time AS createdAt FROM"
                + " ai_task WHERE id=? AND user_id=?",
            id,
            access.userId());
    if (rows.isEmpty()) throw new BusinessException(404, "任务不存在");
    var t = rows.get(0);
    Object raw = t.remove("resultJson");
    if (raw != null) {
      try {
        t.put("result", trips.parseObject(raw.toString()));
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    }
    Object meta = t.remove("proposalMeta");
    if (meta != null) t.put("proposal", trips.parseObject(meta.toString()));
    return t;
  }

  @Transactional
  public boolean start(String id) {
    var t = db.queryForMap("SELECT * FROM ai_task WHERE id=? FOR UPDATE", id);
    if (!"QUEUED".equals(t.get("status"))) return false;
    long user = ((Number) t.get("user_id")).longValue();
    db.update(
        "UPDATE sys_user SET ai_quota_used=0,quota_reset_date=CURRENT_DATE WHERE id=? AND"
            + " (quota_reset_date IS NULL OR quota_reset_date<CURRENT_DATE)",
        user);
    if (db.update(
            "UPDATE sys_user SET ai_quota_used=ai_quota_used+1 WHERE id=? AND"
                + " ai_quota_used<ai_quota_daily AND status=1",
            user)
        != 1) {
      db.update("UPDATE ai_task SET status='FAILED',error_message='今日免费调用配额已用完' WHERE id=?", id);
      return false;
    }
    db.update(
        "UPDATE ai_task SET status='RUNNING',quota_reserved=1,quota_reserved_date=CURRENT_DATE"
            + " WHERE id=?",
        id);
    return true;
  }

  @Transactional
  public void complete(String id, TripDetailVO t) {
    var task = db.queryForMap("SELECT * FROM ai_task WHERE id=? FOR UPDATE", id);
    if (!"RUNNING".equals(task.get("status"))) return;
    trips.validate(t);
    String type = (String) task.get("task_type");
    if ("GENERATE".equals(type)) {
      var created = trips.create(t);
      db.update(
          "UPDATE ai_task SET status='SUCCEEDED',trip_id=?,result_json=?,quota_reserved=0 WHERE"
              + " id=?",
          created.getId(),
          trips.encode(created),
          id);
    } else {
      Long trip = ((Number) task.get("trip_id")).longValue();
      access.read(trip);
      t.setId(trip);
      t.setVersion(((Number) task.get("base_version")).longValue());
      db.update(
          "UPDATE ai_task SET status='PREVIEW',result_json=?,quota_reserved=0 WHERE id=?",
          trips.encode(t),
          id);
    }
  }

  @Transactional
  public void completeAdjustment(String id, ItineraryAnalysisService.Built built) {
    var task = db.queryForMap("SELECT * FROM ai_task WHERE id=? FOR UPDATE", id);
    if (!"RUNNING".equals(task.get("status"))) return;
    Long trip = ((Number) task.get("trip_id")).longValue();
    var current = access.writeLocked(trip);
    if (!Objects.equals(current.getVersion(), ((Number) task.get("base_version")).longValue()))
      throw new BusinessException(409, "路书已变化，请重新分析");
    built.trip().setId(trip);
    built.trip().setVersion(current.getVersion());
    db.update(
        "UPDATE ai_task SET status='PREVIEW',result_json=?,proposal_meta_json=?,quota_reserved=0"
            + " WHERE id=?",
        trips.encode(built.trip()),
        trips.encode(built.proposal()),
        id);
  }

  @Transactional
  public void fail(String id, String message) {
    var rows =
        db.queryForList(
            "SELECT user_id,quota_reserved,quota_reserved_date,status FROM ai_task WHERE id=? FOR"
                + " UPDATE",
            id);
    if (rows.isEmpty()) return;
    var t = rows.get(0);
    if (!Set.of("QUEUED", "RUNNING").contains(t.get("status"))) return;
    if (((Number) t.get("quota_reserved")).intValue() == 1)
      db.update(
          "UPDATE sys_user SET ai_quota_used=GREATEST(ai_quota_used-1,0) WHERE id=? AND"
              + " quota_reset_date=?",
          t.get("user_id"),
          t.get("quota_reserved_date"));
    db.update(
        "UPDATE ai_task SET status='FAILED',quota_reserved=0,error_message=? WHERE id=?",
        message,
        id);
  }

  @Transactional
  public TripDetailVO apply(String id) {
    get(id);
    var t = db.queryForMap("SELECT * FROM ai_task WHERE id=? FOR UPDATE", id);
    if ("APPLIED".equals(t.get("status")))
      return trips.load(((Number) t.get("trip_id")).longValue());
    if (!"PREVIEW".equals(t.get("status"))) throw new BusinessException(409, "任务没有可确认的预览");
    var current = access.writeLocked(((Number) t.get("trip_id")).longValue());
    if (!Objects.equals(current.getVersion(), ((Number) t.get("base_version")).longValue()))
      throw new BusinessException(409, "路书已变化，请重新生成提案");
    if ("ADJUST".equals(t.get("task_type"))) {
      var meta = (Map<?, ?>) trips.parseObject((String) t.get("proposal_meta_json"));
      Object expires = meta.get("expiresAt");
      if (expires != null
          && !java.time.Instant.now().isBefore(java.time.Instant.parse(expires.toString())))
        throw new BusinessException(409, "外部分析依据已过期，请重新查询并生成");
      if (((List<?>) meta.get("changes")).isEmpty())
        throw new BusinessException(409, "提案没有需要保存的变更");
    }
    var proposal = trips.decode((String) t.get("result_json"));
    if ("ADJUST".equals(t.get("task_type")))
      analyses.verifyForApply(
          (String) t.get("input_json"),
          proposal,
          (Map<?, ?>) trips.parseObject((String) t.get("proposal_meta_json")));
    var saved = trips.save(((Number) t.get("trip_id")).longValue(), proposal);
    db.update("UPDATE ai_task SET status='APPLIED' WHERE id=?", id);
    return saved;
  }

  @Transactional
  public void recover() {
    var rows = db.queryForList("SELECT id FROM ai_task WHERE status IN ('QUEUED','RUNNING')");
    for (var row : rows) fail((String) row.get("id"), "服务重启中断了任务，配额已退回，请重新发起");
  }
}

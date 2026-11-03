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
  private final TravelRequirementService requirements;
  private final AiDraftNormalizer normalizer;
  private final AiProvider provider;

  public AiTaskStore(
      JdbcTemplate db,
      WorkbenchService trips,
      ResourceAccess access,
      ItineraryAnalysisService analyses,
      TravelRequirementService requirements,
      AiDraftNormalizer normalizer,
      AiProvider provider) {
    this.db = db;
    this.trips = trips;
    this.access = access;
    this.analyses = analyses;
    this.requirements = requirements;
    this.normalizer = normalizer;
    this.provider = provider;
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
        t.put("INTERPRET".equals(t.get("type")) ? "interpretation" : "result", trips.parseObject(raw.toString()));
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    }
    Object meta = t.remove("proposalMeta");
    if (meta != null || "GENERATE".equals(t.get("type"))) {
      String field = "GENERATE".equals(t.get("type")) ? "generation"
          : "INTERPRET".equals(t.get("type")) ? "interpretationContext" : "proposal";
      if ("GENERATE".equals(t.get("type"))) {
        var generation = generationMetadata(meta);
        t.put("recoverable", "FAILED".equals(t.get("status")) && canRecover(generation));
        t.put("phase", generation.getOrDefault("phase", "RUNNING".equals(t.get("status")) ? "MODEL" : t.get("status")));
        t.put("startedAt", generation.get("startedAt"));
        generation.remove("_modelDiagnostics");
        generation.remove("_ragContext");
        t.put(field, generation);
      } else t.put(field, trips.parseObject(meta.toString()));
    }
    return t;
  }

  @Transactional
  public boolean start(String id) {
    return start(id, false);
  }

  @Transactional
  public boolean start(String id, boolean retrievingKnowledge) {
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
    if ("GENERATE".equals(t.get("task_type"))) {
      var meta = generationMetadata(t.get("proposal_meta_json"));
      String now = java.time.Instant.now().toString();
      meta.putIfAbsent("startedAt", now);
      appendPhase(meta, retrievingKnowledge ? "RETRIEVING" : "MODEL", now);
      db.update("UPDATE ai_task SET proposal_meta_json=? WHERE id=?", trips.encode(meta), id);
    }
    return true;
  }

  @Transactional
  public void completeInterpretation(String id, Map<String, Object> review) {
    var task = db.queryForMap("SELECT * FROM ai_task WHERE id=? FOR UPDATE", id);
    if (!"RUNNING".equals(task.get("status"))) return;
    if (!"INTERPRET".equals(task.get("task_type")))
      throw new BusinessException(409, "任务类型与需求理解结果不一致");
    db.update("UPDATE ai_task SET status='SUCCEEDED',result_json=?,quota_reserved=0 WHERE id=?",
        trips.encode(review), id);
  }

  /** Owner-scoped private snapshot, read only by the single claimed generation worker. */
  @Transactional
  public com.smarttrip.rag.TravelRagContext existingRagContext(String id) {
    var task = lockedTask(id);
    if (!"GENERATE".equals(task.get("task_type")))
      throw new BusinessException(409, "该任务不支持旅行知识检索");
    var meta = generationMetadata(task.get("proposal_meta_json"));
    if (!meta.containsKey("_ragContext")) return null;
    var snapshot = new LinkedHashMap<String, Object>();
    if (meta.get("rag") instanceof Map<?, ?> value)
      value.forEach((key, item) -> snapshot.put(String.valueOf(key), item));
    return new com.smarttrip.rag.TravelRagContext(Objects.toString(meta.get("_ragContext"), ""), snapshot);
  }

  @Transactional
  public TravelRequirement retrievalRequirement(String id) {
    var task = lockedTask(id);
    if (!"GENERATE".equals(task.get("task_type")) || !"RUNNING".equals(task.get("status")))
      throw new BusinessException(409, "任务尚未进入旅行知识检索阶段");
    return requirement(generationMetadata(task.get("proposal_meta_json")));
  }

  /** First snapshot wins; persistence must succeed before any model request is sent. */
  @Transactional
  public com.smarttrip.rag.TravelRagContext recordRagContext(String id, com.smarttrip.rag.TravelRagContext result) {
    var task = lockedTask(id);
    if (!"GENERATE".equals(task.get("task_type")) || !"RUNNING".equals(task.get("status")))
      throw new BusinessException(409, "任务状态已变化，未发起模型生成");
    var meta = generationMetadata(task.get("proposal_meta_json"));
    if (!meta.containsKey("_ragContext")) {
      if (result == null || result.promptContext() == null || result.snapshot() == null
          || result.promptContext().length() > 32000)
        throw new BusinessException(422, "旅行知识上下文超出安全限制，未发起模型生成");
      meta.put("_ragContext", result.promptContext());
      meta.put("rag", result.snapshot());
      if (db.update("UPDATE ai_task SET proposal_meta_json=? WHERE id=? AND user_id=? AND status='RUNNING'",
          trips.encode(meta), id, access.userId()) != 1)
        throw new BusinessException(409, "任务状态已变化，未发起模型生成");
    }
    var snapshot = new LinkedHashMap<String, Object>();
    if (meta.get("rag") instanceof Map<?, ?> value)
      value.forEach((key, item) -> snapshot.put(String.valueOf(key), item));
    return new com.smarttrip.rag.TravelRagContext(Objects.toString(meta.get("_ragContext"), ""), snapshot);
  }

  /** Persist a bounded internal diagnostic snapshot; raw model text is never projected by get(). */
  @Transactional
  public void recordModelOutput(String id, Map<String, Object> diagnostics) {
    var task = lockedTask(id);
    if (!"GENERATE".equals(task.get("task_type")) || !"RUNNING".equals(task.get("status"))
        || diagnostics == null || diagnostics.isEmpty()) return;
    var meta = generationMetadata(task.get("proposal_meta_json"));
    Map<?, ?> previous = meta.get("_modelDiagnostics") instanceof Map<?, ?> old ? old : Map.of();
    var snapshot = new LinkedHashMap<String, Object>();
    for (String field : List.of("responseText", "responseCharacters", "model", "requestId",
        "finishReason", "maxOutputTokens", "inputTokens", "outputTokens", "totalTokens",
        "usage", "shape", "stage", "failureCategory", "errorCode", "responseTruncated")) {
      Object value = diagnostics.containsKey(field) ? diagnostics.get(field) : previous.get(field);
      if (value == null || "responseText".equals(field) && !(value instanceof String)) continue;
      if (value instanceof String text) {
        int maximum = "responseText".equals(field) ? 200000 : 1000;
        if (text.length() > maximum) {
          if (Character.isHighSurrogate(text.charAt(maximum - 1))) maximum--;
          value = text.substring(0, maximum);
        }
      }
      snapshot.put(field, value);
    }
    if (snapshot.isEmpty()) return;
    meta.put("_modelDiagnostics", snapshot);
    db.update("UPDATE ai_task SET proposal_meta_json=? WHERE id=? AND user_id=?",
        trips.encode(meta), id, access.userId());
  }
  @Transactional
  public void complete(String id, TripDetailVO t) {
    var task = db.queryForMap("SELECT * FROM ai_task WHERE id=? FOR UPDATE", id);
    if (!"RUNNING".equals(task.get("status"))) return;
    String type = (String) task.get("task_type");
    if ("GENERATE".equals(type)) {
      var meta = generationMetadata(task.get("proposal_meta_json"));
      t = prepareGeneration(t, meta, true);
      meta.putIfAbsent("expiresAt", java.time.Instant.now().plusSeconds(86400).toString());
      appendPhase(meta, "PREVIEW", java.time.Instant.now().toString());
      db.update(
          "UPDATE ai_task SET status='PREVIEW',result_json=?,proposal_meta_json=?,quota_reserved=0 WHERE id=?",
          trips.encode(t), trips.encode(meta), id);
    } else {
      Long trip = ((Number) task.get("trip_id")).longValue();
      var current = access.writeLocked(trip);
      if (!Objects.equals(current.getVersion(), ((Number) task.get("base_version")).longValue()))
        throw new BusinessException(409, "路书已变化，请重新生成提案");
      AiModificationGuard.verify(trips.load(trip), t);
      trips.validate(t);
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
    String safeMessage = message == null || message.isBlank() ? "任务失败，请手动重试" : message;
    if (safeMessage.length() > 500) safeMessage = safeMessage.substring(0, 500);
    db.update(
        "UPDATE ai_task SET status='FAILED',quota_reserved=0,error_message=? WHERE id=?",
        safeMessage,
        id);
  }

  @Transactional
  public TripDetailVO apply(String id) {
    return apply(id, null);
  }

  @Transactional
  public TripDetailVO apply(String id, String title) {
    return apply(id, title, null);
  }

  @Transactional
  public TripDetailVO apply(String id, String title, Long expectedRevision) {
    var t = lockedTask(id);
    if ("APPLIED".equals(t.get("status")))
      return trips.load(((Number) t.get("trip_id")).longValue());
    if (!"PREVIEW".equals(t.get("status"))) throw new BusinessException(409, "任务没有可确认的预览");
    if ("GENERATE".equals(t.get("task_type"))) {
      var meta = generationMetadata(t.get("proposal_meta_json"));
      requireUnexpiredDraft(meta);
      requireDraftRevision(meta, expectedRevision, false);

      var draft = trips.decode((String) t.get("result_json"));
      if (title != null) draft.setTitle(title.strip());
      draft = prepareGeneration(draft, meta, false);
      requireSaveable(meta);
      // The save gate uses the same report as preview, plus the existing strict validators.
      trips.validate(draft);
      if (meta.get("requirement") != null) requirements.validate(requirement(meta), draft);
      var saved = trips.createGenerated(draft);
      db.update("UPDATE ai_task SET status='APPLIED',trip_id=?,result_json=? WHERE id=?",
          saved.getId(), trips.encode(saved), id);
      return saved;
    }
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
    if ("MODIFY".equals(t.get("task_type")))
      AiModificationGuard.verify(trips.load(current.getId()), proposal);
    var saved = trips.save(((Number) t.get("trip_id")).longValue(), proposal);
    db.update("UPDATE ai_task SET status='APPLIED' WHERE id=?", id);
    return saved;
  }

  @Transactional
  public Map<String, Object> updateDraft(String id, TripDetailVO draft, Long expectedRevision) {
    var task = lockedTask(id);
    if (!"GENERATE".equals(task.get("task_type")) || !"PREVIEW".equals(task.get("status")))
      throw new BusinessException(409, "仅未保存的生成草稿可以编辑");
    var meta = generationMetadata(task.get("proposal_meta_json"));
    requireUnexpiredDraft(meta);
    requireDraftRevision(meta, expectedRevision, true);
    long revision = draftRevision(meta);
    if (revision == Long.MAX_VALUE) throw new BusinessException(409, "草稿版本无法继续编辑，请重新生成");
    draft = prepareGeneration(draft, meta, false);
    meta.put("draftRevision", revision + 1);
    db.update("UPDATE ai_task SET result_json=?,proposal_meta_json=? WHERE id=? AND user_id=?",
        trips.encode(draft), trips.encode(meta), id, access.userId());
    return get(id);
  }

  /** Explicit budget revision only: no model request, quota reservation, or formal trip creation. */
  @Transactional
  public Map<String, Object> raiseBudget(String id, java.math.BigDecimal budgetMax,
      Boolean confirmed, Long expectedRevision, String requestKey) {
    if (!Boolean.TRUE.equals(confirmed)) throw new BusinessException(400, "请明确确认提高本次草稿的预算上限");
    if (budgetMax == null || budgetMax.signum() < 0 || budgetMax.compareTo(new java.math.BigDecimal("9999999")) > 0
        || budgetMax.stripTrailingZeros().scale() > 2)
      throw new BusinessException(400, "预算上限应为范围内的非负金额，最多两位小数");
    try {
      if (requestKey == null || !UUID.fromString(requestKey).toString().equalsIgnoreCase(requestKey))
        throw new IllegalArgumentException();
    } catch (IllegalArgumentException e) { throw new BusinessException(400, "请提供有效的预算修改请求标识"); }
    var task = lockedTask(id);
    if (!"GENERATE".equals(task.get("task_type")) || !"PREVIEW".equals(task.get("status")))
      throw new BusinessException(409, "仅未正式保存的生成草稿可以提高预算");
    var meta = generationMetadata(task.get("proposal_meta_json"));
    requireUnexpiredDraft(meta);
    var changes = new ArrayList<Map<String, Object>>();
    if (meta.get("budgetChanges") instanceof List<?> previous) {
      for (var entry : previous) {
        if (!(entry instanceof Map<?, ?> raw)) throw new BusinessException(422, "预算修改记录无法读取");
        var change = new LinkedHashMap<String, Object>();
        raw.forEach((key, value) -> change.put(key.toString(), value));
        changes.add(change);
        if (requestKey.equalsIgnoreCase(Objects.toString(change.get("requestKey"), ""))) {
          var after = change.get("after") instanceof Map<?, ?> value ? value : Map.of();
          boolean sameAmount;
          try { sameAmount = budgetMax.compareTo(new java.math.BigDecimal(Objects.toString(after.get("budgetMax")))) == 0; }
          catch (NumberFormatException e) { throw new BusinessException(422, "预算修改记录无法读取"); }
          if (!sameAmount || !Objects.equals(expectedRevision, change.get("draftRevision") instanceof Number n ? n.longValue() : null))
            throw new BusinessException(409, "同一预算修改标识不能用于不同金额或草稿版本");
          return get(id);
        }
      }
    }
    requireDraftRevision(meta, expectedRevision, true);
    long revision = draftRevision(meta);
    if (revision == Long.MAX_VALUE) throw new BusinessException(409, "草稿版本无法继续编辑");
    if (changes.size() >= 100) throw new BusinessException(409, "本草稿预算修订次数已达上限");
    var before = requirement(meta);
    if (before == null || !"CNY".equals(before.currency())
        || !Set.of("TOTAL", "PER_PERSON").contains(Objects.toString(before.budgetScope(), "")))
      throw new BusinessException(400, "仅支持已确认人民币总预算或人均预算的草稿");
    var currentLimit = before.budgetMax() != null ? before.budgetMax() : before.budget();
    if (currentLimit == null) throw new BusinessException(400, "此草稿尚无已确认预算上限，不能通过提高上限修改");
    if (budgetMax.compareTo(currentLimit) <= 0) throw new BusinessException(400, "新预算上限必须高于当前已确认上限");
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    var revised = mapper.valueToTree(before);
    ((com.fasterxml.jackson.databind.node.ObjectNode) revised).put("budgetMax", budgetMax);
    var after = mapper.convertValue(revised, TravelRequirement.class);
    var audit = new LinkedHashMap<String, Object>();
    audit.put("requestKey", requestKey);
    audit.put("before", budgetSnapshot(before));
    audit.put("after", budgetSnapshot(after));
    audit.put("userId", access.userId());
    audit.put("changedAt", java.time.Instant.now().toString());
    audit.put("draftRevision", revision);
    changes.add(audit);
    meta.putIfAbsent("originalRequirement", meta.get("requirement"));
    meta.put("requirement", after);
    meta.put("budgetChanges", changes);
    var draft = prepareGeneration(trips.decode((String) task.get("result_json")), meta, false);
    meta.put("draftRevision", revision + 1);
    db.update("UPDATE ai_task SET result_json=?,proposal_meta_json=? WHERE id=? AND user_id=?",
        trips.encode(draft), trips.encode(meta), id, access.userId());
    return get(id);
  }

  private Map<String, Object> budgetSnapshot(TravelRequirement r) {
    var limit = r.budgetMax() != null ? r.budgetMax() : r.budget();
    if ("PER_PERSON".equals(r.budgetScope())) {
      if (r.travelers() == null || r.travelers() < 1)
        throw new BusinessException(422, "人均预算缺少确认人数，不能猜测总上限");
      limit = limit.multiply(java.math.BigDecimal.valueOf(r.travelers()));
    }
    var value = new LinkedHashMap<String, Object>();
    value.put("budgetMax", r.budgetMax());
    value.put("totalCap", limit);
    value.put("budgetScope", r.budgetScope());
    value.put("currency", r.currency());
    value.put("travelers", r.travelers());
    return value;
  }

  private Map<String, Object> lockedTask(String id) {
    Long user = access.userId();
    Map<String, Object> task;
    try {
      // Lock before any snapshot read so concurrent idempotent APPLY can load the created trip.
      task = db.queryForMap("SELECT * FROM ai_task WHERE id=? AND user_id=? FOR UPDATE", id, user);
    } catch (org.springframework.dao.EmptyResultDataAccessException e) {
      throw new BusinessException(404, "任务不存在");
    }
    if (!Objects.equals(((Number) task.get("user_id")).longValue(), user))
      throw new BusinessException(404, "任务不存在");
    return task;
  }

  private void requireUnexpiredDraft(Map<String, Object> meta) {
    Object expires = meta.get("expiresAt");
    if (expires == null) throw new BusinessException(410, "生成草稿已过期，请重新生成；尚未保存路书");
    try {
      if (!java.time.Instant.now().isBefore(java.time.Instant.parse(expires.toString())))
        throw new BusinessException(410, "生成草稿已过期，请重新生成；尚未保存路书");
    } catch (java.time.format.DateTimeParseException e) {
      throw new BusinessException(422, "生成草稿有效期数据无效，请重新生成");
    }
  }

  private long draftRevision(Map<String, Object> meta) {
    Object revision = meta.get("draftRevision");
    if (!(revision instanceof Number)) throw new BusinessException(422, "草稿版本数据无效，请重新生成");
    try {
      long value = new java.math.BigDecimal(revision.toString()).longValueExact();
      if (value < 0) throw new ArithmeticException();
      return value;
    } catch (ArithmeticException e) {
      throw new BusinessException(422, "草稿版本数据无效，请重新生成");
    }
  }

  private void requireDraftRevision(Map<String, Object> meta, Long expected, boolean required) {
    if (expected == null) {
      if (required) throw new BusinessException(400, "保存草稿时请提供草稿版本");
      return;
    }
    if (expected < 0) throw new BusinessException(400, "草稿版本无效");
    if (expected != draftRevision(meta))
      throw new BusinessException(409, "草稿已被更新，请重新读取后再编辑或保存");
  }
  @SuppressWarnings("unchecked")
  private Map<String, Object> generationMetadata(Object raw) {
    var meta = new LinkedHashMap<String, Object>();
    if (raw != null) {
      Object value = trips.parseObject(raw.toString());
      if (!(value instanceof Map<?, ?>)) throw new BusinessException(422, "生成草稿需求数据无效");
      meta.putAll((Map<String, Object>) value);
    }
    meta.putIfAbsent("draftRevision", 0L);
    return meta;
  }

  private TravelRequirement requirement(Map<String, Object> meta) {
    try {
      return new com.fasterxml.jackson.databind.ObjectMapper()
          .convertValue(meta.get("requirement"), TravelRequirement.class);
    } catch (IllegalArgumentException e) {
      throw new BusinessException(422, "生成草稿需求数据无效，请重新生成");
    }
  }

  /** Normalization is deterministic; editable issues never become fabricated data or model retries. */
  private TripDetailVO prepareGeneration(TripDetailVO input, Map<String, Object> meta, boolean generated) {
    if (input == null || input.getDays() == null || input.getDays().isEmpty())
      throw new BusinessException(422, "模型输出不完整：缺少逐日计划，尚未保存正式路书");
    if (input.getDays().size() > 30) throw new BusinessException(422, "模型输出结构超出30天上限");
    for (var day : input.getDays()) {
      if (day == null || day.getItems() == null || day.getItems().stream().anyMatch(Objects::isNull)
          || generated && day.getItems().isEmpty())
        throw new BusinessException(422, "模型输出不完整：有日程缺少具体活动，尚未保存正式路书");
      if (day.getItems().size() > 40) throw new BusinessException(422, "单日活动超过40项，无法整理该草稿");
      if (generated) for (var item : day.getItems()) item.setScheduleLocked(false);
    }
    var r = meta.get("requirement") == null ? null : requirement(meta);
    var report = normalizer.normalize(input, r, generated);
    if (generated) appendPhase(meta, "VALIDATING", java.time.Instant.now().toString());
    var draft = report.draft();
    var warnings = new ArrayList<>(report.warnings());
    var issues = new ArrayList<>(report.issues());
    var corrections = new LinkedHashSet<String>();
    if (meta.get("corrections") instanceof List<?> previous)
      previous.stream().filter(String.class::isInstance).map(String.class::cast).forEach(corrections::add);
    corrections.addAll(report.corrections());
    int index = 0;
    for (var day : draft.getDays()) {
      index++;
      // Missing dates are a deterministic derivation; explicit conflicting dates stay visible as issues.
      if (day.getDayDate() == null && draft.getStartDate() != null)
        day.setDayDate(draft.getStartDate().plusDays(index - 1));
      if (day.getDayTheme() == null || day.getDayTheme().isBlank())
        issues.add(new AiDraftNormalizer.Issue("DAY_THEME", index, null, null, "请补充当天主题"));
      if (day.getItems().isEmpty())
        issues.add(new AiDraftNormalizer.Issue("DAY_EMPTY", index, null, null, "当天至少需要一项活动"));
    }
    if (draft.getSummary() == null || draft.getSummary().isBlank())
      issues.add(new AiDraftNormalizer.Issue("SUMMARY", null, null, null, "请补充路书整体概要"));
    // Workbench validation mutates unknown costs to zero: validate a copy, never the actual draft.
    try { trips.validate(trips.decode(trips.encode(draft))); }
    catch (BusinessException e) {
      issues.add(new AiDraftNormalizer.Issue("FIELD_VALIDATION", null, null, null, e.getMessage()));
    }
    if (r != null) {
      var review = requirements.inspect(r, draft);
      warnings.addAll(review.warnings());
      issues.addAll(review.issues());
    } else warnings.add("此历史任务未包含结构化需求，请逐项核对安排与预算。");
    trips.clearIds(draft);
    draft.setVersion(null);
    meta.put("corrections", List.copyOf(corrections));
    meta.put("issues", issues.stream().distinct().toList());
    meta.put("saveBlocked", !issues.isEmpty());
    meta.put("warnings", warnings.stream().distinct().toList());

    return draft;
  }

  private void requireSaveable(Map<String, Object> meta) {
    if (Boolean.TRUE.equals(meta.get("saveBlocked"))) {
      var issues = (List<?>) meta.get("issues");
      String message = !issues.isEmpty() && issues.get(0) instanceof AiDraftNormalizer.Issue issue
          ? issue.message() : "草稿仍有未解决问题";
      throw new BusinessException(422, "草稿尚不能保存：" + message + "；请编辑草稿后重新校验，无需再次调用模型");
    }
  }

  @Transactional
  public void setPhase(String id, String phase) {
    if (!Set.of("RETRIEVING", "MODEL", "MODEL_COMPLETED", "NORMALIZING", "VALIDATING").contains(phase))
      throw new IllegalArgumentException("Invalid generation phase");
    var task = lockedTask(id);
    if (!"GENERATE".equals(task.get("task_type")) || !"RUNNING".equals(task.get("status"))) return;
    var meta = generationMetadata(task.get("proposal_meta_json"));
    appendPhase(meta, phase, java.time.Instant.now().toString());
    db.update("UPDATE ai_task SET proposal_meta_json=? WHERE id=? AND user_id=?",
        trips.encode(meta), id, access.userId());
  }

  private void appendPhase(Map<String, Object> meta, String phase, String at) {
    var history = new ArrayList<Map<String, Object>>();
    if (meta.get("phaseHistory") instanceof List<?> previous)
      for (Object entry : previous) if (entry instanceof Map<?, ?> event
          && event.get("phase") instanceof String && event.get("at") instanceof String)
        history.add(Map.of("phase", event.get("phase"), "at", event.get("at")));
    if (history.isEmpty() || !phase.equals(history.get(history.size() - 1).get("phase")))
      history.add(Map.of("phase", phase, "at", at));
    if (history.size() > 24) history = new ArrayList<>(history.subList(history.size() - 24, history.size()));
    meta.put("phaseHistory", history);
    meta.put("phase", phase);
  }

  private boolean canRecover(Map<String, Object> meta) {
    try {
      requireUnexpiredDraft(meta);
      if (!(meta.get("_modelDiagnostics") instanceof Map<?, ?> diagnostics)) return false;
      if (!(diagnostics.get("responseText") instanceof String raw) || raw.isBlank()) return false;
      if (Boolean.TRUE.equals(diagnostics.get("responseTruncated"))) return false;
      if (diagnostics.get("responseCharacters") instanceof Number length && length.longValue() > raw.length()) return false;
      Object reason = diagnostics.get("finishReason");
      return reason == null || "STOP".equalsIgnoreCase(reason.toString());
    } catch (BusinessException e) { return false; }
  }

  /** Explicit owner action: replay the private saved response, without provider requests or quota changes. */
  @Transactional
  public Map<String, Object> recoverDraft(String id) {
    var task = lockedTask(id);
    if (!"GENERATE".equals(task.get("task_type"))) throw new BusinessException(409, "仅生成任务支持草稿恢复");
    if (Set.of("PREVIEW", "APPLIED").contains(task.get("status"))) return get(id);
    if (!"FAILED".equals(task.get("status"))) throw new BusinessException(409, "任务仍在处理中，不能恢复");
    var meta = generationMetadata(task.get("proposal_meta_json"));
    requireUnexpiredDraft(meta);
    if (!canRecover(meta)) throw new BusinessException(422, "此任务未保留可恢复的完整模型响应，无法恢复草稿");
    var diagnostics = (Map<?, ?>) meta.get("_modelDiagnostics");
    appendPhase(meta, "NORMALIZING", java.time.Instant.now().toString());
    var parsed = provider.parseGeneration((String) diagnostics.get("responseText"));
    var draft = prepareGeneration(trips.decode(parsed.json()), meta, true);
    appendPhase(meta, "PREVIEW", java.time.Instant.now().toString());
    meta.put("recoveredFromTaskId", id);
    db.update("UPDATE ai_task SET status='PREVIEW',result_json=?,proposal_meta_json=?,error_message=NULL,quota_reserved=0 WHERE id=? AND user_id=?",
        trips.encode(draft), trips.encode(meta), id, access.userId());
    return get(id);
  }

  @Transactional
  public void recover() {
    var rows = db.queryForList("SELECT id FROM ai_task WHERE status IN ('QUEUED','RUNNING')");
    for (var row : rows) fail((String) row.get("id"), "服务重启中断了任务，配额已退回，请重新发起");
  }
}

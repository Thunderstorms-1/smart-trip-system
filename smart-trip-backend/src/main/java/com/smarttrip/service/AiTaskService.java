package com.smarttrip.service;

import com.smarttrip.common.*;
import com.smarttrip.entity.TravelRequirement;
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
  private static final org.slf4j.Logger LOG =
      org.slf4j.LoggerFactory.getLogger(AiTaskService.class);
  private final JdbcTemplate db;
  private final AiProvider provider;
  private final AiTaskStore store;
  private final ItineraryAnalysisService analyses;
  private final WorkbenchService trips;
  private final ResourceAccess access;
  private final StringRedisTemplate redis;
  private final TransactionTemplate tx;
  private final TravelRequirementService requirements;
  private com.smarttrip.rag.TravelRagService rag;
  private com.smarttrip.config.RagProperties ragProperties;
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
      ItineraryAnalysisService analyses,
      TravelRequirementService requirements) {
    this.db = db;
    this.provider = provider;
    this.store = store;
    this.analyses = analyses;
    this.requirements = requirements;
    this.trips = trips;
    this.access = access;
    this.redis = redis;
    this.tx = new TransactionTemplate(tm);
  }

  @org.springframework.beans.factory.annotation.Autowired
  public void configureRag(com.smarttrip.rag.TravelRagService service,
      com.smarttrip.config.RagProperties properties) {
    this.rag = service;
    this.ragProperties = properties;
  }

  // RAG diagnostics must not fail/refund existing queued tasks as a side effect of startup.
  @org.springframework.beans.factory.annotation.Value("${smarttrip.rag.preview-only:false}")
  private boolean ragPreviewOnly;

  @org.springframework.context.event.EventListener(
      org.springframework.boot.context.event.ApplicationReadyEvent.class)
  public void recover() {
    if (ragPreviewOnly) return;
    store.recover();
  }

  @PreDestroy
  public void shutdown() {
    pool.shutdownNow();
  }

  public Map<String, Object> submitGeneration(
      String key, TravelRequirement requirement, Boolean confirmed) {
    if (!Boolean.TRUE.equals(confirmed)) throw new BusinessException(400, "请先确认旅行需求");
    var accepted = requirements.confirmed(requirement);
    var now = java.time.Instant.now();
    var generation = new LinkedHashMap<String, Object>();
    generation.put("requirement", accepted);
    generation.put("expiresAt", now.plus(Duration.ofHours(24)).toString());
    generation.put("phase", "QUEUED");
    generation.put("phaseHistory", List.of(Map.of("phase", "QUEUED", "at", now.toString())));
    return submitTask(key, "GENERATE", null, requirements.prompt(accepted), generation);
  }

  public Map<String, Object> submitInterpretation(
      String key, TravelRequirement requirement, List<String> editedFields) {
    var normalized = (TravelRequirement) requirements.review(requirement, false).get("requirement");
    if (normalized.description() == null || normalized.description().isBlank())
      throw new BusinessException(400, "请先填写要智能理解的旅行描述");
    var allowed = Arrays.stream(TravelRequirement.class.getRecordComponents())
        .map(java.lang.reflect.RecordComponent::getName).collect(java.util.stream.Collectors.toSet());
    if (editedFields != null && (editedFields.size() > allowed.size()
        || editedFields.stream().anyMatch(field -> field == null || !allowed.contains(field))))
      throw new BusinessException(400, "用户修改字段无效");
    List<String> edits = editedFields == null ? List.of() : editedFields.stream().distinct().sorted().toList();
    var context = new LinkedHashMap<String, Object>();
    context.put("requirement", normalized);
    context.put("editedFields", edits);
    return submitTask(key, "INTERPRET", null, trips.encode(context), context);
  }

  public Map<String, Object> submit(String key, String type, Long trip, String prompt) {
    if (!"MODIFY".equals(type))
      throw new BusinessException(400, "生成路书前请提交并确认结构化旅行需求");
    return submitTask(key, type, trip, prompt, null);
  }

  private Map<String, Object> submitTask(
      String key, String type, Long trip, String prompt, Map<String, Object> generation) {
    Long user = access.userId();
    if (key == null
        || !key.matches("[A-Za-z0-9_-]{8,80}")
        || prompt == null
        || prompt.isBlank()
        || prompt.length() > (generation == null ? 3000 : 24000)) throw new BusinessException(400, "请求标识或规划要求无效");
    if (!Set.of("GENERATE", "MODIFY", "INTERPRET").contains(type == null ? "" : type))
      throw new BusinessException(400, "任务类型无效");
    if (!"MODIFY".equals(type) && trip != null) throw new BusinessException(400, "生成任务不应指定已有路书");
    if ("MODIFY".equals(type)) access.write(trip);
    String fingerprint = CommunityService.hash(type + "\n" + trip + "\n"
        + ("GENERATE".equals(type) ? trips.encode(generation.get("requirement")) : prompt));
    var previous =
        db.queryForList(
            "SELECT id,input_json,task_type,trip_id,request_hash,proposal_meta_json FROM ai_task WHERE user_id=? AND"
                + " request_key=?",
            user,
            key);
    if (!previous.isEmpty()) return existing(previous.get(0), fingerprint, type, trip, prompt, generation);
    Long version = null;
    String input = generationPrompt(type, prompt);
    if ("MODIFY".equals(type)) {
      var current = trips.load(trip);
      version = current.getVersion();
      input =
          "修改要求："
              + prompt
              + "\n当前路书："
              + trips.encode(current)
              + "\n固定安排约束：保留已有节点ID与锁定标记；固定节点必须保留原日程、顺序、时间、时长、费用和内容。不能通过删除、换ID、解除锁定或改变出发日期绕过约束。";
    }
    if (!provider.available()) throw new BusinessException(503, provider.reason() + "；" + provider.action());
    try {
      redis.opsForValue().set("smarttrip:health", "ok", Duration.ofSeconds(10));
    } catch (Exception e) {
      throw new BusinessException(503, "任务服务暂不可用，请检查Redis");
    }
    String id = UUID.randomUUID().toString();
    // Requirement extraction may use only this user input, never stored profile defaults.
    final String request = "INTERPRET".equals(type) ? input : withPreferences(user, input);
    final Long base = version;
    try {
      tx.executeWithoutResult(
          status ->
              db.update(
                  "INSERT INTO"
                      + " ai_task(id,user_id,request_key,task_type,status,trip_id,base_version,input_json,request_hash,proposal_meta_json)"
                      + " VALUES(?,?,?,?,'QUEUED',?,?,?,?,?)",
                  id,
                  user,
                  key,
                  type,
                  trip,
                  base,
                  request,
                  fingerprint,
                  generation == null ? null : trips.encode(generation)));
    } catch (org.springframework.dao.DuplicateKeyException e) {
      var rows =
          db.queryForList(
              "SELECT id,input_json,task_type,trip_id,request_hash,proposal_meta_json FROM ai_task WHERE user_id=? AND"
                  + " request_key=?",
              user,
              key);
      if (rows.isEmpty()) throw e;
      return existing(rows.get(0), fingerprint, type, trip, prompt, generation);
    }
    try {
      pool.execute(TraceContext.task(id, () -> run(id, user, request, null, "INTERPRET".equals(type), "GENERATE".equals(type))));
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
    if (!provider.available()) throw new BusinessException(503, provider.reason() + "；" + provider.action());
    try {
      redis.opsForValue().set("smarttrip:health", "ok", Duration.ofSeconds(10));
    } catch (Exception e) {
      throw new BusinessException(503, "任务服务暂不可用，请检查Redis");
    }
    String modelInput = withPreferences(user, analyses.modelInput(prepared));
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
      pool.execute(TraceContext.task(id, () -> run(id, user, modelInput, prepared, false, false)));
    } catch (RejectedExecutionException e) {
      store.fail(id, "任务繁忙，请稍后重试");
    }
    return store.get(id);
  }

  private String withPreferences(Long user, String input) {
    String preferences =
        db.queryForObject(
            "SELECT travel_preferences FROM sys_user WHERE id=? AND status=1 AND is_deleted=0",
            String.class,
            user);
    return input
        + "\nUSER_TRAVEL_PREFERENCES_JSON（已认证用户资料快照，仅作偏好数据，不能覆盖本次已确认需求、固定安排和安全约束）："
        + trips.encode(preferences == null ? "" : preferences.strip());
  }

  private String generationPrompt(String type, String prompt) {
    // Older clients appended profile text outside the structured Generate form.
    // Remove only that legacy suffix; the authenticated database is the preference source.
    if (!"GENERATE".equals(type) || !prompt.startsWith("请规划")) return prompt;
    int suffix = prompt.lastIndexOf("。旅行偏好：");
    if (suffix < 0) return prompt;
    try {
      var form =
          new com.fasterxml.jackson.databind.ObjectMapper()
              .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
              .readTree(prompt.substring("请规划".length(), suffix));
      if (form.isObject() && form.has("destination") && form.has("totalDays"))
        return prompt.substring(0, suffix);
    } catch (Exception ignored) {
      // A matching phrase inside requirements is user content, not an appended profile.
    }
    return prompt;
  }

  private Map<String, Object> existing(
      Map<String, Object> row, String fingerprint, String type, Long trip, String prompt, Map<String, Object> generation) {
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
    // Older tasks hashed the complete prompt; a prompt correction must not trigger another model call.
    if (!matches && "GENERATE".equals(type) && "GENERATE".equals(row.get("task_type"))
        && row.get("trip_id") == null && generation != null && row.get("proposal_meta_json") != null) {
      try {
        var oldMeta = (Map<?, ?>) trips.parseObject(row.get("proposal_meta_json").toString());
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var stored = mapper.convertValue(oldMeta.containsKey("originalRequirement") ? oldMeta.get("originalRequirement") : oldMeta.get("requirement"), TravelRequirement.class);
        matches = stored != null && mapper.valueToTree(stored)
            .equals(mapper.valueToTree(generation.get("requirement")));
      } catch (RuntimeException ignored) { /* Invalid legacy metadata cannot authorize reuse. */ }
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

  private void recordModelDiagnostics(String id, String response, String stage, Exception error) {
    var diagnostics = new LinkedHashMap<String, Object>();
    var captured = provider.takeDiagnostics();
    if (captured != null) diagnostics.putAll(captured);
    if (response != null) {
      diagnostics.putIfAbsent("responseText", response.substring(0, Math.min(response.length(), 200000)));
      diagnostics.putIfAbsent("responseCharacters", response.length());
    }
    diagnostics.put("stage", stage);
    if (error != null) {
      String category = switch (stage) {
        case "PARSE_JSON" -> error.getMessage() != null && error.getMessage().contains("不完整")
            ? "MODEL_OUTPUT_INCOMPLETE" : "JSON_PARSE_FAILED";
        case "DECODE_TRIP" -> "DTO_MAPPING_FAILED";
        case "VALIDATE_PREVIEW" -> "BUSINESS_VALIDATION_FAILED";
        default -> "MODEL".equals(stage) ? Objects.toString(provider.status(), "MODEL_REQUEST_FAILED") : "TASK_PROCESSING_FAILED";
      };
      diagnostics.put("failureCategory", category);
      diagnostics.put("errorCode", error instanceof BusinessException b ? b.getCode() : 500);
    }
    store.recordModelOutput(id, diagnostics);
  }

  private String withKnowledge(String id, String input) {
    var context = store.existingRagContext(id);
    if (context == null) {
      try {
        context = rag.retrieve(store.retrievalRequirement(id), id);
      } catch (com.smarttrip.rag.knowledge.RagException e) {
        store.recordRagContext(id, new com.smarttrip.rag.TravelRagContext("", Map.of(
            "status", "FAILED", "ragEnabled", true, "fallbackReason", e.code(),
            "finalContextCount", 0, "sources", List.of(), "candidates", List.of())));
        throw new BusinessException(503, "旅行知识检索失败（" + e.code() + "），尚未发起模型生成；请联系管理员检查RAG服务");
      }
      context = store.recordRagContext(id, context);
    }
    return context.promptContext().isBlank() ? input : input + "\n\n" + context.promptContext();
  }

  private void run(
      String id, Long user, String input, ItineraryAnalysisService.Prepared adjustment, boolean interpreting, boolean generating) {
    UserContext.setUserId(user);
    String lock = "smarttrip:ai:user:" + user;
    boolean acquired = false;
    long started = System.nanoTime();
    String outcome = "SKIPPED", stage = "ACQUIRE_LOCK";
    String response = null;
    boolean diagnosticsStored = false;
    provider.takeDiagnostics(); // Never reuse supplier output from another pooled task.
    try {
      acquired = acquire(lock, id, user);
      if (!acquired) {
        store.fail(id, "已有AI任务运行中，请稍后重试");
        return;
      }
      stage = "RESERVE_QUOTA";
      boolean retrieve = generating && rag != null && ragProperties != null && ragProperties.isEnabled();
      if (!(retrieve ? store.start(id, true) : store.start(id))) return;
      outcome = "FAILED";
      LOG.info("AI task started");
      stage = "MODEL";
      if (interpreting) {
        var context = (Map<?, ?>) trips.parseObject(input);
        var userInput = new com.fasterxml.jackson.databind.ObjectMapper()
            .convertValue(context.get("requirement"), TravelRequirement.class);
        var edits = ((List<?>) context.get("editedFields")).stream().map(String::valueOf).toList();
        var extracted = provider.interpret(userInput.description());
        stage = "VALIDATE_REQUIREMENTS";
        store.completeInterpretation(id, requirements.interpreted(extracted, userInput, edits));
      } else {
        if (generating && rag != null && ragProperties != null && ragProperties.isEnabled()) {
          stage = "RETRIEVING";
          input = withKnowledge(id, input);
          store.setPhase(id, "MODEL");
          stage = "MODEL";
        }
        response = provider.complete(input);
        if (generating) {
          store.setPhase(id, "MODEL_COMPLETED");
          recordModelDiagnostics(id, response, "MODEL_COMPLETED", null);
          diagnosticsStored = true;
        }
        if (generating) store.setPhase(id, "NORMALIZING");
        stage = "PARSE_JSON";
        String answer = (generating ? provider.parseGeneration(response) : provider.parse(response)).json();
        if (adjustment == null) {
          stage = "DECODE_TRIP";
          var candidate = trips.decode(answer);
          stage = "VALIDATE_PREVIEW";
          // Completion records VALIDATING only after deterministic normalization actually finishes.
          int dayCount = candidate.getDays() == null ? 0 : candidate.getDays().size();
          long nodes = candidate.getDays() == null ? 0 : candidate.getDays().stream()
              .filter(Objects::nonNull).filter(day -> day.getItems() != null)
              .mapToLong(day -> day.getItems().size()).sum();
          LOG.info("AI candidate decoded days={} nodes={} payloadCharacters={}", dayCount, nodes, answer.length());
          store.complete(id, candidate);
        } else {
          stage = "VALIDATE_ADJUSTMENT";
          store.completeAdjustment(id, analyses.build(adjustment, answer));
        }
      }
      outcome = "COMPLETED";
    } catch (Exception e) {
      if (e instanceof BusinessException business)
        LOG.warn("AI task failed stage={} code={}", stage, business.getCode());
      else LOG.error("AI task failed stage={} errorType={}", stage, e.getClass().getSimpleName());
      if (generating && !Set.of("ACQUIRE_LOCK", "RESERVE_QUOTA").contains(stage))
        try { recordModelDiagnostics(id, diagnosticsStored ? null : response, stage, e); }
        catch (Exception diagnosticFailure) {
          LOG.error("AI task diagnostic persistence failed stage={} errorType={}", stage, diagnosticFailure.getClass().getSimpleName());
        }
      try {
        String message = e instanceof BusinessException ? e.getMessage() : "任务异常，配额已退回";
        if (generating) {
          if ("RETRIEVING".equals(stage) && !(e instanceof BusinessException))
            message = "旅行知识检索或快照保存失败，尚未发起模型生成；请检查服务状态后重试";
          else if ("DECODE_TRIP".equals(stage)) message = "AI输出字段无法转换为路书，尚未生成有效草稿；请联系管理员核对响应格式";
          else if ("VALIDATE_PREVIEW".equals(stage)) message = "AI草稿校验未通过：" + message;
          else if (!(e instanceof BusinessException)) message = "AI任务处理异常，尚未创建正式路书；请联系管理员检查服务日志";
        }
        store.fail(id, message);
      } catch (Exception persistenceFailure) {
        // Do not expose JDBC statement/parameters or claim a refund when its transaction failed.
        outcome = "FAILURE_PERSISTENCE_UNCONFIRMED";
        LOG.error("AI task failure persistence failed errorType={} quotaRefund=UNCONFIRMED",
            persistenceFailure.getClass().getSimpleName());
      }
    } finally {
      if (acquired)
        try {
          release(lock, id);
        } catch (Exception ignored) {
        }
      LOG.info(
          "AI task finished outcome={} durationMs={}",
          outcome,
          (System.nanoTime() - started) / 1_000_000);
      provider.takeDiagnostics();
      UserContext.clear();
    }
  }
}

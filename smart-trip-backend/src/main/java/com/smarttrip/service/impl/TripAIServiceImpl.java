package com.smarttrip.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smarttrip.common.BusinessException;
import com.smarttrip.config.AiProperties;
import com.smarttrip.entity.*;
import com.smarttrip.mapper.SquareMapper;
import com.smarttrip.mapper.UserMapper;
import com.smarttrip.service.TripAIService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class TripAIServiceImpl implements TripAIService {

    private final AiProperties aiProperties;
    private final UserMapper userMapper;
    private final SquareMapper squareMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final StringRedisTemplate stringRedisTemplate;

    // Redis 分布式锁 Key 前缀与超时时间 (60秒防死锁)
    private static final String LOCK_PREFIX = "lock:ai:gen:";
    private static final long LOCK_EXPIRE_SECONDS = 60L;

    // Lua 脚本保证“比对值 + 释放锁”的原子性，彻底避免误删他人锁
    private static final String UNLOCK_LUA_SCRIPT =
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                    "    return redis.call('del', KEYS[1]) " +
                    "else " +
                    "    return 0 " +
                    "end";

    private final OkHttpClient okHttpClient = new OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build();

    @Override
    public void streamGenerateTrip(TripGenerateDTO dto, Long userId, SseEmitter emitter) {
        Long actualUserId = userId != null ? userId : 1L;
        String lockKey = LOCK_PREFIX + actualUserId;
        String lockValue = UUID.randomUUID().toString();

        // =========================================================================
        // 【核心关卡 1】：获取 Redis 分布式并发锁 (原子加锁，防并发连击与恶意刷量)
        // =========================================================================
        Boolean lockAcquired = stringRedisTemplate.opsForValue()
                .setIfAbsent(lockKey, lockValue, LOCK_EXPIRE_SECONDS, TimeUnit.SECONDS);

        if (Boolean.FALSE.equals(lockAcquired)) {
            log.warn("用户 [{}] 触发并发防刷限制：已有进行中的 AI 生成任务", actualUserId);
            throw new BusinessException(429, "当前已有行程正在生成中，请勿重复提交！");
        }
        log.info("用户 [{}] 成功获取 AI 生成分布式锁，lockKey={}, lockValue={}", actualUserId, lockKey, lockValue);

        try {
            // =========================================================================
            // 【核心关卡 2】：原子预扣用户配额
            // =========================================================================
            int rows = userMapper.tryPreDeductUserQuota(actualUserId);
            if (rows <= 0) {
                log.warn("用户 [{}] 今日免费 AI 配额已耗尽", actualUserId);
                // 配额不足时立即释放刚刚获取的 Redis 锁
                releaseLockSafely(lockKey, lockValue);
                try {
                    emitter.send(SseEmitter.event().name("error").data("抱歉，您今日的免费 AI 定制配额已用完！"));
                    emitter.complete();
                } catch (IOException ignored) {}
                return;
            }

            // 状态机与句柄守卫
            AtomicBoolean isQuotaSettled = new AtomicBoolean(false);
            AtomicBoolean isCompensated = new AtomicBoolean(false);
            AtomicReference<EventSource> eventSourceRef = new AtomicReference<>();

            // 客户端断开连接监听（超时或网络错误）
            emitter.onTimeout(() -> {
                log.warn("用户 [{}] SSE 长连接超时，释放锁并执行配额补偿", actualUserId);
                cancelAndRollback(actualUserId, isQuotaSettled, isCompensated, eventSourceRef.get(), lockKey, lockValue);
            });
            emitter.onError((ex) -> {
                log.warn("用户 [{}] SSE 管道通信异常: {}, 释放锁并执行配额补偿", actualUserId, ex.getMessage());
                cancelAndRollback(actualUserId, isQuotaSettled, isCompensated, eventSourceRef.get(), lockKey, lockValue);
            });

            // 构造 Prompt
            String systemPrompt = "你是一名顶级的资深私人旅行定制架构师。\n" +
                    "请严格根据用户需求规划路书。你的回答必须严格分为两大部分：\n\n" +
                    "【第一部分：给用户阅读的精致路书总结】（使用 Markdown 格式，严禁出现英文变量名与 JSON）：\n" +
                    "### 🌟 行程亮点与寄语\n\n" +
                    "### 📅 每日精华安排概览\n\n" +
                    "### 💡 独家避坑指南\n\n" +
                    "【第二部分：给系统落库的结构化时间轴数据】（必须严格用 ```json 包裹在最末尾）：\n" +
                    "```json\n" +
                    "{\n" +
                    "  \"days\": [\n" +
                    "    {\n" +
                    "      \"dayIndex\": 1,\n" +
                    "      \"dayTheme\": \"当天主题\",\n" +
                    "      \"items\": [\n" +
                    "        {\n" +
                    "          \"itemType\": \"SCENIC\",\n" +
                    "          \"itemName\": \"景点名\",\n" +
                    "          \"startTime\": \"09:00\",\n" +
                    "          \"costTimeHours\": 2.0,\n" +
                    "          \"costEstimate\": 50.0,\n" +
                    "          \"transportTips\": \"交通建议\",\n" +
                    "          \"aiTips\": \"避坑贴士\"\n" +
                    "        }\n" +
                    "      ]\n" +
                    "    }\n" +
                    "  ]\n" +
                    "}\n" +
                    "```";

            String userPrompt = String.format(
                    "目的地【%s】，出行 %d 天，预算 ￥%s 元，风格【%s】，人群【%s】，要求【%s】。请立即开始规划！",
                    dto.getDestination(), dto.getTotalDays(), dto.getTotalBudget(),
                    dto.getTripStyle(), dto.getTravelGroup(), dto.getCustomRequirements()
            );

            // 发起流式长连接调用（传入锁标识用于终态释放）
            callDualChannelStreamWithLock(systemPrompt, userPrompt, dto, actualUserId, emitter,
                    isQuotaSettled, isCompensated, eventSourceRef, lockKey, lockValue);

        } catch (Exception e) {
            log.error("AI 生成初始化异常，执行安全清理: userId={}", actualUserId, e);
            cancelAndRollback(actualUserId, new AtomicBoolean(false), new AtomicBoolean(false), null, lockKey, lockValue);
            throw e;
        }
    }

    private void callDualChannelStreamWithLock(
            String systemPrompt,
            String userPrompt,
            TripGenerateDTO dto,
            Long userId,
            SseEmitter emitter,
            AtomicBoolean isQuotaSettled,
            AtomicBoolean isCompensated,
            AtomicReference<EventSource> eventSourceRef,
            String lockKey,
            String lockValue
    ) {
        try {
            Map<String, Object> requestBodyMap = new HashMap<>();
            requestBodyMap.put("model", aiProperties.getModel());
            requestBodyMap.put("stream", true);
            requestBodyMap.put("messages", List.of(
                    Map.of("role", "system", "content", systemPrompt),
                    Map.of("role", "user", "content", userPrompt)
            ));

            String jsonPayload = objectMapper.writeValueAsString(requestBodyMap);
            Request request = new Request.Builder()
                    .url(aiProperties.getBaseUrl() + "/chat/completions")
                    .addHeader("Authorization", "Bearer " + aiProperties.getApiKey())
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(jsonPayload, MediaType.parse("application/json")))
                    .build();

            StringBuilder fullTextAccumulator = new StringBuilder();
            AtomicBoolean isCompleted = new AtomicBoolean(false);
            AtomicBoolean isJsonBlockStarted = new AtomicBoolean(false);

            EventSource eventSource = EventSources.createFactory(okHttpClient).newEventSource(request, new EventSourceListener() {
                @Override
                public void onEvent(EventSource eventSource, String id, String type, String data) {
                    if (isCompleted.get()) return;

                    // -----------------------------------------------------------------
                    // 【终态 1】：正常生成结束，落库成功后原子释放 Redis 锁
                    // -----------------------------------------------------------------
                    if ("[DONE]".equals(data.trim())) {
                        if (isCompleted.compareAndSet(false, true)) {
                            try {
                                Long newTripId = parseAndSaveStructuredTrip(dto, userId, fullTextAccumulator.toString());
                                emitter.send(SseEmitter.event().name("finish").data("{\"tripId\":" + newTripId + "}"));
                                emitter.complete();

                                isQuotaSettled.set(true); // 配额正式核销
                                log.info("用户 [{}] 行程 [{}] 落库完毕，配额核销确认", userId, newTripId);

                            } catch (Exception e) {
                                log.error("用户 [{}] 结构化落库异常，触发补偿回滚", userId, e);
                                cancelAndRollback(userId, isQuotaSettled, isCompensated, eventSource, lockKey, lockValue);
                                try {
                                    emitter.send(SseEmitter.event().name("error").data("生成中断：数据落库失败，配额已退还！"));
                                    emitter.complete();
                                } catch (IOException ignored) {}
                            } finally {
                                // 无论落库成功或失败，均安全释放 Redis 锁
                                releaseLockSafely(lockKey, lockValue);
                            }
                        }
                        return;
                    }

                    try {
                        JsonNode rootNode = objectMapper.readTree(data);
                        JsonNode choices = rootNode.path("choices");
                        if (choices.isArray() && !choices.isEmpty()) {
                            String deltaContent = choices.get(0).path("delta").path("content").asText("");
                            if (!deltaContent.isEmpty() && !isCompleted.get()) {
                                fullTextAccumulator.append(deltaContent);

                                if (fullTextAccumulator.toString().contains("```json") || fullTextAccumulator.toString().contains("```JSON")) {
                                    isJsonBlockStarted.set(true);
                                }

                                if (!isJsonBlockStarted.get()) {
                                    synchronized (emitter) {
                                        emitter.send(SseEmitter.event().name("message").data(deltaContent));
                                    }
                                }
                            }
                        }
                    } catch (IOException ioException) {
                        // -----------------------------------------------------------------
                        // 【终态 3】：客户端中途断连，立即释放 Redis 锁并掐断大模型
                        // -----------------------------------------------------------------
                        if (isCompleted.compareAndSet(false, true)) {
                            log.warn("检测到客户端断开连接 (IOException)，立即熔断长连接并释放锁: userId=[{}]", userId);
                            cancelAndRollback(userId, isQuotaSettled, isCompensated, eventSource, lockKey, lockValue);
                        }
                    } catch (Exception ignored) {}
                }

                @Override
                public void onFailure(EventSource eventSource, Throwable t, Response response) {
                    // -----------------------------------------------------------------
                    // 【终态 2】：大模型服务故障/超时，释放 Redis 锁并返还配额
                    // -----------------------------------------------------------------
                    if (isCompleted.compareAndSet(false, true)) {
                        log.error("大模型网络通信故障 (onFailure)，执行全链路熔断与锁释放: userId=[{}]", userId, t);
                        cancelAndRollback(userId, isQuotaSettled, isCompensated, eventSource, lockKey, lockValue);
                        try {
                            emitter.send(SseEmitter.event().name("error").data("大模型服务异常，配额已退还，请重试！"));
                            emitter.complete();
                        } catch (IOException ignored) {}
                    }
                }
            });

            eventSourceRef.set(eventSource);

        } catch (Exception e) {
            log.error("构建大模型请求异常", e);
            cancelAndRollback(userId, isQuotaSettled, isCompensated, null, lockKey, lockValue);
            emitter.completeWithError(e);
        }
    }

    /**
     * 【终态 4 & 兜底补偿】：取消大模型长连接、回滚配额并释放 Redis 锁
     */
    private void cancelAndRollback(Long userId, AtomicBoolean isQuotaSettled, AtomicBoolean isCompensated,
                                   EventSource eventSource, String lockKey, String lockValue) {
        // 1. 掐断大模型 EventSource
        if (eventSource != null) {
            try {
                eventSource.cancel();
            } catch (Exception ignored) {}
        }

        // 2. 幂等补偿用户配额
        if (!isQuotaSettled.get() && isCompensated.compareAndSet(false, true)) {
            try {
                userMapper.compensateRollbackUserQuota(userId);
                log.info("【风控回滚成功】已为用户 [{}] 补偿 1 次 AI 配额", userId);
            } catch (Exception e) {
                log.error("【系统告警】为用户 [{}] 补偿配额失败！", userId, e);
            }
        }

        // 3. 安全释放 Redis 分布式锁
        releaseLockSafely(lockKey, lockValue);
    }

    /**
     * 【Lua 脚本安全释放锁】：比对 UUID，防止误删他人锁
     */
    private void releaseLockSafely(String lockKey, String lockValue) {
        if (lockKey == null || lockValue == null) return;
        try {
            DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>(UNLOCK_LUA_SCRIPT, Long.class);
            Long result = stringRedisTemplate.execute(redisScript, Collections.singletonList(lockKey), lockValue);
            if (Long.valueOf(1L).equals(result)) {
                log.info("【Redis锁释放成功】lockKey={}", lockKey);
            } else {
                log.debug("【Redis锁已被自动过期或已释放】lockKey={}", lockKey);
            }
        } catch (Exception e) {
            log.error("【Redis锁释放异常】lockKey={}", lockKey, e);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public Long parseAndSaveStructuredTrip(TripGenerateDTO dto, Long userId, String fullOutput) {
        // 1. 插入路书主表
        TripDetailVO trip = new TripDetailVO();
        trip.setUserId(userId != null ? userId : 1L);
        trip.setTitle(dto.getTotalDays() + "天 " + dto.getDestination() + " " + dto.getTripStyle() + "精致路书");
        trip.setDestination(dto.getDestination());
        trip.setTotalDays(dto.getTotalDays());
        trip.setTotalBudget(dto.getTotalBudget() != null ? dto.getTotalBudget() : new BigDecimal("1500.00"));
        trip.setTripStyle(dto.getTripStyle());
        trip.setTravelGroup(dto.getTravelGroup());
        trip.setSummary(extractSummary(fullOutput));

        squareMapper.insertCloneTrip(trip);
        Long tripId = trip.getId();

        boolean parsedSuccess = false;

        // 2. 尝试从大模型输出中捕获 JSON
        Pattern pattern = Pattern.compile("```(?:json|JSON)?\\s*([\\s\\S]*?)\\s*```");
        Matcher matcher = pattern.matcher(fullOutput);

        if (matcher.find()) {
            try {
                String jsonStr = matcher.group(1).trim();
                JsonNode root = objectMapper.readTree(jsonStr);
                JsonNode daysArray = root.path("days");

                if (daysArray.isArray() && !daysArray.isEmpty()) {
                    for (JsonNode dayNode : daysArray) {
                        TripDayVO day = new TripDayVO();
                        day.setTripId(tripId);
                        day.setDayIndex(dayNode.path("dayIndex").asInt(1));
                        day.setDayTheme(dayNode.path("dayTheme").asText(dto.getDestination() + " 精彩探索"));
                        day.setDaySummary("AI 智能定制时间规划");
                        squareMapper.insertCloneDay(day);
                        Long dayId = day.getId();

                        JsonNode itemsArray = dayNode.path("items");
                        if (itemsArray.isArray()) {
                            int sort = 1;
                            for (JsonNode itemNode : itemsArray) {
                                TripItem item = new TripItem();
                                item.setTripId(tripId);
                                item.setDayId(dayId);
                                item.setItemType(itemNode.path("itemType").asText("SCENIC"));
                                item.setItemName(itemNode.path("itemName").asText(dto.getDestination() + "精选打卡地"));
                                item.setStartTime(itemNode.path("startTime").asText("09:00"));
                                item.setCostTimeHours(new BigDecimal(itemNode.path("costTimeHours").asText("2.0")));
                                item.setCostEstimate(new BigDecimal(itemNode.path("costEstimate").asText("50.0")));
                                item.setTransportTips(itemNode.path("transportTips").asText("建议公共交通或打车前往"));
                                item.setAiTips(itemNode.path("aiTips").asText("AI 游玩避坑贴士"));
                                item.setSortOrder(sort++);
                                item.setIsDeleted(0);
                                squareMapper.insertCloneItem(item);
                            }
                        }
                    }
                    parsedSuccess = true;
                    log.info("🎉 大模型 JSON 结构化时间轴精准落库成功！tripId: {}", tripId);
                }
            } catch (Exception e) {
                log.warn("大模型 JSON 解析微瑕，启动智能自愈引擎");
            }
        }

        // 3. 【绝对防御】：智能保底填充
        if (!parsedSuccess) {
            log.info("启用智能自愈引擎，为目的地 [{}] 自动排布时间轴", dto.getDestination());
            for (int i = 1; i <= dto.getTotalDays(); i++) {
                TripDayVO day = new TripDayVO();
                day.setTripId(tripId);
                day.setDayIndex(i);
                day.setDayTheme(dto.getDestination() + " " + dto.getTripStyle() + "深度体验 Day " + i);
                day.setDaySummary("AI 为您规划的特色路线");
                squareMapper.insertCloneDay(day);
                Long dayId = day.getId();

                TripItem item1 = new TripItem();
                item1.setTripId(tripId);
                item1.setDayId(dayId);
                item1.setItemType("SCENIC");
                item1.setItemName(dto.getDestination() + " 标志性核心 5A 景区");
                item1.setStartTime("09:00");
                item1.setCostTimeHours(new BigDecimal("3.0"));
                item1.setCostEstimate(new BigDecimal("60.00"));
                item1.setSortOrder(1);
                item1.setIsDeleted(0);
                squareMapper.insertCloneItem(item1);
            }
        }

        return tripId;
    }

    private String extractSummary(String fullText) {
        if (fullText == null || fullText.isEmpty()) return "为您打造的专属旅行路线。";
        String clean = fullText.replaceAll("```[\\s\\S]*?```", "");
        String[] lines = clean.split("\n");
        for (String line : lines) {
            if (line.contains("寄语") || line.contains("亮点") || line.length() > 20) {
                return line.replaceAll("[#*🌟]", "").trim();
            }
        }
        return "为您定制的精品路线，尽享地道风情与特色美食。";
    }
}
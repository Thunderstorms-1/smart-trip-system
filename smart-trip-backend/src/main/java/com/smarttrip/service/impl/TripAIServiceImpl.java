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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    private final OkHttpClient okHttpClient = new OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build();

    @Override
    public void streamGenerateTrip(TripGenerateDTO dto, Long userId, SseEmitter emitter) {
        log.info("AI 流式生成请求启动: 用户ID [{}], 目的地: {}, 天数: {}", userId, dto.getDestination(), dto.getTotalDays());

        // ---------------------------------------------------------------------
        // 1. 【风控边界】：原子 CAS 预扣配额，杜绝超频调用
        // ---------------------------------------------------------------------
        int rows = userMapper.tryPreDeductUserQuota(userId);
        if (rows <= 0) {
            log.warn("用户 [{}] 今日免费 AI 配额已耗尽，拒绝生成", userId);
            try {
                emitter.send(SseEmitter.event().name("error").data("抱歉，您今日的免费 AI 定制配额已用完！"));
                emitter.complete();
            } catch (IOException ignored) {}
            return;
        }
        log.info("用户 [{}] 配额预扣成功，当前已进入冻结状态", userId);

        // 状态标记与句柄绑定（用于多分支安全回滚）
        AtomicBoolean isQuotaSettled = new AtomicBoolean(false); // 是否已最终确认消费
        AtomicBoolean isCompensated = new AtomicBoolean(false);  // 是否已经执行过回滚
        AtomicReference<EventSource> eventSourceRef = new AtomicReference<>();

        // 绑定客户端断连监听（如用户关闭标签页、拔掉网线）
        emitter.onCompletion(() -> log.debug("SseEmitter 完成"));
        emitter.onTimeout(() -> {
            log.warn("用户 [{}] SSE 长连接心跳超时，触发安全补偿与流关闭", userId);
            cancelAndRollback(userId, isQuotaSettled, isCompensated, eventSourceRef.get());
        });
        emitter.onError((ex) -> {
            log.warn("用户 [{}] SSE 管道通信异常断开: {}, 触发安全补偿", userId, ex.getMessage());
            cancelAndRollback(userId, isQuotaSettled, isCompensated, eventSourceRef.get());
        });

        // 2. 构造 Prompt
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

        // 3. 发起调用
        callDualChannelStreamWithRollback(systemPrompt, userPrompt, dto, userId, emitter, isQuotaSettled, isCompensated, eventSourceRef);
    }

    private void callDualChannelStreamWithRollback(
            String systemPrompt,
            String userPrompt,
            TripGenerateDTO dto,
            Long userId,
            SseEmitter emitter,
            AtomicBoolean isQuotaSettled,
            AtomicBoolean isCompensated,
            AtomicReference<EventSource> eventSourceRef
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

                    if ("[DONE]".equals(data.trim())) {
                        if (isCompleted.compareAndSet(false, true)) {
                            try {
                                // -------------------------------------------------------------
                                // 核心步骤：解析并持久化落库（如果抛出异常，触发配额补偿）
                                // -------------------------------------------------------------
                                Long newTripId = parseAndSaveStructuredTrip(dto, userId, fullTextAccumulator.toString());

                                // 向客户端推送完成标记
                                emitter.send(SseEmitter.event().name("finish").data("{\"tripId\":" + newTripId + "}"));
                                emitter.complete();

                                // 标记配额正式核销完成（不可再回滚）
                                isQuotaSettled.set(true);
                                log.info("用户 [{}] 行程 [{}] AI生成全链路成功，配额正式完成核销", userId, newTripId);

                            } catch (Exception e) {
                                log.error("用户 [{}] 数据解析/落库阶段发生致命异常，执行配额补偿回滚", userId, e);
                                cancelAndRollback(userId, isQuotaSettled, isCompensated, eventSource);
                                try {
                                    emitter.send(SseEmitter.event().name("error").data("生成中断：数据结构化落库失败，配额已全额返还！"));
                                    emitter.complete();
                                } catch (IOException ignored) {}
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
                        // -------------------------------------------------------------
                        // 边界 2：客户端中途关闭连接/浏览器导致的写入中断
                        // -------------------------------------------------------------
                        log.warn("用户 [{}] 客户端断开连接 (IOException)，立即掐断大模型连接并回滚配额", userId);
                        isCompleted.set(true);
                        cancelAndRollback(userId, isQuotaSettled, isCompensated, eventSource);
                    } catch (Exception ignored) {}
                }

                @Override
                public void onFailure(EventSource eventSource, Throwable t, Response response) {
                    // -----------------------------------------------------------------
                    // 边界 1：大模型 API 通信故障/限流/鉴权失败
                    // -----------------------------------------------------------------
                    if (isCompleted.compareAndSet(false, true)) {
                        log.error("用户 [{}] 调用大模型 API 异常 (onFailure): {}", userId, t.getMessage());
                        cancelAndRollback(userId, isQuotaSettled, isCompensated, eventSource);
                        try {
                            emitter.send(SseEmitter.event().name("error").data("大模型网络故障，生成已中止，本次配额已自动返还！"));
                            emitter.complete();
                        } catch (IOException ignored) {}
                    }
                }
            });

            eventSourceRef.set(eventSource);

        } catch (Exception e) {
            log.error("发起大模型请求前发生异常", e);
            cancelAndRollback(userId, isQuotaSettled, isCompensated, null);
            try {
                emitter.send(SseEmitter.event().name("error").data("服务器初始化异常，配额已全额返还！"));
                emitter.complete();
            } catch (IOException ignored) {}
        }
    }

    /**
     * 【原子反向补偿方法】：掐断长连接并安全回滚用户配额
     */
    private void cancelAndRollback(Long userId, AtomicBoolean isQuotaSettled, AtomicBoolean isCompensated, EventSource eventSource) {
        // 1. 如果大模型长连接尚在推流，立即断开，防止无谓消耗 Token
        if (eventSource != null) {
            try {
                eventSource.cancel();
            } catch (Exception ignored) {}
        }

        // 2. 如果配额已最终确认消费，严禁回滚
        if (isQuotaSettled.get()) {
            return;
        }

        // 3. 幂等控制：确保本次调用只回滚一次
        if (isCompensated.compareAndSet(false, true)) {
            try {
                int rows = userMapper.compensateRollbackUserQuota(userId);
                log.info("【风控回滚触发】用户 [{}] AI配额补偿成功 (+1), 影响行数={}", userId, rows);
            } catch (Exception e) {
                log.error("【严重故障】用户 [{}] 配额补偿回滚失败，需人工运维介入!", userId, e);
            }
        }
    }

    /**
     * 【结构化精准落库】：在事务中执行插入，失败抛出异常以触发补偿
     */
    @Transactional(rollbackFor = Exception.class)
    public Long parseAndSaveStructuredTrip(TripGenerateDTO dto, Long userId, String fullOutput) throws Exception {
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

        // 2. 正则提取 JSON
        Pattern pattern = Pattern.compile("```(?:json|JSON)?\\s*([\\s\\S]*?)\\s*```");
        Matcher matcher = pattern.matcher(fullOutput);

        if (!matcher.find()) {
            throw new BusinessException("未能在大模型输出中捕获标准的结构化 JSON 数据块");
        }

        String jsonStr = matcher.group(1).trim();
        JsonNode root = objectMapper.readTree(jsonStr);
        JsonNode daysArray = root.path("days");

        if (!daysArray.isArray() || daysArray.isEmpty()) {
            throw new BusinessException("大模型返回的日程数组为空");
        }

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
                    item.setItemName(itemNode.path("itemName").asText("推荐景点"));
                    item.setStartTime(itemNode.path("startTime").asText("09:00"));
                    item.setCostTimeHours(new BigDecimal(itemNode.path("costTimeHours").asText("2.0")));
                    item.setCostEstimate(new BigDecimal(itemNode.path("costEstimate").asText("50.0")));
                    item.setTransportTips(itemNode.path("transportTips").asText("建议公共交通前往"));
                    item.setAiTips(itemNode.path("aiTips").asText("AI 避坑贴士"));
                    item.setSortOrder(sort++);
                    item.setIsDeleted(0);
                    squareMapper.insertCloneItem(item);
                }
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
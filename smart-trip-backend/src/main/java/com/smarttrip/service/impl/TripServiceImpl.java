package com.smarttrip.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smarttrip.common.BusinessException;
import com.smarttrip.config.AiProperties;
import com.smarttrip.entity.*;
import com.smarttrip.mapper.SquareMapper;
import com.smarttrip.mapper.TripMapper;
import com.smarttrip.service.TripService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class TripServiceImpl implements TripService {

    private final TripMapper tripMapper;
    private final SquareMapper squareMapper;
    private final AiProperties aiProperties;

    @Override
    public TripDetailVO getTripDetail(Long tripId) {
        log.info("拉取路书详情: tripId={}", tripId);

        TripDetailVO detail = tripMapper.selectTripById(tripId);
        if (detail == null) {
            return buildMockTripDetail(tripId);
        }

        List<TripDayVO> days = tripMapper.selectDaysByTripId(tripId);
        for (TripDayVO day : days) {
            // 这里会自动调用按 start_time ASC 排序的 SQL！
            List<TripItem> items = tripMapper.selectItemsByDayId(day.getId());
            day.setItems(items);
        }
        detail.setDays(days);

        return detail;
    }

    @Override
    public void updateItem(Long itemId, TripItem item) {
        item.setId(itemId);
        tripMapper.updateTripItem(item);
        log.info("节点更新成功: itemId={}", itemId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void sortItems(Long dayId, List<Long> sortedItemIds) {
        log.info("更新节点拖拽排序: dayId={}, sortedIds={}", dayId, sortedItemIds);
        for (int i = 0; i < sortedItemIds.size(); i++) {
            tripMapper.updateItemSortOrder(sortedItemIds.get(i), i + 1);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void modifyTripByAI(TripModifyDTO dto) {
        log.info("【确定性意图微调引擎启动】: tripId={}, dayIndex={}, 指令={}", dto.getTripId(), dto.getDayIndex(), dto.getUserRequirement());

        TripDetailVO trip = tripMapper.selectTripById(dto.getTripId());
        List<TripDayVO> days = tripMapper.selectDaysByTripId(dto.getTripId());
        TripDayVO targetDay = days.stream().filter(d -> d.getDayIndex().equals(dto.getDayIndex())).findFirst().orElse(null);

        if (targetDay == null || trip == null) {
            throw new BusinessException("未找到对应的行程天数");
        }

        // 1. 获取当前已有节点的真实快照
        List<TripItem> currentItems = tripMapper.selectItemsByDayId(targetDay.getId());
        String currentSnapshotJson;
        try {
            currentSnapshotJson = new ObjectMapper().writeValueAsString(currentItems);
        } catch (Exception e) {
            currentSnapshotJson = "[]";
        }

        // 2. 意图解析 Prompt：要求大模型输出操作指令 (action) 与节点数据
        String systemPrompt = "你是一个行程数据操作编译器。\n" +
                "请分析用户的【修改指令】，对比【当前已有行程数据】，输出操作动作与节点列表。\n" +
                "【动作定义】：\n" +
                "- APPEND: 仅在当前行程后追加新节点（如看夜景、增加晚餐）；\n" +
                "- SHIFT_AND_UPDATE: 调整出发时间或修改某几个节点（保留其余已有节点）；\n" +
                "- OVERWRITE: 用户明确要求彻底重做整天行程。\n" +
                "【严格输出格式】：必须且仅输出如下 JSON（用 ```json 包裹）：\n" +
                "```json\n" +
                "{\n" +
                "  \"action\": \"APPEND|SHIFT_AND_UPDATE|OVERWRITE\",\n" +
                "  \"items\": [\n" +
                "    {\n" +
                "      \"itemType\": \"SCENIC|FOOD|TRAFFIC\",\n" +
                "      \"itemName\": \"地点名\",\n" +
                "      \"startTime\": \"HH:mm\",\n" +
                "      \"costTimeHours\": 2.0,\n" +
                "      \"costEstimate\": 50.0,\n" +
                "      \"transportTips\": \"交通建议\",\n" +
                "      \"aiTips\": \"专属贴士\"\n" +
                "    }\n" +
                "  ]\n" +
                "}\n" +
                "```";

        String userPrompt = String.format(
                "城市：%s。\n【当前行程】：%s\n【用户指令】：%s",
                trip.getDestination(), currentSnapshotJson, dto.getUserRequirement()
        );

        try {
            String aiJsonResult = callLlmSync(systemPrompt, userPrompt);
            Pattern pattern = Pattern.compile("```(?:json|JSON)?\\s*([\\s\\S]*?)\\s*```");
            Matcher matcher = pattern.matcher(aiJsonResult);
            String jsonStr = matcher.find() ? matcher.group(1).trim() : aiJsonResult.trim();

            JsonNode root = new ObjectMapper().readTree(jsonStr);
            String action = root.path("action").asText("SHIFT_AND_UPDATE");
            JsonNode itemsNode = root.path("items");

            List<TripItem> newItems = new ArrayList<>();
            if (itemsNode.isArray()) {
                for (JsonNode node : itemsNode) {
                    TripItem item = new TripItem();
                    item.setTripId(dto.getTripId());
                    item.setDayId(targetDay.getId());
                    item.setItemType(node.path("itemType").asText("SCENIC"));
                    item.setItemName(node.path("itemName").asText("推荐地点"));
                    item.setStartTime(node.path("startTime").asText("10:00"));
                    item.setCostTimeHours(new BigDecimal(node.path("costTimeHours").asText("2.0")));
                    item.setCostEstimate(new BigDecimal(node.path("costEstimate").asText("30.0")));
                    item.setTransportTips(node.path("transportTips").asText("建议交通"));
                    item.setAiTips(node.path("aiTips").asText("专属贴士"));
                    item.setIsDeleted(0);
                    newItems.add(item);
                }
            }

            // 3. 【确定性 Java 合并状态机】—— 杜绝数据被大模型意外清空
            if ("APPEND".equalsIgnoreCase(action) && !currentItems.isEmpty()) {
                // 追加模式：保留已有全部节点，仅向后追加新节点
                for (TripItem newItem : newItems) {
                    squareMapper.insertCloneItem(newItem);
                }
            } else if ("SHIFT_AND_UPDATE".equalsIgnoreCase(action) && newItems.size() < currentItems.size()) {
                // 安全防线：如果大模型漏掉了原有节点，Java 底层自动合并已有节点与新补充节点
                for (TripItem newItem : newItems) {
                    boolean matched = false;
                    for (TripItem old : currentItems) {
                        if (old.getItemName().contains(newItem.getItemName()) || newItem.getItemName().contains(old.getItemName())) {
                            old.setStartTime(newItem.getStartTime());
                            old.setAiTips(newItem.getAiTips());
                            tripMapper.updateTripItem(old);
                            matched = true;
                            break;
                        }
                    }
                    if (!matched) {
                        squareMapper.insertCloneItem(newItem);
                    }
                }
            } else {
                // 全量重写模式
                tripMapper.deleteItemsByDayId(targetDay.getId());
                int sort = 1;
                for (TripItem item : newItems) {
                    item.setSortOrder(sort++);
                    squareMapper.insertCloneItem(item);
                }
            }

            log.info("🎉 意图合并引擎处理完毕: action={}", action);

        } catch (Exception e) {
            log.error("意图微调处理失败", e);
            throw new BusinessException("AI 微调解析失败，请重试");
        }
    }

    private String callLlmSync(String systemPrompt, String userPrompt) throws Exception {
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(90, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();

        Map<String, Object> bodyMap = new HashMap<>();
        bodyMap.put("model", aiProperties.getModel());
        bodyMap.put("stream", false);
        bodyMap.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)
        ));

        String jsonPayload = new ObjectMapper().writeValueAsString(bodyMap);
        Request request = new Request.Builder()
                .url(aiProperties.getBaseUrl() + "/chat/completions")
                .addHeader("Authorization", "Bearer " + aiProperties.getApiKey())
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(jsonPayload, MediaType.parse("application/json")))
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new RuntimeException("大模型响应异常: " + response.code());
            }
            JsonNode resNode = new ObjectMapper().readTree(response.body().string());
            return resNode.path("choices").get(0).path("message").path("content").asText("");
        }
    }

    private TripDetailVO buildMockTripDetail(Long tripId) {
        TripDetailVO vo = new TripDetailVO();
        vo.setId(tripId);
        vo.setTitle("1天 精致旅行专属路书");
        vo.setDestination("精选目的地");
        vo.setStartDate(LocalDate.now());
        vo.setTotalDays(1);
        vo.setTotalBudget(new BigDecimal("1000.00"));
        vo.setTripStyle("自然风光");
        vo.setTravelGroup("独自一人");
        vo.setSummary("为您定制的精品路线。");

        List<TripDayVO> days = new ArrayList<>();
        TripDayVO day1 = new TripDayVO();
        day1.setId(101L);
        day1.setDayIndex(1);
        day1.setDayDate(LocalDate.now());
        day1.setDayTheme("初见城市 · 经典地标探秘");
        day1.setItems(new ArrayList<>());
        days.add(day1);
        vo.setDays(days);
        return vo;
    }
}
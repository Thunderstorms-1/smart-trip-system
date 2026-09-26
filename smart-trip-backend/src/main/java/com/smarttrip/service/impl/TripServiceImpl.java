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

    private final OkHttpClient okHttpClient = new OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build();

    // =========================================================================
    // 【核心设计】：公共同步总预算方法（原子调用 SQL 聚合，保持数据强一致性）
    // =========================================================================
    private void syncTripTotalCost(Long tripId) {
        if (tripId == null) {
            return;
        }
        int rows = tripMapper.recalculateAndSyncTotalCost(tripId);
        log.info("【总预算联动同步完成】: tripId={}, 影响主表行数={}", tripId, rows);
    }

    @Override
    public TripDetailVO getTripDetail(Long tripId) {
        log.info("拉取路书详情: tripId={}", tripId);
        TripDetailVO detail = tripMapper.selectTripById(tripId);
        if (detail == null) {
            return buildMockTripDetail(tripId);
        }

        List<TripDayVO> days = tripMapper.selectDaysByTripId(tripId);
        for (TripDayVO day : days) {
            List<TripItem> items = tripMapper.selectItemsByDayId(day.getId());
            day.setItems(items);
        }
        detail.setDays(days);
        return detail;
    }

    /**
     * 场景 1：用户在前端编辑卡片（修改名称、时间或花费）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateItem(Long itemId, TripItem item) {
        item.setId(itemId);

        // 1. 获取该卡片原有的 tripId
        TripItem existingItem = tripMapper.selectItemById(itemId);
        if (existingItem == null) {
            throw new BusinessException("卡片节点不存在");
        }
        Long tripId = existingItem.getTripId();

        // 2. 执行卡片更新
        tripMapper.updateTripItem(item);
        log.info("节点更新成功: itemId={}, tripId={}", itemId, tripId);

        // 3. 【触发联动重算】：原子更新主表总预估花费
        syncTripTotalCost(tripId);
    }

    /**
     * 场景 2：用户删除单张卡片（软删除）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteTripItem(Long itemId) {
        TripItem existingItem = tripMapper.selectItemById(itemId);
        if (existingItem == null) {
            throw new BusinessException("卡片节点不存在");
        }
        Long tripId = existingItem.getTripId();

        // 1. 软删除卡片标记 is_deleted = 1
        tripMapper.softDeleteItemById(itemId);
        log.info("节点软删除成功: itemId={}, tripId={}", itemId, tripId);

        // 2. 【触发联动重算】：重新聚合未删除的卡片金额并同步主表
        syncTripTotalCost(tripId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void sortItems(Long dayId, List<Long> sortedItemIds) {
        log.info("更新节点拖拽排序: dayId={}, sortedIds={}", dayId, sortedItemIds);
        for (int i = 0; i < sortedItemIds.size(); i++) {
            tripMapper.updateItemSortOrder(sortedItemIds.get(i), i + 1);
        }
    }

    /**
     * 场景 3：AI 智能局部重排与增量微调
     */
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

        // 1. 获取当前快照
        List<TripItem> currentItems = tripMapper.selectItemsByDayId(targetDay.getId());
        String currentSnapshotJson;
        try {
            currentSnapshotJson = new ObjectMapper().writeValueAsString(currentItems);
        } catch (Exception e) {
            currentSnapshotJson = "[]";
        }

        // 2. 意图解析 Prompt
        String systemPrompt = "你是一个行程数据操作编译器。\n" +
                "请分析用户的【修改指令】，对比【当前已有行程数据】，输出操作动作与节点列表。\n" +
                "【动作定义】：\n" +
                "- APPEND: 仅在当前行程后追加新节点；\n" +
                "- SHIFT_AND_UPDATE: 调整时间或修改某几个节点；\n" +
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

            // 3. 状态机合并
            if ("APPEND".equalsIgnoreCase(action) && !currentItems.isEmpty()) {
                for (TripItem newItem : newItems) {
                    squareMapper.insertCloneItem(newItem);
                }
            } else if ("SHIFT_AND_UPDATE".equalsIgnoreCase(action) && newItems.size() < currentItems.size()) {
                for (TripItem newItem : newItems) {
                    boolean matched = false;
                    for (TripItem old : currentItems) {
                        if (old.getItemName().contains(newItem.getItemName()) || newItem.getItemName().contains(old.getItemName())) {
                            old.setStartTime(newItem.getStartTime());
                            old.setAiTips(newItem.getAiTips());
                            old.setCostEstimate(newItem.getCostEstimate());
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
                tripMapper.deleteItemsByDayId(targetDay.getId());
                int sort = 1;
                for (TripItem item : newItems) {
                    item.setSortOrder(sort++);
                    squareMapper.insertCloneItem(item);
                }
            }

            // 4. 【触发联动重算】：在 AI 替换节点事务提交前，重新计算整本路书的总开销并落库
            syncTripTotalCost(dto.getTripId());

            log.info("🎉 意图合并引擎处理完毕并完成总预算同步: action={}, tripId={}", action, dto.getTripId());

        } catch (Exception e) {
            log.error("意图微调处理失败", e);
            throw new BusinessException("AI 微调解析失败，请重试");
        }
    }

    private String callLlmSync(String systemPrompt, String userPrompt) throws Exception {
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

        try (Response response = okHttpClient.newCall(request).execute()) {
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
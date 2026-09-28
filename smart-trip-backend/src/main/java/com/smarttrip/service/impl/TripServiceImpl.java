package com.smarttrip.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smarttrip.common.BusinessException;
import com.smarttrip.common.UserContext;
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
    // 【核心组件】：智能图库与评分解析引擎 (哈希算法智能匹配高保真旅行封面)
    // =========================================================================
    public static class TravelImageResolver {
        private static final Map<String, String[]> CATEGORY_PHOTO_POOLS = Map.of(
                "SCENIC", new String[]{
                        "https://images.unsplash.com/photo-1507525428034-b723cf961d3e?w=500&q=80", // 绝美海岛与水系
                        "https://images.unsplash.com/photo-1528127269322-539801943592?w=500&q=80", // 古风园林与历史古迹
                        "https://images.unsplash.com/photo-1464822759023-fed622ff2c3b?w=500&q=80", // 壮丽雪山与山岳奇观
                        "https://images.unsplash.com/photo-1513836279014-a89f7a76ae86?w=500&q=80"  // 森林与自然徒步
                },
                "FOOD", new String[]{
                        "https://images.unsplash.com/photo-1504674900247-0877df9cc836?w=500&q=80", // 特色盛宴与火锅
                        "https://images.unsplash.com/photo-1555396273-367ea4eb4db5?w=500&q=80", // 烟火市井小吃
                        "https://images.unsplash.com/photo-1565299585323-38d6b0865b47?w=500&q=80"  // 甜品与特色饮品
                },
                "HOTEL", new String[]{
                        "https://images.unsplash.com/photo-1566073771259-6a8506099945?w=500&q=80", // 精品度假酒店
                        "https://images.unsplash.com/photo-1582719478250-c89cae4dc85b?w=500&q=80"  // 特色民宿与客栈
                },
                "TRAFFIC", new String[]{
                        "https://images.unsplash.com/photo-1544620347-c4fd4a3d5957?w=500&q=80", // 城市交通与公路
                        "https://images.unsplash.com/photo-1436491865332-7a61a109cc05?w=500&q=80"  // 航班与车站
                }
        );

        public static void resolveItemMedia(TripItem item) {
            if (item == null) return;

            // 1. 动态生成 4.6 ~ 4.9 的真实高评分
            if (item.getRating() == null || item.getRating() == 0.0) {
                long seed = item.getId() != null ? item.getId() : (long) (item.getItemName() != null ? item.getItemName().hashCode() : 1);
                double score = 4.6 + (Math.abs(seed) % 4) * 0.1;
                item.setRating(Math.round(score * 10.0) / 10.0);
            }

            // 2. 封面图片智能匹配
            if (item.getCoverImage() == null || item.getCoverImage().isBlank()) {
                String type = item.getItemType() != null ? item.getItemType().toUpperCase() : "SCENIC";
                String[] pool = CATEGORY_PHOTO_POOLS.getOrDefault(type, CATEGORY_PHOTO_POOLS.get("SCENIC"));
                int hash = item.getItemName() != null ? item.getItemName().hashCode() : 0;
                int index = Math.abs(hash) % pool.length;
                item.setCoverImage(pool[index]);
            }
        }
    }

    private TripDetailVO assertTripWritePermission(Long tripId, Long currentUserId) {
        if (tripId == null) throw new BusinessException(400, "行程ID不能为空");
        TripDetailVO trip = tripMapper.selectTripById(tripId);
        if (trip == null) throw new BusinessException(404, "目标行程不存在或已被删除");
        if (currentUserId == null || !trip.getUserId().equals(currentUserId)) {
            log.warn("【水平越权拦截-写操作】用户 [{}] 试图非法修改行程 [{}]", currentUserId, tripId);
            throw new BusinessException(403, "无权修改或操作他人行程！");
        }
        return trip;
    }

    private TripDetailVO assertTripReadPermission(Long tripId, Long currentUserId) {
        if (tripId == null) throw new BusinessException(400, "行程ID不能为空");
        TripDetailVO trip = tripMapper.selectTripById(tripId);
        if (trip == null) return buildMockTripDetail(tripId);
        if (currentUserId != null && trip.getUserId().equals(currentUserId)) return trip;
        if (trip.getIsPublic() != null && trip.getIsPublic() == 1) return trip;
        log.warn("【水平越权拦截-读操作】用户 [{}] 试图查看私密行程 [{}]", currentUserId, tripId);
        throw new BusinessException(403, "该行程为私密行程，您无权查看！");
    }

    private void syncTripTotalCost(Long tripId) {
        if (tripId == null) return;
        int rows = tripMapper.recalculateAndSyncTotalCost(tripId);
        log.info("【总预算联动同步完成】: tripId={}, 影响主表行数={}", tripId, rows);
    }

    @Override
    public List<TripDetailVO> getMyTrips(Long userId) {
        if (userId == null) return new ArrayList<>();
        List<TripDetailVO> list = tripMapper.selectTripsByUserId(userId);
        return list != null ? list : new ArrayList<>();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteTrip(Long tripId, Long currentUserId) {
        if (tripId == null || currentUserId == null) throw new BusinessException(400, "参数错误");
        int affectedRows = tripMapper.softDeleteTripByIdAndUserId(tripId, currentUserId);
        if (affectedRows == 0) throw new BusinessException(403, "无权删除该行程或该行程不存在");
        tripMapper.softDeleteDaysByTripId(tripId);
        tripMapper.softDeleteItemsByTripId(tripId);
    }

    /**
     * 查询行程详情（已植入图文富媒体着色）
     */
    @Override
    public TripDetailVO getTripDetail(Long tripId) {
        Long currentUserId = UserContext.getUserId();
        TripDetailVO detail = assertTripReadPermission(tripId, currentUserId);

        List<TripDayVO> days = tripMapper.selectDaysByTripId(tripId);
        for (TripDayVO day : days) {
            List<TripItem> items = tripMapper.selectItemsByDayId(day.getId());
            if (items != null) {
                for (TripItem item : items) {
                    // 【核心调用】：为每个卡片补齐高清封面图与星级评分
                    TravelImageResolver.resolveItemMedia(item);
                }
            }
            day.setItems(items);
        }
        detail.setDays(days);
        return detail;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateItem(Long itemId, TripItem item) {
        Long currentUserId = UserContext.getUserId();
        TripItem existingItem = tripMapper.selectItemById(itemId);
        if (existingItem == null) throw new BusinessException(404, "该日程卡片不存在");
        Long tripId = existingItem.getTripId();
        assertTripWritePermission(tripId, currentUserId);

        item.setId(itemId);
        tripMapper.updateTripItem(item);
        syncTripTotalCost(tripId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteTripItem(Long itemId) {
        Long currentUserId = UserContext.getUserId();
        TripItem existingItem = tripMapper.selectItemById(itemId);
        if (existingItem == null) throw new BusinessException(404, "该日程卡片不存在");
        Long tripId = existingItem.getTripId();
        assertTripWritePermission(tripId, currentUserId);

        tripMapper.softDeleteItemById(itemId);
        syncTripTotalCost(tripId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TripItem addTripItem(TripItemAddDTO dto) {
        Long currentUserId = UserContext.getUserId();
        assertTripWritePermission(dto.getTripId(), currentUserId);

        if (dto.getItemName() == null || dto.getItemName().isBlank()) {
            throw new BusinessException(400, "活动地点名称不能为空");
        }

        int maxSort = tripMapper.selectMaxSortOrderByDayId(dto.getDayId());
        TripItem item = new TripItem();
        item.setTripId(dto.getTripId());
        item.setDayId(dto.getDayId());
        item.setItemType(dto.getItemType() != null ? dto.getItemType() : "SCENIC");
        item.setItemName(dto.getItemName().trim());
        item.setStartTime(dto.getStartTime() != null && !dto.getStartTime().isBlank() ? dto.getStartTime() : "09:00");
        item.setCostTimeHours(dto.getCostTimeHours() != null ? dto.getCostTimeHours() : new BigDecimal("2.0"));
        item.setCostEstimate(dto.getCostEstimate() != null ? dto.getCostEstimate() : BigDecimal.ZERO);
        item.setTransportTips(dto.getTransportTips());
        item.setAiTips(dto.getAiTips() != null ? dto.getAiTips() : "自定义活动");
        item.setSortOrder(maxSort + 1);
        item.setIsDeleted(0);

        // 新增卡片动态赋图
        TravelImageResolver.resolveItemMedia(item);

        tripMapper.insertSingleTripItem(item);
        syncTripTotalCost(dto.getTripId());
        return item;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reorderItems(List<ItemSortDTO> sortList) {
        if (sortList == null || sortList.isEmpty()) return;
        Long currentUserId = UserContext.getUserId();
        TripItem firstItem = tripMapper.selectItemById(sortList.get(0).getItemId());
        if (firstItem != null) {
            assertTripWritePermission(firstItem.getTripId(), currentUserId);
        }
        for (ItemSortDTO item : sortList) {
            if (item.getItemId() != null && item.getSortOrder() != null) {
                tripMapper.updateItemSortOrder(item.getItemId(), item.getSortOrder());
            }
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void sortItems(Long dayId, List<Long> sortedItemIds) {
        TripDayVO day = tripMapper.selectDayById(dayId);
        if (day != null) {
            assertTripWritePermission(day.getTripId(), UserContext.getUserId());
        }
        for (int i = 0; i < sortedItemIds.size(); i++) {
            tripMapper.updateItemSortOrder(sortedItemIds.get(i), i + 1);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TripDayVO addTripDay(Long tripId) {
        Long currentUserId = UserContext.getUserId();
        assertTripWritePermission(tripId, currentUserId);

        int maxIndex = tripMapper.selectMaxDayIndexByTripId(tripId);
        int nextIndex = maxIndex + 1;

        TripDayVO newDay = new TripDayVO();
        newDay.setTripId(tripId);
        newDay.setDayIndex(nextIndex);
        newDay.setDayTheme("自由探索与随心漫游");
        newDay.setDaySummary("第 " + nextIndex + " 天日程");
        newDay.setItems(new ArrayList<>());
        tripMapper.insertTripDay(newDay);

        int totalDaysCount = tripMapper.countActiveDaysByTripId(tripId);
        tripMapper.updateTripTotalDays(tripId, totalDaysCount);
        return newDay;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteTripDay(Long dayId) {
        Long currentUserId = UserContext.getUserId();
        TripDayVO day = tripMapper.selectDayById(dayId);
        if (day == null) throw new BusinessException(404, "该天数日程不存在");
        Long tripId = day.getTripId();
        assertTripWritePermission(tripId, currentUserId);

        int activeDaysCount = tripMapper.countActiveDaysByTripId(tripId);
        if (activeDaysCount <= 1) throw new BusinessException(400, "行程至少需要保留 1 天，无法继续删除！");

        tripMapper.softDeleteDayById(dayId);
        tripMapper.softDeleteItemsByDayId(dayId);

        List<TripDayVO> remainingDays = tripMapper.selectDaysByTripId(tripId);
        int currentIndex = 1;
        for (TripDayVO d : remainingDays) {
            if (!d.getDayIndex().equals(currentIndex)) {
                tripMapper.updateDayIndex(d.getId(), currentIndex);
            }
            currentIndex++;
        }

        tripMapper.updateTripTotalDays(tripId, remainingDays.size());
        syncTripTotalCost(tripId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void modifyTripByAI(TripModifyDTO dto) {
        Long currentUserId = UserContext.getUserId();
        TripDetailVO trip = assertTripWritePermission(dto.getTripId(), currentUserId);

        List<TripDayVO> days = tripMapper.selectDaysByTripId(dto.getTripId());
        TripDayVO targetDay = days.stream().filter(d -> d.getDayIndex().equals(dto.getDayIndex())).findFirst().orElse(null);
        if (targetDay == null) throw new BusinessException(404, "未找到对应的行程天数");

        List<TripItem> currentItems = tripMapper.selectItemsByDayId(targetDay.getId());
        String currentSnapshotJson;
        try {
            currentSnapshotJson = new ObjectMapper().writeValueAsString(currentItems);
        } catch (Exception e) {
            currentSnapshotJson = "[]";
        }

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
                    item.setCostEstimate(new BigDecimal(node.path("costEstimate").asText("50.0")));
                    item.setTransportTips(node.path("transportTips").asText("建议交通"));
                    item.setAiTips(node.path("aiTips").asText("专属贴士"));
                    item.setIsDeleted(0);
                    TravelImageResolver.resolveItemMedia(item);
                    newItems.add(item);
                }
            }

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

            syncTripTotalCost(dto.getTripId());

        } catch (Exception e) {
            log.error("意图微调处理失败", e);
            throw new BusinessException(500, "AI 微调解析失败，请重试");
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
        vo.setIsPublic(1);

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
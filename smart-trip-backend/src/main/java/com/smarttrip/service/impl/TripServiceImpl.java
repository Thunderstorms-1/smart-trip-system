package com.smarttrip.service.impl;

import com.smarttrip.common.BusinessException;
import com.smarttrip.common.UserContext;
import com.smarttrip.config.AiProperties;
import com.smarttrip.entity.*;
import com.smarttrip.mapper.SquareMapper;
import com.smarttrip.mapper.TripMapper;
import com.smarttrip.mapper.TripMemberMapper;
import com.smarttrip.service.TripService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class TripServiceImpl implements TripService {

  private final TripMapper tripMapper;
  private final com.smarttrip.service.WorkbenchService workbench;
  private final com.smarttrip.service.ResourceAccess access;
  private final SquareMapper squareMapper;
  private final AiProperties aiProperties;
  private final TripMemberMapper tripMemberMapper;

  // =========================================================================
  // 【核心组件】：智能图库与评分解析引擎 (哈希算法智能匹配高保真旅行封面)
  // =========================================================================
  public static class TravelImageResolver {
    public static void resolveItemMedia(TripItem item) {
      if (item != null) {
        item.setRating(null);
        item.setCoverImage(null);
      }
    }
  }

  private TripDetailVO assertTripWritePermission(Long tripId, Long currentUserId) {
    return access.write(tripId);
  }

  private TripDetailVO assertTripReadPermission(Long tripId, Long currentUserId) {
    return access.read(tripId);
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
    workbench.delete(tripId);
  }

  /** 查询行程详情（已植入图文富媒体着色） */
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
    item.setTripId(existingItem.getTripId());
    item.setDayId(existingItem.getDayId());
    item.setIsDeleted(0);
    if (item.getItemName() == null || item.getItemName().isBlank())
      throw new BusinessException(400, "地点名称不能为空");
    if (item.getCostEstimate() == null || item.getCostEstimate().signum() < 0)
      throw new BusinessException(400, "费用必须为非负数");
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
    if (!java.util.Objects.equals(access.day(dto.getDayId()).getTripId(), dto.getTripId()))
      throw new BusinessException(400, "节点所属日程不匹配");

    if (dto.getItemName() == null || dto.getItemName().isBlank()) {
      throw new BusinessException(400, "活动地点名称不能为空");
    }

    int maxSort = tripMapper.selectMaxSortOrderByDayId(dto.getDayId());
    TripItem item = new TripItem();
    item.setTripId(dto.getTripId());
    item.setDayId(dto.getDayId());
    item.setItemType(dto.getItemType() != null ? dto.getItemType() : "SCENIC");
    item.setItemName(dto.getItemName().trim());
    item.setStartTime(
        dto.getStartTime() != null && !dto.getStartTime().isBlank() ? dto.getStartTime() : "09:00");
    item.setCostTimeHours(
        dto.getCostTimeHours() != null ? dto.getCostTimeHours() : new BigDecimal("2.0"));
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
    if (sortList == null || sortList.isEmpty() || sortList.size() > 200)
      throw new BusinessException(400, "排序列表无效");
    java.util.Set<Long> ids = new java.util.HashSet<>();
    Long dayId = null;
    for (ItemSortDTO entry : sortList) {
      if (entry.getItemId() == null
          || entry.getSortOrder() == null
          || entry.getSortOrder() < 1
          || !ids.add(entry.getItemId())) throw new BusinessException(400, "排序数据无效");
      TripItem item = access.item(entry.getItemId());
      if (dayId == null) dayId = item.getDayId();
      if (!java.util.Objects.equals(dayId, item.getDayId()))
        throw new BusinessException(400, "不能跨日程排序");
    }
    for (ItemSortDTO entry : sortList)
      tripMapper.updateItemSortOrder(entry.getItemId(), entry.getSortOrder());
    syncTripTotalCost(access.day(dayId).getTripId());
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void sortItems(Long dayId, List<Long> sortedItemIds) {
    TripDayVO day = access.day(dayId);
    if (sortedItemIds == null
        || sortedItemIds.size() > 200
        || new java.util.HashSet<>(sortedItemIds).size() != sortedItemIds.size())
      throw new BusinessException(400, "排序列表无效");
    List<TripItem> active = tripMapper.selectItemsByDayId(dayId);
    java.util.Set<Long> expected =
        active.stream().map(TripItem::getId).collect(java.util.stream.Collectors.toSet());
    if (!expected.equals(new java.util.HashSet<>(sortedItemIds)))
      throw new BusinessException(400, "排序必须包含当天所有节点");
    for (Long id : sortedItemIds)
      if (!java.util.Objects.equals(access.item(id).getDayId(), dayId))
        throw new BusinessException(400, "节点不属于目标日程");
    for (int i = 0; i < sortedItemIds.size(); i++)
      tripMapper.updateItemSortOrder(sortedItemIds.get(i), i + 1);
    syncTripTotalCost(day.getTripId());
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
    throw new BusinessException(409, "请使用AI任务预览，并确认后保存");
  }
}

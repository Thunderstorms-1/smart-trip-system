package com.smarttrip.service.impl;

import com.smarttrip.common.BusinessException;
import com.smarttrip.common.UserContext;
import com.smarttrip.entity.*;
import com.smarttrip.mapper.ToolMapper;
import com.smarttrip.mapper.TripMapper;
import com.smarttrip.service.ToolService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ToolServiceImpl implements ToolService {

  private final ToolMapper toolMapper;
  private final TripMapper tripMapper;
  private final com.smarttrip.service.ResourceAccess access;

  /** 一键将行程中的预估花费卡片批量导入为记账流水 */
  @Override
  @Transactional(rollbackFor = Exception.class)
  public int importExpensesFromTrip(Long tripId) {
    access.write(tripId);
    throw new BusinessException(409, "预估费用需逐条核对实际金额后记账，请使用节点预填功能");
  }

  /** 行程卡片类型 (ItemType) -> 记账消费类别 (ExpenseCategory) 映射字典 */
  private String mapItemTypeToExpenseCategory(String itemType) {
    if (itemType == null) return "OTHER";
    switch (itemType.toUpperCase()) {
      case "SCENIC":
        return "TICKET"; // 景点 -> 门票
      case "FOOD":
        return "CATERING"; // 美食 -> 餐饮
      case "HOTEL":
        return "HOTEL"; // 住宿 -> 酒店
      case "TRAFFIC":
        return "TRAFFIC"; // 交通 -> 交通
      default:
        return "OTHER";
    }
  }

  @Override
  public void addExpense(TripExpense expense) {
    access.write(expense.getTripId());
    if (expense.getAmount() == null || expense.getAmount().signum() < 0)
      throw new BusinessException(400, "金额必须为非负数");
    expense.setUserId(UserContext.getUserId());
    toolMapper.insertExpense(expense);
    log.info("记账成功: tripId={}, amount={}", expense.getTripId(), expense.getAmount());
  }

  @Override
  public ExpenseSummaryVO getExpenseSummary(Long tripId) {
    access.read(tripId);
    ExpenseSummaryVO vo = new ExpenseSummaryVO();

    // 1. 查总预算 (默认 3000)
    TripDetailVO trip = tripMapper.selectTripById(tripId);
    BigDecimal budget =
        (trip != null && trip.getTotalBudget() != null)
            ? trip.getTotalBudget()
            : new BigDecimal("3000.00");
    vo.setTotalBudget(budget);

    // 2. 查实际总花费
    BigDecimal totalSpent = toolMapper.selectTotalSpent(tripId);
    vo.setTotalSpent(totalSpent);

    // 3. 计算剩余预算
    vo.setRemaining(budget.subtract(totalSpent));

    // 4. 查各分类统计与明细流水
    vo.setCategoryStats(toolMapper.selectCategoryStats(tripId));
    vo.setExpenseList(toolMapper.selectExpensesByTripId(tripId));

    return vo;
  }

  @Override
  public List<TripPackingItem> getPackingList(Long tripId) {
    access.read(tripId);
    return toolMapper.selectPackingItemsByTripId(tripId);
  }

  @Override
  public void togglePackingItem(Long itemId, Integer isChecked) {
    Long tripId = toolMapper.selectPackingTripId(itemId);
    if (tripId == null) throw new BusinessException(404, "清单条目不存在");
    access.write(tripId);
    if (isChecked == null || (isChecked != 0 && isChecked != 1))
      throw new BusinessException(400, "勾选状态无效");
    toolMapper.updatePackingItemCheckStatus(itemId, isChecked);
  }

  private List<TripPackingItem> generateDefaultPackingItems(Long tripId) {
    List<TripPackingItem> list = new ArrayList<>();
    String[][] defaults = {
      {"DOC", "身份证 / 护照原件"},
      {"DOC", "景区门票 / 机票订单预订确认凭证"},
      {"ELECTRONIC", "手机充电线 & 高速充电宝"},
      {"ELECTRONIC", "降噪耳机 / 相机及备用电池"},
      {"CLOTHES", "舒适透气运动鞋 / 换洗衣物 3 套"},
      {"CLOTHES", "晴雨两用折叠伞 / 防晒衣"},
      {"MEDICINE", "肠胃药 / 晕车贴 / 创可贴"},
      {"OTHER", "便携洗漱包 / 湿厕纸 / 晴雨防晒霜"}
    };
    for (String[] def : defaults) {
      TripPackingItem item = new TripPackingItem();
      item.setTripId(tripId);
      item.setCategory(def[0]);
      item.setItemName(def[1]);
      item.setIsChecked(0);
      list.add(item);
    }
    return list;
  }
}

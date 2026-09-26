package com.smarttrip.service.impl;

import com.smarttrip.common.BusinessException;
import com.smarttrip.common.UserContext;
import com.smarttrip.entity.*;
import com.smarttrip.mapper.ToolMapper;
import com.smarttrip.mapper.TripMapper;
import com.smarttrip.service.ToolService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ToolServiceImpl implements ToolService {

    private final ToolMapper toolMapper;
    private final TripMapper tripMapper;

    /**
     * 一键将行程中的预估花费卡片批量导入为记账流水
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int importExpensesFromTrip(Long tripId) {
        Long userId = UserContext.getUserId();
        log.info("用户 [{}] 执行从行程一键导入预算: tripId={}", userId, tripId);

        // 1. 查询所有带有预估金额的卡片
        List<TripItem> costItems = tripMapper.selectCostItemsByTripId(tripId);
        if (costItems == null || costItems.isEmpty()) {
            throw new BusinessException("当前行程中没有大于 0 元的预估消费项可导入！");
        }

        // 2. 转换为记账实体并建立分类映射
        List<TripExpense> expenseList = new ArrayList<>();
        LocalDate today = LocalDate.now();

        for (TripItem item : costItems) {
            TripExpense expense = new TripExpense();
            expense.setTripId(tripId);
            expense.setUserId(userId != null ? userId : 1L);
            expense.setAmount(item.getCostEstimate());
            expense.setExpenseName(item.getItemName());
            expense.setExpenseDate(today);
            expense.setCategory(mapItemTypeToExpenseCategory(item.getItemType()));
            expense.setRemark("从行程规划预估项自动导入");
            expenseList.add(expense);
        }

        // 3. 批量落库
        int insertedCount = toolMapper.batchInsertExpenses(expenseList);
        log.info("批量导入记账流水完成: tripId={}, 成功导入条数={}", tripId, insertedCount);
        return insertedCount;
    }

    /**
     * 行程卡片类型 (ItemType) -> 记账消费类别 (ExpenseCategory) 映射字典
     */
    private String mapItemTypeToExpenseCategory(String itemType) {
        if (itemType == null) return "OTHER";
        switch (itemType.toUpperCase()) {
            case "SCENIC":
                return "TICKET";    // 景点 -> 门票
            case "FOOD":
                return "CATERING";  // 美食 -> 餐饮
            case "HOTEL":
                return "HOTEL";     // 住宿 -> 酒店
            case "TRAFFIC":
                return "TRAFFIC";   // 交通 -> 交通
            default:
                return "OTHER";
        }
    }

    @Override
    public void addExpense(TripExpense expense) {
        expense.setUserId(UserContext.getUserId());
        toolMapper.insertExpense(expense);
        log.info("记账成功: tripId={}, amount={}", expense.getTripId(), expense.getAmount());
    }

    @Override
    public ExpenseSummaryVO getExpenseSummary(Long tripId) {
        ExpenseSummaryVO vo = new ExpenseSummaryVO();

        // 1. 查总预算 (默认 3000)
        TripDetailVO trip = tripMapper.selectTripById(tripId);
        BigDecimal budget = (trip != null && trip.getTotalBudget() != null) ? trip.getTotalBudget() : new BigDecimal("3000.00");
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
        List<TripPackingItem> items = toolMapper.selectPackingItemsByTripId(tripId);

        // 老数据兜底：若数据库中尚无记录，动态补齐基础项
        if (items == null || items.isEmpty()) {
            TripDetailVO trip = tripMapper.selectTripById(tripId);
            String dest = (trip != null && trip.getDestination() != null) ? trip.getDestination() : "目的地";

            List<TripPackingItem> defaultList = new ArrayList<>();
            String[][] defs = {
                    {"SPECIAL", dest + " 随身温差外套与晴雨伞 [当地气候特备]"},
                    {"DOC", "身份证原件及电子凭证 [乘车入住必备]"},
                    {"CLOTHES", "换洗衣物 2-3 套与透气鞋 [游玩舒适度保障]"},
                    {"ELECTRONIC", "手机充电宝及数据线 [防止电量耗尽]"},
                    {"MEDICINE", "常备肠胃药与感冒应急药 [异地饮食保障]"}
            };
            for (String[] def : defs) {
                TripPackingItem item = new TripPackingItem();
                item.setTripId(tripId);
                item.setCategory(def[0]);
                item.setItemName(def[1]);
                item.setIsChecked(0);
                defaultList.add(item);
            }
            toolMapper.batchInsertPackingItems(defaultList);
            items = toolMapper.selectPackingItemsByTripId(tripId);
        }
        return items;
    }

    @Override
    public void togglePackingItem(Long itemId, Integer isChecked) {
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
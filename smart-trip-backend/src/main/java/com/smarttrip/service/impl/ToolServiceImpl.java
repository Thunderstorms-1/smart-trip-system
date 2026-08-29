package com.smarttrip.service.impl;

import com.smarttrip.common.UserContext;
import com.smarttrip.entity.*;
import com.smarttrip.mapper.ToolMapper;
import com.smarttrip.mapper.TripMapper;
import com.smarttrip.service.ToolService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ToolServiceImpl implements ToolService {

    private final ToolMapper toolMapper;
    private final TripMapper tripMapper;

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

        // 如果该路书尚未初始化清单，自动为它生成一套智能推荐清单
        if (items == null || items.isEmpty()) {
            items = generateDefaultPackingItems(tripId);
            toolMapper.batchInsertPackingItems(items);
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
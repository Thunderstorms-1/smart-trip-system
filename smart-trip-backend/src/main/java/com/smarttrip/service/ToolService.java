package com.smarttrip.service;

import com.smarttrip.entity.ExpenseSummaryVO;
import com.smarttrip.entity.TripExpense;
import com.smarttrip.entity.TripPackingItem;

import java.util.List;

public interface ToolService {

    int importExpensesFromTrip(Long tripId);
    // 记账
    void addExpense(TripExpense expense);
    ExpenseSummaryVO getExpenseSummary(Long tripId);

    // 清单
    List<TripPackingItem> getPackingList(Long tripId);
    void togglePackingItem(Long itemId, Integer isChecked);
}
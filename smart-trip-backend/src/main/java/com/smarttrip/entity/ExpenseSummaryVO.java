package com.smarttrip.entity;

import lombok.Data;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Data
public class ExpenseSummaryVO {
    private BigDecimal totalBudget; // 设定总预算
    private BigDecimal totalSpent;  // 实际已花费
    private BigDecimal remaining;   // 剩余预算
    private List<Map<String, Object>> categoryStats; // 分类统计 (包含 category, amount)
    private List<TripExpense> expenseList; // 所有的明细流水
}
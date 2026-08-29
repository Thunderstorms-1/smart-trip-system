package com.smarttrip.entity;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class TripExpense {
    private Long id;
    private Long tripId;
    private Long userId;
    private String category;    // CATERING:餐饮, TRAFFIC:交通, HOTEL:住宿, TICKET:门票, SHOPPING:购物, OTHER:其他
    private BigDecimal amount;  // 金额
    private String expenseName; // 费用名称
    private LocalDate expenseDate;
    private String remark;
    private Integer isDeleted;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
package com.smarttrip.entity;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
public class TripDetailVO {
    private Long id;
    private Long userId;
    private String title;
    private String destination;
    private LocalDate startDate;
    private Integer totalDays;
    private BigDecimal totalBudget;
    private String tripStyle;
    private String travelGroup;
    private String summary;
    private Integer isPublic;
    private List<TripDayVO> days;   // 核心：所有天数的嵌套数组
}
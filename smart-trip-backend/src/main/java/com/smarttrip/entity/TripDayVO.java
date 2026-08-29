package com.smarttrip.entity;

import lombok.Data;
import java.time.LocalDate;
import java.util.List;

@Data
public class TripDayVO {
    private Long id;
    private Long tripId;
    private Integer dayIndex;       // 第几天 (1, 2, 3...)
    private LocalDate dayDate;
    private String dayTheme;        // 当天主题
    private String daySummary;      // 当天简述
    private List<TripItem> items;   // 当天的所有节点明细
}
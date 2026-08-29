package com.smarttrip.entity;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class TripItem {
    private Long id;
    private Long dayId;
    private Long tripId;
    private String itemType;        // SCENIC:景点, FOOD:美食, HOTEL:住宿, TRAFFIC:交通
    private String itemName;        // 景点/餐厅名
    private String startTime;       // 开始时间 (如: 09:00)
    private BigDecimal costTimeHours; // 游玩时长(小时)
    private BigDecimal costEstimate;  // 预估花费(元)
    private String address;
    private String transportTips;   // 交通建议
    private String aiTips;          // AI 贴士
    private Integer sortOrder;      // 排序值
    private Integer isDeleted;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
package com.smarttrip.entity;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class TripItemAddDTO {
    private Long tripId;              // 所属路书ID
    private Long dayId;               // 所属日程天数ID
    private String itemType;          // 节点类型 (SCENIC:景点, FOOD:美食, HOTEL:住宿, TRAFFIC:交通)
    private String itemName;          // 活动/地点名称 (必填)
    private String startTime;         // 开始时间 (如 "09:30")
    private BigDecimal costTimeHours; // 预估游玩耗时 (小时)
    private BigDecimal costEstimate;  // 预估花费 (元)
    private String transportTips;     // 交通出行建议
    private String aiTips;            // 自定义备注与避坑贴士
}
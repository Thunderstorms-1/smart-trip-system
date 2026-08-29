package com.smarttrip.entity;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class TripGenerateDTO {
    private String destination;         // 目的地（如：成都）
    private Integer totalDays;          // 天数（如：3）
    private String startDate;           // 出发日期（如：2025-05-01）
    private BigDecimal totalBudget;     // 预算（如：3000）
    private String tripStyle;           // 风格（如：美食探索/特种兵打卡/休闲度假）
    private String travelGroup;         // 人群（如：情侣出行/独自一人/亲子出行）
    private String customRequirements;  // 自定义补充要求（如：想看大熊猫，不早起）
}
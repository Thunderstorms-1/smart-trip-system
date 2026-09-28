package com.smarttrip.entity;

import lombok.Data;
import java.io.Serializable;
import java.math.BigDecimal;

@Data
public class FeaturedThemeVO implements Serializable {
    private Long id;                  // 路线或快照 ID (用于一键克隆)
    private String themeTag;          // 主题分类标签 (如: 🚘 经典自驾, 🌿 烟雨江南)
    private String title;             // 精选标题 (如: 川西大环线 · 7日雪山自驾秘境)
    private String destination;       // 核心目的地 (如: 康定 · 稻城亚丁)
    private Integer totalDays;        // 行程天数
    private BigDecimal totalBudget;   // 建议预估预算
    private String coverImage;        // 高清沉浸式大图 URL
    private String highlightDesc;     // 核心亮点提炼
    private Integer cloneCount;       // 累计套用人数
}
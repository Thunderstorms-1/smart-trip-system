package com.smarttrip.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

@Data
public class TripItem {
  private String poiId;
  private String coordinateSystem;
  private Long id;
  private Long dayId;
  private Long tripId;
  private String itemType; // SCENIC:景点, FOOD:美食, HOTEL:住宿, TRAFFIC:交通
  private String itemName; // 活动/地点名称
  private String startTime; // 建议开始时间
  private BigDecimal costTimeHours; // 建议游玩/耗时(小时)
  private BigDecimal costEstimate; // 预估花费(元)
  private String address;
  private BigDecimal longitude;
  private BigDecimal latitude;
  private String transportTips; // 交通出行建议
  private String aiTips; // AI 贴士 / 自定义备注
  private Integer sortOrder; // 排序序号
  private Integer isDeleted;
  private LocalDateTime createTime;
  private LocalDateTime updateTime;

  // =========================================================================
  // 【核心新增】：图文富媒体投影属性（内存级动态着色，零数据库结构变更）
  // =========================================================================
  private String coverImage; // 高清封面图 URL
  private Double rating; // 星级评分 (如 4.7 ~ 4.9)
}

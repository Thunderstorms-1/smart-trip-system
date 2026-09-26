package com.smarttrip.entity;

import lombok.Data;

@Data
public class SquareQueryDTO {
    private String keyword;       // 目的地或标题模糊检索
    private String travelStyle;   // 旅行风格筛选 (全部/美食探索/人文古迹/自然风光/特种兵打卡/休闲度假)
    private Integer minDays;      // 最小天数过滤
    private Integer maxDays;      // 最大天数过滤
    private String sortType;      // 排序规则: HOT(点赞最多), NEWEST(最新发布), BUDGET_ASC(预算从低到高)
    private Integer pageNum = 1;  // 当前页码 (默认第1页)
    private Integer pageSize = 8; // 每页条数 (默认8条)
}
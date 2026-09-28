package com.smarttrip.entity;

import lombok.Data;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Data
public class UserFootprintVO implements Serializable {
    // 1. KPI 汇总指标
    private Integer totalTrips;          // 累计规划路书总数
    private Integer totalDays;           // 累计出行总天数
    private BigDecimal totalBudget;      // 累计总预估预算
    private Integer exploredCitiesCount; // 点亮去重城市总数

    // 2. 点亮城市分布点位
    private List<CityFootprintItem> litCities;

    // 3. 旅行偏好风格雷达图数据
    private List<Map<String, Object>> styleRadarData;

    @Data
    public static class CityFootprintItem implements Serializable {
        private String cityName;
        private Integer tripCount;
        private Double longitude;
        private Double latitude;
    }
}
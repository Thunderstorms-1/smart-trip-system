package com.smarttrip.entity;

import lombok.Data;
import java.io.Serializable;

@Data
public class WeatherInfoVO implements Serializable {
    private String destination;       // 城市名称 (如：大理)
    private String condition;         // 天气状况 (如：阵雨转多云)
    private String weatherIcon;       // 图标标识: SUNNY, RAINY, CLOUDY, SNOWY
    private String tempRange;         // 气温区间 (如：14°C ~ 24°C)
    private String currentTemp;       // 实时体感温度 (如：20°C)
    private String dressingIndex;     // 穿衣指南 (如：早晚微凉，建议薄外套搭配短袖)
    private String uvLevel;           // 紫外线等级 (如：极强 - 高原环境)
    private String alertLevel;        // 预警等级: NORMAL(正常晴好), WARNING(提示警示), SEVERE(危险红警)
    private String alertMessage;      // 针对性预警避坑文案
}
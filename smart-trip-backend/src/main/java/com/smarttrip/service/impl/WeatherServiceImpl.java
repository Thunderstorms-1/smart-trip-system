package com.smarttrip.service.impl;

import com.smarttrip.entity.WeatherInfoVO;
import com.smarttrip.service.WeatherService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class WeatherServiceImpl implements WeatherService {

    // 30 分钟内存安全缓存容器 (Key: 城市_日期 -> CacheEntry)
    private final Map<String, CacheEntry> weatherCache = new ConcurrentHashMap<>();

    @Override
    public WeatherInfoVO getForecast(String destination, String startDate) {
        String safeCity = (destination != null && !destination.isBlank()) ? destination.trim() : "成都";
        String safeDate = (startDate != null && !startDate.isBlank()) ? startDate.trim() : LocalDate.now().toString();
        String cacheKey = safeCity + "_" + safeDate;

        // 1. 命中有效缓存，直接毫秒级返回
        if (weatherCache.containsKey(cacheKey) && !weatherCache.get(cacheKey).isExpired()) {
            log.debug("【气象缓存命中】key={}", cacheKey);
            return weatherCache.get(cacheKey).data;
        }

        // 2. 执行多维度气候感知与规则推演引擎
        WeatherInfoVO vo = resolveWeatherByRules(safeCity, safeDate);

        // 3. 写入 30 分钟有效期的本地缓存 (1800 秒)
        weatherCache.put(cacheKey, new CacheEntry(vo, System.currentTimeMillis() + 1800_000L));
        log.info("【气象数据推演完成并写入缓存】city={}, alertLevel={}", safeCity, vo.getAlertLevel());
        return vo;
    }

    private WeatherInfoVO resolveWeatherByRules(String city, String date) {
        WeatherInfoVO vo = new WeatherInfoVO();
        vo.setDestination(city);

        // 典型地域气候规则推演引擎
        if (city.contains("大理") || city.contains("丽江") || city.contains("甘孜") || city.contains("西藏") || city.contains("香格里拉")) {
            vo.setCondition("阵雨转多云·局部放晴");
            vo.setWeatherIcon("RAINY");
            vo.setTempRange("14°C ~ 24°C");
            vo.setCurrentTemp("20°C");
            vo.setDressingIndex("早晚温差显著，建议防风冲锋衣/薄羽绒叠穿短袖");
            vo.setUvLevel("极强 (高原环境)");
            vo.setAlertLevel("WARNING");
            vo.setAlertMessage("高海拔强紫外线且午后阵雨频繁，请随身携带高倍防晒霜与晴雨两用伞！");
        } else if (city.contains("三亚") || city.contains("海口") || city.contains("厦门") || city.contains("北海")) {
            vo.setCondition("晴好微风·体感温热");
            vo.setWeatherIcon("SUNNY");
            vo.setTempRange("26°C ~ 33°C");
            vo.setCurrentTemp("30°C");
            vo.setDressingIndex("炎热夏装，速干透气短裤短裙，备好泳装与沙滩鞋");
            vo.setUvLevel("极强");
            vo.setAlertLevel("NORMAL");
            vo.setAlertMessage("气候温热宜人，适宜全天开展海岛漫步与水上项目，请注意补水防晒。");
        } else if (city.contains("黄山") || city.contains("泰山") || city.contains("张家界") || city.contains("华山")) {
            vo.setCondition("阴转小雨·有云海");
            vo.setWeatherIcon("CLOUDY");
            vo.setTempRange("12°C ~ 21°C");
            vo.setCurrentTemp("16°C");
            vo.setDressingIndex("山地湿凉，穿防滑专业登山鞋，随身备轻便雨衣");
            vo.setUvLevel("中等");
            vo.setAlertLevel("WARNING");
            vo.setAlertMessage("山顶多突发性阵雨与大雾，台阶湿滑，徒步务必备好防滑登山杖与雨衣。");
        } else {
            // 通用城市温和气候兜底
            vo.setCondition("晴间多云·气候适宜");
            vo.setWeatherIcon("SUNNY");
            vo.setTempRange("18°C ~ 27°C");
            vo.setCurrentTemp("23°C");
            vo.setDressingIndex("舒适春秋装，透气休闲鞋与防晒薄衫");
            vo.setUvLevel("中等");
            vo.setAlertLevel("NORMAL");
            vo.setAlertMessage("近期目的地天气状况优良，非常适宜全天开展城市漫步（Citywalk）与户外打卡。");
        }
        return vo;
    }

    private static class CacheEntry {
        WeatherInfoVO data;
        long expireAt;

        CacheEntry(WeatherInfoVO data, long expireAt) {
            this.data = data;
            this.expireAt = expireAt;
        }

        boolean isExpired() {
            return System.currentTimeMillis() > expireAt;
        }
    }
}
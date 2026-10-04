package com.smarttrip.service.impl;

import com.smarttrip.common.BusinessException;
import com.smarttrip.entity.WeatherInfoVO;
import com.smarttrip.service.*;
import java.time.LocalDate;
import org.springframework.stereotype.Service;

@Service
public class WeatherServiceImpl implements WeatherService {
  private final GeoService geo;

  public WeatherServiceImpl(GeoService geo) {
    this.geo = geo;
  }

  public WeatherInfoVO getForecast(String destination, String startDate) {
    LocalDate date;
    try {
      date =
          startDate == null || startDate.isBlank() ? LocalDate.now() : LocalDate.parse(startDate);
    } catch (Exception e) {
      throw new BusinessException(400, "日期格式应为YYYY-MM-DD");
    }
    var data = geo.weather(destination, date);
    if (!Boolean.TRUE.equals(data.get("available")))
      throw new BusinessException(503, data.get("reason").toString());
    var v = new WeatherInfoVO();
    v.setDestination(data.get("city").toString());
    v.setCondition(data.get("dayWeather").toString());
    v.setTempRange(data.get("low") + "°C ~ " + data.get("high") + "°C");
    v.setAlertMessage("数据来源：高德；发布时间：" + data.get("issuedAt"));
    return v;
  }
}

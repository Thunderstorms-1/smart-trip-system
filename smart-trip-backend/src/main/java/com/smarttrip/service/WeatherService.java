package com.smarttrip.service;

import com.smarttrip.entity.WeatherInfoVO;

public interface WeatherService {
    WeatherInfoVO getForecast(String destination, String startDate);
}
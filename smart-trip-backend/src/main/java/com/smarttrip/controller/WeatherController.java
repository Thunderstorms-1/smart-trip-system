package com.smarttrip.controller;

import com.smarttrip.common.Result;
import com.smarttrip.entity.WeatherInfoVO;
import com.smarttrip.service.WeatherService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/weather")
@RequiredArgsConstructor
public class WeatherController {

    private final WeatherService weatherService;

    // 获取目的地气象与预警预测
    @GetMapping("/forecast")
    public Result<WeatherInfoVO> getWeatherForecast(
            @RequestParam(required = false, defaultValue = "成都") String destination,
            @RequestParam(required = false) String startDate
    ) {
        WeatherInfoVO forecast = weatherService.getForecast(destination, startDate);
        return Result.success(forecast);
    }
}
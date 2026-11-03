package com.smarttrip.controller;

import com.smarttrip.common.Result;
import com.smarttrip.service.*;
import java.time.LocalDate;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class GeoController {
  private final GeoService geo;
  private final AiProvider ai;

  @GetMapping("/api/capabilities")
  public Result<?> capabilities() {
    var capabilities = new java.util.LinkedHashMap<String, Object>();
    capabilities.put("ai", ai.available());
    capabilities.put("geo", geo.available());
    capabilities.put("map", geo.mapAvailable());
    capabilities.put("poi", geo.poiAvailable());
    capabilities.put("route", geo.routeAvailable());
    capabilities.put("weather", geo.weatherAvailable());
    capabilities.put("status", Map.of("ai", ai.status(), "geo", geo.status(), "map", geo.mapStatus(),
        "poi", geo.poiStatus(), "route", geo.routeStatus(), "weather", geo.weatherStatus()));
    capabilities.put("aiReason", ai.reason());
    capabilities.put("aiAction", ai.action());
    capabilities.put("aiConfigurationProblems", ai.configurationProblems());
    capabilities.put("notice", "本地配置完整仅表示可以尝试；未付费探测供应商权限、剩余额度或在线状态");
    return Result.success(capabilities);
  }

  @GetMapping("/api/geo/config")
  public Result<?> config() {
    return Result.success(geo.mapConfig());
  }

  @GetMapping("/api/geo/search")
  public Result<?> search(@RequestParam String city, @RequestParam String keyword) {
    return Result.success(geo.search(city, keyword));
  }

  @GetMapping("/api/geo/trips/{tripId}/days/{dayId}/route")
  public Result<?> route(
      @PathVariable Long tripId,
      @PathVariable Long dayId,
      @RequestParam(defaultValue = "walking") String mode) {
    return Result.success(geo.route(tripId, dayId, mode));
  }

  @GetMapping("/api/geo/weather")
  public Result<?> weather(@RequestParam String city, @RequestParam LocalDate date) {
    return Result.success(geo.weather(city, date));
  }
}

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
    return Result.success(
        Map.of(
            "ai",
            ai.available(),
            "geo",
            geo.available(),
            "map",
            geo.mapAvailable(),
            "status",
            Map.of("ai", ai.status(), "geo", geo.status(), "map", geo.mapStatus()),
            "notice",
            "仅在已确认免费权限且本机配置完整时启用外部服务"));
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

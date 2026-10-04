package com.smarttrip.controller;

import com.smarttrip.common.Result;
import com.smarttrip.service.ItineraryAnalysisService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/workbench/{tripId}/analyses")
@RequiredArgsConstructor
public class ItineraryAnalysisController {
  private final ItineraryAnalysisService analyses;

  public record Create(Long baseVersion, List<Long> dayIds, String mode) {}

  public record Source(Long dayId, String kind, Boolean refresh) {}

  @PostMapping
  public Result<?> create(@PathVariable Long tripId, @RequestBody Create r) {
    return Result.success(analyses.create(tripId, r.baseVersion(), r.dayIds(), r.mode()));
  }

  @GetMapping("/{id}")
  public Result<?> get(@PathVariable Long tripId, @PathVariable String id) {
    return Result.success(analyses.get(tripId, id));
  }

  @PostMapping("/{id}/sources")
  public Result<?> source(
      @PathVariable Long tripId, @PathVariable String id, @RequestBody Source r) {
    return Result.success(
        analyses.source(tripId, id, r.dayId(), r.kind(), Boolean.TRUE.equals(r.refresh())));
  }
}

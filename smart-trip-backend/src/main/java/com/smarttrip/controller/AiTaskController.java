package com.smarttrip.controller;

import com.smarttrip.common.Result;
import com.smarttrip.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ai/tasks")
@RequiredArgsConstructor
public class AiTaskController {
  private final AiTaskService tasks;
  private final AiTaskStore store;

  public record Request(String requestKey, String type, Long tripId, String prompt) {}

  @PostMapping
  public Result<?> submit(@RequestBody Request r) {
    return Result.success(tasks.submit(r.requestKey(), r.type(), r.tripId(), r.prompt()));
  }

  @GetMapping("/{id}")
  public Result<?> get(@PathVariable String id) {
    return Result.success(store.get(id));
  }

  @PostMapping("/{id}/apply")
  public Result<?> apply(@PathVariable String id) {
    return Result.success(store.apply(id));
  }
}

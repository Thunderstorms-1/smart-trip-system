package com.smarttrip.controller;

import com.smarttrip.common.Result;
import com.smarttrip.common.BusinessException;
import com.smarttrip.entity.TravelRequirement;
import com.smarttrip.entity.TripWriteDTO;
import com.smarttrip.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ai/tasks")
@RequiredArgsConstructor
public class AiTaskController {
  private final AiTaskService tasks;
  private final AiTaskStore store;
  private final TravelRequirementService requirements;

  public record Request(
      String requestKey, String type, Long tripId, String prompt, String analysisId,
      TravelRequirement requirement, Boolean confirmed, java.util.List<String> editedFields) {}

  public record RequirementRequest(TravelRequirement requirement, Boolean extract, java.util.List<String> editedFields) {}
  public record SaveRequest(String title, Long draftRevision) {}
  public record DraftRequest(TripWriteDTO draft, Long draftRevision) {}
  public record BudgetRequest(java.math.BigDecimal budgetMax, Boolean confirmed,
      Long draftRevision, String requestKey) {}

  @PostMapping("/requirements")
  public Result<?> review(@RequestBody RequirementRequest r) {
    return Result.success(requirements.review(r.requirement(), !Boolean.FALSE.equals(r.extract()), r.editedFields()));
  }

  @PostMapping
  public Result<?> submit(@RequestBody Request r) {
    if ("INTERPRET".equals(r.type())) {
      if (r.tripId() != null) throw new BusinessException(400, "需求理解不应指定已有路书");
      return Result.success(tasks.submitInterpretation(r.requestKey(), r.requirement(), r.editedFields()));
    }
    if ("GENERATE".equals(r.type())) {
      if (r.tripId() != null) throw new BusinessException(400, "生成任务不应指定已有路书");
      return Result.success(tasks.submitGeneration(r.requestKey(), r.requirement(), r.confirmed()));
    }
    if ("ADJUST".equals(r.type()))
      return Result.success(tasks.submitAdjustment(r.requestKey(), r.tripId(), r.analysisId()));
    return Result.success(tasks.submit(r.requestKey(), r.type(), r.tripId(), r.prompt()));
  }

  @GetMapping("/{id}")
  public Result<?> get(@PathVariable String id) {
    return Result.success(store.get(id));
  }

  @PutMapping("/{id}/draft")
  public Result<?> updateDraft(@PathVariable String id, @RequestBody DraftRequest r) {
    if (r.draft() == null) throw new BusinessException(400, "请提供要保存的草稿");
    return Result.success(store.updateDraft(id, r.draft().toTrip(), r.draftRevision()));
  }

  @PutMapping("/{id}/budget")
  public Result<?> raiseBudget(@PathVariable String id, @RequestBody BudgetRequest r) {
    return Result.success(store.raiseBudget(id, r.budgetMax(), r.confirmed(), r.draftRevision(), r.requestKey()));
  }

  @PostMapping("/{id}/recover")
  public Result<?> recover(@PathVariable String id) {
    return Result.success(store.recoverDraft(id));
  }

  @PostMapping("/{id}/apply")
  public Result<?> apply(@PathVariable String id, @RequestBody(required = false) SaveRequest r) {
    return Result.success(store.apply(id, r == null ? null : r.title(), r == null ? null : r.draftRevision()));
  }
}

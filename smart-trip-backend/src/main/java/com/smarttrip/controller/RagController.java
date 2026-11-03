package com.smarttrip.controller;

import com.smarttrip.common.BusinessException;
import com.smarttrip.common.Result;
import com.smarttrip.entity.TravelRequirement;
import com.smarttrip.rag.RagDiagnosticsService;
import com.smarttrip.service.ResourceAccess;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** Authenticated, read-only diagnostics for the explicitly public knowledge corpus. */
@RestController
@RequestMapping("/api/rag")
@RequiredArgsConstructor
public class RagController {
  private final ResourceAccess access;
  private final RagDiagnosticsService diagnostics;

  public record PreviewRequest(TravelRequirement requirement) {}

  @GetMapping("/status")
  public Result<?> status(@RequestParam(defaultValue = "false") boolean probe) {
    access.userId();
    return Result.success(diagnostics.status(probe));
  }

  @PostMapping("/preview")
  public Result<?> preview(@RequestBody PreviewRequest request) {
    access.userId();
    if (request == null || request.requirement() == null)
      throw new BusinessException(400, "请提供结构化旅行需求");
    return Result.success(diagnostics.preview(request.requirement()));
  }
}

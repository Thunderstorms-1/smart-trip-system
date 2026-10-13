package com.smarttrip.controller;

import com.smarttrip.common.Result;
import com.smarttrip.entity.ExpenseSummaryVO;
import com.smarttrip.entity.TripExpense;
import com.smarttrip.entity.TripPackingItem;
import com.smarttrip.service.ToolService;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/trip")
@RequiredArgsConstructor
public class ToolController {

  private final ToolService toolService;
  private final com.smarttrip.service.TravelToolsService tools;

  // 1. 添加记账
  @PostMapping("/{tripId}/expense")
  public Result<Void> addExpense(
      @PathVariable Long tripId,
      @RequestBody TripExpense expense,
      @RequestHeader(value = "Idempotency-Key", required = false) String requestKey,
      @RequestParam(value = "sourceItemId", required = false) Long sourceItemId) {
    tools.add(tripId, expense, requestKey, sourceItemId);
    return Result.success();
  }

  // 2. 获取费用统计看板
  @GetMapping("/{tripId}/expense/summary")
  public Result<ExpenseSummaryVO> getExpenseSummary(@PathVariable Long tripId) {
    return Result.success(toolService.getExpenseSummary(tripId));
  }

  // 3. 获取行李清单
  @GetMapping("/{tripId}/packing-list")
  public Result<List<TripPackingItem>> getPackingList(@PathVariable Long tripId) {
    return Result.success(toolService.getPackingList(tripId));
  }

  // 4. 切换清单打勾状态
  @PatchMapping("/packing-item/{itemId}/toggle")
  public Result<Void> togglePackingItem(
      @PathVariable Long itemId, @RequestBody Map<String, Integer> payload) {
    Integer isChecked = payload.get("isChecked");
    toolService.togglePackingItem(itemId, isChecked);
    return Result.success();
  }

  // 5. 从行程规划预估花费一键批量导入为记账流水
  @PostMapping("/{tripId}/expense/import-from-items")
  public Result<Integer> importExpensesFromTrip(@PathVariable Long tripId) {
    int count = toolService.importExpensesFromTrip(tripId);
    return Result.success(count);
  }
}

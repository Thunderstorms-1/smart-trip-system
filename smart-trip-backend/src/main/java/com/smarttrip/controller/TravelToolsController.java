package com.smarttrip.controller;

import com.smarttrip.common.Result;
import com.smarttrip.entity.TripExpense;
import com.smarttrip.service.TravelToolsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/tools")
@RequiredArgsConstructor
public class TravelToolsController {
  private final TravelToolsService tools;

  public record ExpenseInput(TripExpense expense, String requestKey, Long sourceItemId) {}

  public record PackingInput(String itemName, Boolean checked) {}

  @GetMapping("/trips/{id}/expenses")
  public Result<?> expenses(@PathVariable Long id) {
    return Result.success(tools.expenses(id));
  }

  @PostMapping("/trips/{id}/expenses")
  public Result<?> add(@PathVariable Long id, @RequestBody ExpenseInput input) {
    tools.add(id, input.expense(), input.requestKey(), input.sourceItemId());
    return Result.success();
  }

  @PutMapping("/expenses/{id}")
  public Result<?> edit(@PathVariable Long id, @RequestBody TripExpense input) {
    tools.edit(id, input);
    return Result.success();
  }

  @DeleteMapping("/expenses/{id}")
  public Result<?> delete(@PathVariable Long id) {
    tools.deleteExpense(id);
    return Result.success();
  }

  @GetMapping("/trips/{id}/packing")
  public Result<?> packing(@PathVariable Long id) {
    return Result.success(tools.packing(id));
  }

  @PostMapping("/trips/{id}/packing")
  public Result<?> addPacking(@PathVariable Long id, @RequestBody PackingInput input) {
    tools.addPacking(id, input.itemName());
    return Result.success();
  }

  @PostMapping("/trips/{id}/packing/template")
  public Result<?> template(@PathVariable Long id) {
    tools.template(id);
    return Result.success();
  }

  @PatchMapping("/packing/{id}")
  public Result<?> toggle(@PathVariable Long id, @RequestBody PackingInput input) {
    tools.packingItem(id, input.checked(), false);
    return Result.success();
  }

  @DeleteMapping("/packing/{id}")
  public Result<?> deletePacking(@PathVariable Long id) {
    tools.packingItem(id, null, true);
    return Result.success();
  }
}

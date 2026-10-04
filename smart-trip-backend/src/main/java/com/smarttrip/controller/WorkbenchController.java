package com.smarttrip.controller;

import com.smarttrip.common.Result;
import com.smarttrip.entity.TripDetailVO;
import com.smarttrip.entity.TripWriteDTO;
import com.smarttrip.service.WorkbenchService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/workbench")
@RequiredArgsConstructor
public class WorkbenchController {
  private final WorkbenchService trips;

  @GetMapping
  public Result<?> mine() {
    return Result.success(trips.mine());
  }

  @PostMapping
  public Result<TripDetailVO> create(@RequestBody TripWriteDTO t) {
    return Result.success(trips.create(t.toTrip()));
  }

  @GetMapping("/{id}")
  public Result<?> detail(@PathVariable Long id) {
    return Result.success(trips.load(id));
  }

  @PutMapping("/{id}")
  public Result<TripDetailVO> save(@PathVariable Long id, @RequestBody TripWriteDTO t) {
    return Result.success(trips.save(id, t.toTrip()));
  }

  @DeleteMapping("/{id}")
  public Result<?> delete(@PathVariable Long id) {
    trips.delete(id);
    return Result.success();
  }

  @GetMapping("/{id}/revisions")
  public Result<?> history(@PathVariable Long id) {
    return Result.success(trips.revisions(id));
  }

  @PostMapping("/{id}/revisions/{revision}/restore")
  public Result<?> restore(
      @PathVariable Long id, @PathVariable Long revision, @RequestBody Map<String, Long> body) {
    return Result.success(trips.restore(id, revision, body.get("version")));
  }
}

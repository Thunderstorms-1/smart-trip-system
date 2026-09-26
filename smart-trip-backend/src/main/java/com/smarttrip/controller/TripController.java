package com.smarttrip.controller;

import com.smarttrip.common.Result;
import com.smarttrip.entity.TripDetailVO;
import com.smarttrip.entity.TripItem;
import com.smarttrip.entity.TripItemAddDTO;
import com.smarttrip.entity.TripModifyDTO;
import com.smarttrip.service.TripService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/trip")
@RequiredArgsConstructor
public class TripController {

    private final TripService tripService;

    // 1. 获取路书完整详情（包含天数和节点）
    @GetMapping("/{tripId}")
    public Result<TripDetailVO> getTripDetail(@PathVariable Long tripId) {
        return Result.success(tripService.getTripDetail(tripId));
    }

    // 2. 修改某个节点卡片信息
    @PutMapping("/item/{itemId}")
    public Result<Void> updateTripItem(@PathVariable Long itemId, @RequestBody TripItem item) {
        tripService.updateItem(itemId, item);
        return Result.success();
    }

    // 3. 前端拖拽排序后批量保存
    @PutMapping("/day/{dayId}/items/sort")
    public Result<Void> sortItems(@PathVariable Long dayId, @RequestBody Map<String, List<Long>> payload) {
        List<Long> sortedIds = payload.get("sortedItemIds");
        tripService.sortItems(dayId, sortedIds);
        return Result.success();
    }

    // 4. AI 智能二次调整行程
    @PostMapping("/modify-by-ai")
    public Result<Void> modifyTripByAI(@RequestBody TripModifyDTO dto) {
        tripService.modifyTripByAI(dto);
        return Result.success();
    }

    // 5. 删除某个节点卡片（软删除，并触发总预算重新聚合计算）
    @DeleteMapping("/item/{itemId}")
    public Result<Void> deleteTripItem(@PathVariable Long itemId) {
        tripService.deleteTripItem(itemId);
        return Result.success();
    }

    // 6. 手动新增行程节点卡片（集成总预算重算联动）
    @PostMapping("/item/add")
    public Result<TripItem> addTripItem(@RequestBody TripItemAddDTO dto) {
        TripItem newItem = tripService.addTripItem(dto);
        return Result.success(newItem);
    }
}
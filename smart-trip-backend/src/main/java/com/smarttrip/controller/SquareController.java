package com.smarttrip.controller;

import com.smarttrip.common.Result;
import com.smarttrip.common.UserContext;
import com.smarttrip.entity.SquareTripVO;
import com.smarttrip.service.SquareService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/square")
@RequiredArgsConstructor
public class SquareController {

    private final SquareService squareService;

    // 1. 公开接口：获取广场公开路书列表
    @GetMapping("/trips")
    public Result<List<SquareTripVO>> getSquareTrips() {
        return Result.success(squareService.getSquareTrips());
    }

    // 2. 需登录：一键克隆路书
    @PostMapping("/trip/{tripId}/clone")
    public Result<Map<String, Object>> cloneTrip(@PathVariable Long tripId) {
        Long currentUserId = UserContext.getUserId();
        Long newTripId = squareService.cloneTrip(tripId, currentUserId);
        return Result.success(Map.of("newTripId", newTripId, "message", "克隆成功"));
    }
}
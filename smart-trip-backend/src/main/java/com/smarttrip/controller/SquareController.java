package com.smarttrip.controller;

import com.smarttrip.common.PageResult;
import com.smarttrip.common.Result;
import com.smarttrip.common.UserContext;
import com.smarttrip.entity.SquareQueryDTO;
import com.smarttrip.entity.SquareTripVO;
import com.smarttrip.service.SquareService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/square")
@RequiredArgsConstructor
public class SquareController {

    private final SquareService squareService;

    // 1. 公开接口：多条件复合检索与分页
    @PostMapping("/search")
    public Result<PageResult<SquareTripVO>> searchSquareTrips(@RequestBody SquareQueryDTO query) {
        PageResult<SquareTripVO> pageData = squareService.searchSquareTrips(query);
        return Result.success(pageData);
    }

    // 2. 一键克隆路书
    @PostMapping("/trip/{tripId}/clone")
    public Result<Map<String, Object>> cloneTrip(@PathVariable Long tripId) {
        Long currentUserId = UserContext.getUserId();
        Long newTripId = squareService.cloneTrip(tripId, currentUserId);
        return Result.success(Map.of("newTripId", newTripId, "message", "克隆成功"));
    }
}
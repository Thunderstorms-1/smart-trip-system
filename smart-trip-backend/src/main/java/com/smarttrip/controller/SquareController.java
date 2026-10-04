package com.smarttrip.controller;

import com.smarttrip.common.PageResult;
import com.smarttrip.common.Result;
import com.smarttrip.common.UserContext;
import com.smarttrip.entity.FeaturedThemeVO;
import com.smarttrip.entity.SquareQueryDTO;
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

    // 1. 公开广场检索
    @PostMapping("/search")
    public Result<PageResult<SquareTripVO>> searchSquareTrips(@RequestBody SquareQueryDTO query) {
        PageResult<SquareTripVO> pageData = squareService.searchSquareTrips(query);
        return Result.success(pageData);
    }

    // 2. 发布/同步当前行程快照至广场
    @PostMapping("/publish/{tripId}")
    public Result<Void> publishOrSyncTrip(@PathVariable Long tripId) {
        Long currentUserId = UserContext.getUserId();
        squareService.publishOrSyncTrip(tripId, currentUserId);
        return Result.success();
    }

    // 3. 设为私密（从广场下架）
    @PostMapping("/unpublish/{tripId}")
    public Result<Void> unpublishTrip(@PathVariable Long tripId) {
        Long currentUserId = UserContext.getUserId();
        squareService.unpublishTrip(tripId, currentUserId);
        return Result.success();
    }

    // 4. 从快照一键克隆
    @PostMapping("/clone/{squarePostId}")
    public Result<Map<String, Object>> cloneFromSnapshot(@PathVariable Long squarePostId) {
        Long currentUserId = UserContext.getUserId();
        Long newTripId = squareService.cloneFromSnapshot(squarePostId, currentUserId);
        return Result.success(Map.of("newTripId", newTripId, "message", "克隆成功"));
    }

    // 5. 【核心端点】：获取官方精选主题模板库 (动态查库，支持运营热更新)
    @GetMapping("/featured-templates")
    public Result<List<FeaturedThemeVO>> getFeaturedTemplates() {
        return Result.success(squareService.getFeaturedThemedTrips());
    }

    // 兼容老路径别名
    @GetMapping("/featured")
    public Result<List<FeaturedThemeVO>> getFeaturedThemesLegacy() {
        return Result.success(squareService.getFeaturedThemedTrips());
    }
}
package com.smarttrip.controller;

import com.smarttrip.common.Result;
import com.smarttrip.common.UserContext;
import com.smarttrip.entity.TripMember;
import com.smarttrip.service.TripMemberService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/trip")
@RequiredArgsConstructor
public class TripMemberController {

    private final TripMemberService tripMemberService;

    // 1. 获取专属邀请口令
    @GetMapping("/{tripId}/invite/code")
    public Result<Map<String, String>> getInviteCode(@PathVariable Long tripId) {
        Long currentUserId = UserContext.getUserId();
        String code = tripMemberService.generateInviteCode(tripId, currentUserId);
        return Result.success(Map.of("inviteCode", code));
    }

    // 2. 好友输入口令核销加入组团
    @PostMapping("/invite/join")
    public Result<Map<String, Object>> joinTrip(@RequestBody Map<String, String> payload) {
        String inviteCode = payload.get("inviteCode");
        Long currentUserId = UserContext.getUserId();
        Long tripId = tripMemberService.joinTripByCode(inviteCode, currentUserId);
        return Result.success(Map.of("tripId", tripId, "message", "成功加入协同行程！"));
    }

    // 3. 获取行程的所有成员列表
    @GetMapping("/{tripId}/members")
    public Result<List<TripMember>> getTripMembers(@PathVariable Long tripId) {
        return Result.success(tripMemberService.getTripMembers(tripId));
    }

    // 4. 移除成员或主动退出组团
    @DeleteMapping("/{tripId}/member/{targetUserId}")
    public Result<Void> removeMember(@PathVariable Long tripId, @PathVariable Long targetUserId) {
        Long currentUserId = UserContext.getUserId();
        tripMemberService.removeMember(tripId, targetUserId, currentUserId);
        return Result.success();
    }
}
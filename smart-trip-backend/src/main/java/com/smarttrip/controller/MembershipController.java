package com.smarttrip.controller;

import com.smarttrip.common.Result;
import com.smarttrip.service.MembershipService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/membership")
@RequiredArgsConstructor
public class MembershipController {
  private final MembershipService members;

  public record Invite(String role) {}

  public record Join(String inviteCode) {}

  @GetMapping("/trips/{id}")
  public Result<?> members(@PathVariable Long id) {
    return Result.success(members.members(id));
  }

  @GetMapping("/trips/{id}/invites")
  public Result<?> invites(@PathVariable Long id) {
    return Result.success(members.invites(id));
  }

  @PostMapping("/trips/{id}/invites")
  public Result<?> invite(@PathVariable Long id, @RequestBody Invite r) {
    return Result.success(members.invite(id, r.role()));
  }

  @DeleteMapping("/trips/{id}/invites/{invite}")
  public Result<?> revoke(@PathVariable Long id, @PathVariable Long invite) {
    members.revoke(id, invite);
    return Result.success();
  }

  @PostMapping("/join")
  public Result<?> join(@RequestBody Join r) {
    return Result.success(java.util.Map.of("tripId", members.join(r.inviteCode())));
  }

  @DeleteMapping("/trips/{id}/members/{target}")
  public Result<?> remove(@PathVariable Long id, @PathVariable Long target) {
    members.remove(id, target);
    return Result.success();
  }
}

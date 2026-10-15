package com.smarttrip.controller;

import com.smarttrip.common.Result;
import com.smarttrip.common.BusinessException;
import com.smarttrip.service.CommunityService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/community")
@RequiredArgsConstructor
public class CommunityController {
  private final CommunityService social;

  public record Interaction(String type, boolean enabled) {}

  public record Comment(String content) {}

  @GetMapping("/posts")
  public Result<?> feed(
      @RequestParam(defaultValue = "") String q,
      @RequestParam(required = false) Long author,
      @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "recent") String sort) {
    return Result.success(switch (sort) {
      case "recent" -> social.feed(q, author, cursor);
      case "popular" -> social.popular(q, author, cursor);
      default -> throw new BusinessException(400, "路书排序方式无效");
    });
  }

  @GetMapping("/posts/{id}")
  public Result<?> detail(@PathVariable Long id) {
    return Result.success(social.detail(id));
  }

  @GetMapping("/posts/{id}/comments")
  public Result<?> comments(@PathVariable Long id) {
    return Result.success(social.comments(id));
  }

  @PostMapping("/posts/{id}/comments")
  public Result<?> comment(@PathVariable Long id, @RequestBody Comment c) {
    social.comment(id, c.content());
    return Result.success();
  }

  @DeleteMapping("/comments/{id}")
  public Result<?> delete(@PathVariable Long id) {
    social.deleteComment(id);
    return Result.success();
  }

  @PutMapping("/posts/{id}/interaction")
  public Result<?> interact(@PathVariable Long id, @RequestBody Interaction i) {
    return Result.success(social.interact(id, i.type(), i.enabled()));
  }

  @PostMapping("/posts/{id}/clone")
  public Result<?> clonePost(@PathVariable Long id) {
    return Result.success(social.clonePost(id));
  }

  @GetMapping("/assets")
  public Result<?> assets(@RequestParam String type) {
    return Result.success(social.assets(type));
  }

  @PostMapping("/trips/{id}/publish")
  public Result<?> publish(@PathVariable Long id) {
    return Result.success(social.publish(id));
  }

  @DeleteMapping("/trips/{id}/publish")
  public Result<?> unpublish(@PathVariable Long id) {
    social.unpublish(id);
    return Result.success();
  }

  @PostMapping("/trips/{id}/shares")
  public Result<?> share(@PathVariable Long id) {
    return Result.success(social.share(id));
  }

  @GetMapping("/trips/{id}/shares")
  public Result<?> shares(@PathVariable Long id) {
    return Result.success(social.shares(id));
  }

  @DeleteMapping("/shares/{id}")
  public Result<?> revoke(@PathVariable Long id) {
    social.revoke(id);
    return Result.success();
  }

  @GetMapping("/share/{token}")
  public Result<?> readShare(@PathVariable String token) {
    return Result.success(social.readShare(token));
  }
}

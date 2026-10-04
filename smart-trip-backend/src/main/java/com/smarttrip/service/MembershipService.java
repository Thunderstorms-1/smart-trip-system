package com.smarttrip.service;

import com.smarttrip.common.*;
import java.security.SecureRandom;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MembershipService {
  private final JdbcTemplate db;
  private final ResourceAccess access;

  public MembershipService(JdbcTemplate db, ResourceAccess access) {
    this.db = db;
    this.access = access;
  }

  private void owner(Long trip) {
    if (!Objects.equals(access.read(trip).getUserId(), access.userId()))
      throw new BusinessException(403, "只有所有者可管理邀请及成员");
  }

  public List<Map<String, Object>> members(Long trip) {
    var t = access.read(trip);
    return db.queryForList(
        "SELECT u.id AS userId,u.nickname,u.avatar,CASE WHEN u.id=? THEN 'OWNER' ELSE m.role END AS"
            + " role FROM sys_user u LEFT JOIN trip_member m ON m.user_id=u.id AND m.trip_id=? AND"
            + " m.is_deleted=0 WHERE u.is_deleted=0 AND (u.id=? OR m.id IS NOT NULL)",
        t.getUserId(),
        trip,
        t.getUserId());
  }

  public Map<String, Object> invite(Long trip, String role) {
    owner(trip);
    if (!Set.of("EDITOR", "VIEWER").contains(role == null ? "" : role))
      throw new BusinessException(400, "角色无效");
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    String code = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    db.update(
        "INSERT INTO trip_invite(trip_id,token_hash,role,expires_at)"
            + " VALUES(?,?,?,DATE_ADD(NOW(),INTERVAL 24 HOUR))",
        trip,
        CommunityService.hash(code),
        role);
    return Map.of("inviteCode", code, "expiresInHours", 24, "role", role);
  }

  public List<Map<String, Object>> invites(Long trip) {
    owner(trip);
    return db.queryForList(
        "SELECT id,role,expires_at AS expiresAt,revoked FROM trip_invite WHERE trip_id=? ORDER BY"
            + " id DESC LIMIT 100",
        trip);
  }

  public void revoke(Long trip, Long id) {
    owner(trip);
    if (db.update("UPDATE trip_invite SET revoked=1 WHERE id=? AND trip_id=?", id, trip) != 1)
      throw new BusinessException(404, "邀请不存在");
  }

  @Transactional
  public Long join(String token) {
    Long user = access.userId();
    if (token == null || !token.matches("[A-Za-z0-9_-]{43}"))
      throw new BusinessException(400, "邀请口令无效");
    var rows =
        db.queryForList(
            "SELECT i.trip_id,i.role,t.user_id FROM trip_invite i JOIN trip_plan t ON"
                + " t.id=i.trip_id WHERE i.token_hash=? AND i.revoked=0 AND i.expires_at>NOW() AND"
                + " t.is_deleted=0 FOR UPDATE",
            CommunityService.hash(token));
    if (rows.isEmpty()) throw new BusinessException(404, "邀请已过期或撤销");
    var i = rows.get(0);
    Long trip = ((Number) i.get("trip_id")).longValue();
    if (((Number) i.get("user_id")).longValue() == user) return trip;
    var u = db.queryForMap("SELECT nickname,avatar FROM sys_user WHERE id=?", user);
    db.update(
        "INSERT INTO trip_member(trip_id,user_id,role,nickname_snapshot,avatar_snapshot,is_deleted)"
            + " VALUES(?,?,?,?,?,0) ON DUPLICATE KEY UPDATE"
            + " role=VALUES(role),is_deleted=0,nickname_snapshot=VALUES(nickname_snapshot),avatar_snapshot=VALUES(avatar_snapshot)",
        trip,
        user,
        i.get("role"),
        u.get("nickname"),
        u.get("avatar"));
    return trip;
  }

  @Transactional
  public void remove(Long trip, Long target) {
    var t = access.readLocked(trip);
    Long user = access.userId();
    if (Objects.equals(t.getUserId(), target)) throw new BusinessException(400, "不能移除所有者");
    if (!Objects.equals(t.getUserId(), user) && !Objects.equals(user, target))
      throw new BusinessException(403, "仅所有者可移除成员，成员可自行退出");
    if (db.update(
            "UPDATE trip_member SET is_deleted=1 WHERE trip_id=? AND user_id=? AND is_deleted=0",
            trip,
            target)
        != 1) throw new BusinessException(404, "成员不存在");
  }
}

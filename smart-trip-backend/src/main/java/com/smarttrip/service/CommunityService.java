package com.smarttrip.service;

import com.smarttrip.common.*;
import com.smarttrip.entity.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CommunityService {
  private final JdbcTemplate db;
  private final WorkbenchService trips;
  private final ResourceAccess access;

  public CommunityService(JdbcTemplate db, WorkbenchService trips, ResourceAccess access) {
    this.db = db;
    this.trips = trips;
    this.access = access;
  }

  private Long user() {
    return access.userId();
  }

  private void owner(Long id) {
    if (!Objects.equals(access.writeLocked(id).getUserId(), user()))
      throw new BusinessException(403, "仅所有者可发布或分享");
  }

  private TripDetailVO snapshot(Long id) {
    var t = trips.load(id);
    t.setUserId(null);
    t.setIsPublic(1);
    return t;
  }

  @Transactional
  public Map<String, Object> publish(Long id) {
    owner(id);
    db.queryForObject("SELECT id FROM trip_plan WHERE id=? FOR UPDATE", Long.class, id);
    var t = snapshot(id);
    var u = db.queryForMap("SELECT nickname,avatar FROM sys_user WHERE id=?", user());
    db.update(
        "INSERT INTO"
            + " square_post(trip_id,user_id,author_name,author_avatar,title,destination,total_days,total_budget,trip_style,summary,snapshot_json,is_deleted)"
            + " VALUES(?,?,?,?,?,?,?,?,?,?,?,0) ON DUPLICATE KEY UPDATE"
            + " author_name=VALUES(author_name),author_avatar=VALUES(author_avatar),title=VALUES(title),destination=VALUES(destination),total_days=VALUES(total_days),total_budget=VALUES(total_budget),trip_style=VALUES(trip_style),summary=VALUES(summary),snapshot_json=VALUES(snapshot_json),is_deleted=0,update_time=NOW()",
        id,
        user(),
        u.get("nickname"),
        u.get("avatar"),
        t.getTitle(),
        t.getDestination(),
        t.getTotalDays(),
        t.getTotalBudget(),
        t.getTripStyle(),
        t.getSummary(),
        trips.encode(t));
    db.update("UPDATE trip_plan SET is_public=1 WHERE id=?", id);
    return db.queryForMap("SELECT id FROM square_post WHERE trip_id=?", id);
  }

  @Transactional
  public void unpublish(Long id) {
    owner(id);
    db.update("UPDATE square_post SET is_deleted=1 WHERE trip_id=?", id);
    db.update("UPDATE trip_plan SET is_public=0 WHERE id=?", id);
  }

  private String columns() {
    return "p.id,p.user_id AS authorId,u.nickname AS authorName,u.avatar AS"
        + " authorAvatar,p.title,p.destination,p.total_days AS totalDays,p.total_budget AS"
        + " totalBudget,p.summary,p.trip_style AS tripStyle,p.update_time AS"
        + " publishedAt,(SELECT COUNT(*) FROM post_interaction i WHERE i.post_id=p.id AND"
        + " i.action_type='LIKE') AS likeCount,(SELECT COUNT(*) FROM post_interaction i"
        + " WHERE i.post_id=p.id AND i.action_type='FAVORITE') AS favoriteCount,(SELECT"
        + " COUNT(*) FROM post_comment c WHERE c.post_id=p.id AND c.is_deleted=0) AS"
        + " commentCount,p.clone_count AS cloneCount";
  }

  public PostPageDTO feed(String q, Long author, String cursor) {
    String search = q == null ? "" : q.trim();
    if (search.length() > 100) throw new BusinessException(400, "搜索词过长");
    String scope = hash(search + "\n" + author);
    String sql =
        "SELECT "
            + columns()
            + " FROM square_post p JOIN sys_user u ON u.id=p.user_id WHERE p.is_deleted=0 AND"
            + " u.is_deleted=0 AND u.status=1 AND (p.title LIKE ? OR p.destination LIKE ?)";
    List<Object> args = new ArrayList<>(List.of("%" + search + "%", "%" + search + "%"));
    if (author != null) {
      sql += " AND p.user_id=?";
      args.add(author);
    }
    if (cursor != null && !cursor.isBlank()) {
      try {
        if (cursor.length() > 300) throw new IllegalArgumentException();
        String[] fields =
            new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8)
                .split("\n", -1);
        if (fields.length != 3 || !scope.equals(fields[2])) throw new IllegalArgumentException();
        LocalDateTime time = LocalDateTime.parse(fields[0]);
        Long id = Long.parseLong(fields[1]);
        if (id < 1) throw new IllegalArgumentException();
        sql += " AND (p.update_time<? OR (p.update_time=? AND p.id<?))";
        args.add(time);
        args.add(time);
        args.add(id);
      } catch (Exception e) {
        throw new BusinessException(400, "翻页游标无效，请重新搜索");
      }
    }
    sql += " ORDER BY p.update_time DESC,p.id DESC LIMIT 21";
    var rows =
        db.query(sql, BeanPropertyRowMapper.newInstance(PostSummaryDTO.class), args.toArray());
    boolean more = rows.size() > 20;
    var items = new ArrayList<>(rows.subList(0, Math.min(20, rows.size())));
    String next = null;
    if (more) {
      var last = items.get(items.size() - 1);
      next =
          Base64.getUrlEncoder()
              .withoutPadding()
              .encodeToString(
                  (last.getPublishedAt() + "\n" + last.getId() + "\n" + scope)
                      .getBytes(StandardCharsets.UTF_8));
    }
    return new PostPageDTO(items, next, more);
  }

  private void active(Long id) {
    if (db.queryForObject(
            "SELECT COUNT(*) FROM square_post p JOIN sys_user u ON u.id=p.user_id WHERE p.id=? AND"
                + " p.is_deleted=0 AND u.is_deleted=0 AND u.status=1",
            Integer.class,
            id)
        == 0) throw new BusinessException(404, "作品不存在或已下架");
  }

  public PostDetailDTO detail(Long id) {
    active(id);
    var v =
        db.queryForObject(
            "SELECT "
                + columns()
                + ",p.snapshot_json FROM square_post p JOIN sys_user u ON u.id=p.user_id WHERE"
                + " p.id=?",
            (rs, n) -> {
              var value = BeanPropertyRowMapper.newInstance(PostDetailDTO.class).mapRow(rs, n);
              value.setTrip(trips.decode(rs.getString("snapshot_json")));
              return value;
            },
            id);
    Long uid = UserContext.getUserId();
    v.setLiked(uid != null && interaction(uid, id, "LIKE"));
    v.setFavorited(uid != null && interaction(uid, id, "FAVORITE"));
    return v;
  }

  private boolean interaction(Long u, Long p, String type) {
    return db.queryForObject(
            "SELECT COUNT(*) FROM post_interaction WHERE user_id=? AND post_id=? AND action_type=?",
            Integer.class,
            u,
            p,
            type)
        > 0;
  }

  @Transactional
  public PostDetailDTO interact(Long id, String type, boolean enabled) {
    user();
    if (!Set.of("LIKE", "FAVORITE").contains(type)) throw new BusinessException(400, "互动类型无效");
    db.queryForObject(
        "SELECT id FROM square_post WHERE id=? AND is_deleted=0 FOR UPDATE", Long.class, id);
    active(id);
    if (enabled)
      db.update(
          "INSERT INTO post_interaction(user_id,post_id,action_type) VALUES(?,?,?) ON DUPLICATE KEY"
              + " UPDATE user_id=VALUES(user_id)",
          user(),
          id,
          type);
    else
      db.update(
          "DELETE FROM post_interaction WHERE user_id=? AND post_id=? AND action_type=?",
          user(),
          id,
          type);
    return detail(id);
  }

  public List<Map<String, Object>> assets(String type) {
    if (!Set.of("LIKE", "FAVORITE").contains(type)) throw new BusinessException(400, "互动类型无效");
    return db.queryForList(
        "SELECT "
            + columns()
            + " FROM square_post p JOIN sys_user u ON u.id=p.user_id JOIN post_interaction i ON"
            + " i.post_id=p.id WHERE i.user_id=? AND i.action_type=? AND p.is_deleted=0 ORDER BY"
            + " i.create_time DESC LIMIT 200",
        user(),
        type);
  }

  public List<Map<String, Object>> comments(Long id) {
    active(id);
    return db.queryForList(
        "SELECT c.id,c.user_id AS authorId,u.nickname AS authorName,u.avatar AS"
            + " authorAvatar,c.content,c.create_time AS createdAt FROM post_comment c JOIN sys_user"
            + " u ON u.id=c.user_id WHERE c.post_id=? AND c.is_deleted=0 ORDER BY c.id DESC LIMIT"
            + " 100",
        id);
  }

  @Transactional
  public void comment(Long id, String content) {
    active(id);
    if (content == null || content.isBlank() || content.length() > 1000)
      throw new BusinessException(400, "评论应为1至1000字");
    db.update(
        "INSERT INTO post_comment(post_id,user_id,content) VALUES(?,?,?)",
        id,
        user(),
        content.trim());
  }

  public void deleteComment(Long id) {
    if (db.update(
            "UPDATE post_comment SET is_deleted=1 WHERE id=? AND user_id=? AND is_deleted=0",
            id,
            user())
        != 1) throw new BusinessException(403, "只能删除自己的评论");
  }

  @Transactional
  public TripDetailVO clonePost(Long id) {
    db.queryForObject(
        "SELECT id FROM square_post WHERE id=? AND is_deleted=0 FOR UPDATE", Long.class, id);
    active(id);
    var t =
        trips.decode(
            db.queryForObject(
                "SELECT snapshot_json FROM square_post WHERE id=?", String.class, id));
    t.setTitle(
        t.getTitle().length() > 94
            ? t.getTitle().substring(0, 94) + " · 副本"
            : t.getTitle() + " · 副本");
    for (var day : t.getDays()) for (var item : day.getItems()) item.setScheduleLocked(false);
    var copy = trips.create(t);
    db.update("UPDATE square_post SET clone_count=clone_count+1 WHERE id=?", id);
    return copy;
  }

  public static String hash(String token) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Transactional
  public Map<String, Object> share(Long id) {
    owner(id);
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    db.update(
        "INSERT INTO trip_share(trip_id,user_id,token_hash,snapshot_json) VALUES(?,?,?,?)",
        id,
        user(),
        hash(token),
        trips.encode(snapshot(id)));
    return Map.of("token", token, "path", "/share/" + token);
  }

  public TripDetailVO readShare(String token) {
    if (token == null || !token.matches("[A-Za-z0-9_-]{43}"))
      throw new BusinessException(404, "分享不存在");
    var rows =
        db.queryForList(
            "SELECT s.snapshot_json FROM trip_share s JOIN trip_plan t ON t.id=s.trip_id WHERE"
                + " s.token_hash=? AND s.revoked=0 AND t.is_deleted=0",
            hash(token));
    if (rows.isEmpty()) throw new BusinessException(404, "分享已撤销或不存在");
    return trips.decode((String) rows.get(0).get("snapshot_json"));
  }

  public List<Map<String, Object>> shares(Long id) {
    if (!Objects.equals(access.read(id).getUserId(), user()))
      throw new BusinessException(403, "仅所有者可查看分享记录");
    return db.queryForList(
        "SELECT id,revoked,create_time AS createdAt FROM trip_share WHERE trip_id=? ORDER BY id"
            + " DESC LIMIT 100",
        id);
  }

  public void revoke(Long id) {
    if (db.update("UPDATE trip_share SET revoked=1 WHERE id=? AND user_id=?", id, user()) != 1)
      throw new BusinessException(404, "分享不存在");
  }
}

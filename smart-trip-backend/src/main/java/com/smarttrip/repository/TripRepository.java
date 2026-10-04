package com.smarttrip.repository;

import com.smarttrip.common.BusinessException;
import com.smarttrip.entity.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** A single JDBC boundary for the private itinerary aggregate. */
@Repository
@RequiredArgsConstructor
public class TripRepository {
  private final JdbcTemplate db;

  private TripDetailVO header(Long id, boolean lock) {
    if (id == null) throw new BusinessException(400, "行程ID不能为空");
    var rows =
        db.query(
            "SELECT * FROM trip_plan WHERE id=? AND is_deleted=0" + (lock ? " FOR UPDATE" : ""),
            BeanPropertyRowMapper.newInstance(TripDetailVO.class),
            id);
    if (rows.isEmpty()) throw new BusinessException(404, "行程不存在");
    return rows.get(0);
  }

  public TripDetailVO find(Long id) {
    return header(id, false);
  }

  public TripDetailVO lock(Long id) {
    return header(id, true);
  }

  public TripDetailVO tree(TripDetailVO t, boolean includeDeleted) {
    String active = includeDeleted ? "" : " AND is_deleted=0";
    var days =
        db.query(
            "SELECT * FROM trip_day WHERE trip_id=?" + active + " ORDER BY day_index,id",
            BeanPropertyRowMapper.newInstance(TripDayVO.class),
            t.getId());
    var items =
        db.query(
            "SELECT * FROM trip_item WHERE trip_id=?" + active + " ORDER BY sort_order,id",
            BeanPropertyRowMapper.newInstance(TripItem.class),
            t.getId());
    Map<Long, List<TripItem>> grouped = new HashMap<>();
    for (var item : items)
      grouped.computeIfAbsent(item.getDayId(), k -> new ArrayList<>()).add(item);
    for (var day : days) day.setItems(grouped.getOrDefault(day.getId(), new ArrayList<>()));
    t.setDays(days);
    return t;
  }

  public String lockedRole(Long trip, Long user) {
    var roles =
        db.queryForList(
            "SELECT role FROM trip_member WHERE trip_id=? AND user_id=? AND is_deleted=0 FOR"
                + " UPDATE",
            String.class,
            trip,
            user);
    return roles.isEmpty() ? null : roles.get(0);
  }

  public List<TripDetailVO> mine(Long user) {
    return db.query(
        "SELECT * FROM trip_plan t WHERE t.is_deleted=0 AND (t.user_id=? OR EXISTS (SELECT 1 FROM"
            + " trip_member m WHERE m.trip_id=t.id AND m.user_id=? AND m.is_deleted=0 AND m.role IN"
            + " ('OWNER','EDITOR','VIEWER'))) ORDER BY t.update_time DESC,t.id DESC",
        BeanPropertyRowMapper.newInstance(TripDetailVO.class),
        user,
        user);
  }

  public TripDayVO day(Long id) {
    var rows =
        db.query(
            "SELECT * FROM trip_day WHERE id=? AND is_deleted=0",
            BeanPropertyRowMapper.newInstance(TripDayVO.class),
            id);
    return rows.isEmpty() ? null : rows.get(0);
  }

  public TripItem item(Long id) {
    var rows =
        db.query(
            "SELECT * FROM trip_item WHERE id=? AND is_deleted=0",
            BeanPropertyRowMapper.newInstance(TripItem.class),
            id);
    return rows.isEmpty() ? null : rows.get(0);
  }
}

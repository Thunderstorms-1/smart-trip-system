package com.smarttrip.service;

import com.smarttrip.common.BusinessException;
import com.smarttrip.common.UserContext;
import com.smarttrip.entity.TripDayVO;
import com.smarttrip.entity.TripDetailVO;
import com.smarttrip.entity.TripItem;
import com.smarttrip.mapper.TripMemberMapper;
import com.smarttrip.repository.TripRepository;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ResourceAccess {
  private final TripRepository trips;
  private final TripMemberMapper members;

  public Long userId() {
    Long id = UserContext.getUserId();
    if (id == null) throw new BusinessException(401, "请先登录");
    return id;
  }

  private TripDetailVO load(Long tripId) {
    if (tripId == null) throw new BusinessException(400, "行程ID不能为空");
    TripDetailVO trip = trips.find(tripId);
    if (trip == null) throw new BusinessException(404, "行程不存在");
    return trip;
  }

  /** Caller transaction locks the plan first; membership removal takes the same lock. */
  public TripDetailVO writeLocked(Long tripId) {
    return locked(tripId, true);
  }

  public TripDetailVO readLocked(Long tripId) {
    return locked(tripId, false);
  }

  private TripDetailVO locked(Long id, boolean write) {
    Long user = userId();
    var t = trips.lock(id);
    if (Objects.equals(t.getUserId(), user)) return t;
    String role = trips.lockedRole(id, user);
    if (role == null
        || !(write
                ? java.util.Set.of("OWNER", "EDITOR")
                : java.util.Set.of("OWNER", "EDITOR", "VIEWER"))
            .contains(role)) throw new BusinessException(403, write ? "无权修改该行程" : "无权读取该行程");
    return t;
  }

  public TripDetailVO read(Long tripId) {
    Long user = userId();
    TripDetailVO trip = load(tripId);
    if (Objects.equals(trip.getUserId(), user)) return trip;
    boolean member =
        members.selectActiveMembersByTripId(tripId).stream()
            .anyMatch(
                m ->
                    Objects.equals(m.getUserId(), user)
                        && m.getRole() != null
                        && java.util.Set.of("OWNER", "EDITOR", "VIEWER").contains(m.getRole()));
    if (!member) throw new BusinessException(403, "无权读取该行程");
    return trip;
  }

  public TripDetailVO write(Long tripId) {
    Long user = userId();
    TripDetailVO trip = load(tripId);
    if (!Objects.equals(trip.getUserId(), user) && members.checkUserIsEditor(tripId, user) == 0)
      throw new BusinessException(403, "无权修改该行程");
    return trip;
  }

  public TripDayVO day(Long dayId) {
    TripDayVO day = dayId == null ? null : trips.day(dayId);
    if (day == null) throw new BusinessException(404, "日程不存在");
    write(day.getTripId());
    return day;
  }

  public TripItem item(Long itemId) {
    TripItem item = itemId == null ? null : trips.item(itemId);
    if (item == null || Integer.valueOf(1).equals(item.getIsDeleted()))
      throw new BusinessException(404, "节点不存在");
    write(item.getTripId());
    return item;
  }
}

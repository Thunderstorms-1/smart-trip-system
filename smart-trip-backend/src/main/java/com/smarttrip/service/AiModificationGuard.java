package com.smarttrip.service;

import com.smarttrip.common.BusinessException;
import com.smarttrip.entity.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.beans.BeanUtils;

/** MODIFY may edit unlocked content, but model output cannot remove or weaken user locks. */
public final class AiModificationGuard {
  private AiModificationGuard() {}

  private record Position(Long dayId, int dayOrder, int itemOrder, TripItem item) {}

  public static void verify(TripDetailVO base, TripDetailVO candidate) {
    if (candidate == null || candidate.getDays() == null) invalid("修改提案缺少日程");
    Map<Long, Position> original = new HashMap<>();
    Set<Long> days = new HashSet<>(), locked = new HashSet<>();
    for (int d = 0; d < base.getDays().size(); d++) {
      var day = base.getDays().get(d);
      days.add(day.getId());
      for (int n = 0; n < day.getItems().size(); n++) {
        var item = day.getItems().get(n);
        original.put(item.getId(), new Position(day.getId(), d, n, item));
        if (Boolean.TRUE.equals(item.getScheduleLocked())) locked.add(item.getId());
      }
    }
    if (!locked.isEmpty()
        && (!Objects.equals(base.getStartDate(), candidate.getStartDate())
            || !Objects.equals(base.getDestination(), candidate.getDestination())))
      invalid("包含固定安排时不能改变目的地或出发日期");
    Set<Long> seen = new HashSet<>(), seenDays = new HashSet<>();
    for (int d = 0; d < candidate.getDays().size(); d++) {
      var day = candidate.getDays().get(d);
      if (day == null || day.getItems() == null) invalid("修改提案日程结构无效");
      if (day.getId() != null && (!days.contains(day.getId()) || !seenDays.add(day.getId())))
        invalid("修改提案包含重复或不属于当前路书的日程");
      for (int n = 0; n < day.getItems().size(); n++) {
        var item = day.getItems().get(n);
        if (item == null) invalid("修改提案节点结构无效");
        if (item.getId() == null) continue;
        var before = original.get(item.getId());
        if (before == null || !seen.add(item.getId())) invalid("修改提案包含重复或不属于当前路书的节点");
        var old = before.item();
        if (item.getScheduleLocked() != null
            && !Objects.equals(old.getScheduleLocked(), item.getScheduleLocked()))
          invalid("AI不能更改用户的固定安排标记");
        item.setScheduleLocked(old.getScheduleLocked());
        if (item.getEnvironmentType() == null) item.setEnvironmentType(old.getEnvironmentType());
        if (!Boolean.TRUE.equals(old.getScheduleLocked())) continue;
        if (!Objects.equals(before.dayId(), day.getId())
            || before.dayOrder() != d
            || before.itemOrder() != n
            || !Objects.equals(old.getItemName(), item.getItemName())
            || !Objects.equals(old.getItemType(), item.getItemType())
            || !Objects.equals(old.getStartTime(), item.getStartTime())
            || !decimalSame(old.getCostTimeHours(), item.getCostTimeHours())
            || !providedDecimalSame(old.getCostEstimate(), item.getCostEstimate())
            || !providedSame(old.getAiTips(), item.getAiTips())
            || !providedSame(old.getTransportTips(), item.getTransportTips())
            || !providedSame(old.getEnvironmentType(), item.getEnvironmentType())
            || !providedSame(old.getPoiId(), item.getPoiId())
            || !providedSame(old.getAddress(), item.getAddress())
            || !providedSame(old.getCoordinateSystem(), item.getCoordinateSystem())
            || !providedDecimalSame(old.getLongitude(), item.getLongitude())
            || !providedDecimalSame(old.getLatitude(), item.getLatitude()))
          invalid("固定安排不能移动、删除或更改内容与时间");
        // Optional fields omitted by the model retain the exact saved values and location
        // references.
        BeanUtils.copyProperties(old, item);
      }
    }
    if (!seen.containsAll(locked)) invalid("修改提案不能删除固定安排或替换其ID");
    candidate.setUserId(base.getUserId());
    candidate.setIsPublic(base.getIsPublic());
  }

  private static boolean decimalSame(BigDecimal a, BigDecimal b) {
    return a == null ? b == null : b != null && a.compareTo(b) == 0;
  }

  private static boolean providedDecimalSame(BigDecimal a, BigDecimal b) {
    return b == null || decimalSame(a, b);
  }

  private static boolean providedSame(Object a, Object b) {
    return b == null || Objects.equals(a, b);
  }

  private static void invalid(String message) {
    throw new BusinessException(422, message);
  }
}

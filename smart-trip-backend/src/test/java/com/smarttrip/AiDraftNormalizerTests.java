package com.smarttrip;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smarttrip.entity.*;
import com.smarttrip.service.AiDraftNormalizer;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;

class AiDraftNormalizerTests {
  private final AiDraftNormalizer normalizer = new AiDraftNormalizer();

  private TripItem node(String name, String type, String time, String hours, String cost, boolean fixed) {
    var item = new TripItem();
    item.setItemName(name);
    item.setItemType(type);
    item.setStartTime(time);
    item.setCostTimeHours(hours == null ? null : new BigDecimal(hours));
    item.setCostEstimate(cost == null ? null : new BigDecimal(cost));
    item.setScheduleLocked(fixed);
    return item;
  }

  private TripDetailVO trip(TripItem... items) {
    var trip = new TripDetailVO();
    trip.setTitle("固定响应测试");
    trip.setDestination("稻城亚丁");
    trip.setSummary("固定fixture，费用仅为估算。");
    trip.setStartDate(LocalDate.of(2026, 10, 28));
    trip.setTotalDays(99);
    trip.setTotalBudget(new BigDecimal("999"));
    var day = new TripDayVO();
    day.setDayIndex(42);
    day.setDayDate(trip.getStartDate());
    day.setDayTheme("慢游");
    day.setItems(new ArrayList<>(Arrays.asList(items)));
    trip.setDays(new ArrayList<>(List.of(day)));
    return trip;
  }

  private TravelRequirement requirement(Map<String, Object> fields) {
    return new ObjectMapper().convertValue(fields, TravelRequirement.class);
  }

  @Test
  void sortsAndCascadesAllKnownOverlapsWithoutChangingOriginalInput() {
    var hotel = node("入住", "HOTEL", "14:00", "1", "10", false);
    var meal = node("餐饮", "FOOD", "12:30", "2", "10", false);
    var scenic = node("观景", "SCENIC", "12:00", "1", "10", false);
    var walk = node("漫步", "OTHER", "15:30", "1", "10", false);
    var input = trip(hotel, meal, scenic, walk);
    var result = normalizer.normalize(input, null, true);
    assertTrue(result.issues().isEmpty());
    assertEquals(List.of("12:00", "13:00", "15:00", "16:00"), result.draft().getDays().get(0)
        .getItems().stream().map(TripItem::getStartTime).toList());
    assertEquals(0, result.draft().getTotalBudget().compareTo(new BigDecimal("40")));
    assertEquals(1, result.draft().getDays().get(0).getDayIndex());
    assertEquals("14:00", hotel.getStartTime());
    assertEquals("12:30", meal.getStartTime());
    assertEquals(99, input.getTotalDays());
    assertEquals(0, input.getTotalBudget().compareTo(new BigDecimal("999")));
  }

  @Test
  void reservesFixedWindowsAndCascadesOrdinaryActivitiesAfterThem() {
    var input = trip(node("普通A", "SCENIC", "09:30", "2", "10", false),
        node("已预约", "SCENIC", "10:00", "1", "10", true),
        node("普通B", "FOOD", "10:15", "1", "10", false));
    var result = normalizer.normalize(input, null, true);
    assertTrue(result.issues().isEmpty());
    var items = result.draft().getDays().get(0).getItems();
    assertEquals(List.of("已预约", "普通A", "普通B"), items.stream().map(TripItem::getItemName).toList());
    assertEquals(List.of("10:00", "11:00", "13:00"), items.stream().map(TripItem::getStartTime).toList());
    assertTrue(items.get(0).getScheduleLocked());
  }

  @Test
  void fixedCollisionsAndMidnightOverflowRemainBlockingWithoutShorteningDurations() {
    var fixed = normalizer.normalize(trip(node("固定A", "SCENIC", "10:00", "2", "10", true),
        node("固定B", "FOOD", "11:00", "1", "10", true)), null, true);
    assertTrue(fixed.issues().stream().anyMatch(i -> i.code().equals("FIXED_NODE_CONFLICT")));
    assertEquals(List.of("10:00", "11:00"), fixed.draft().getDays().get(0).getItems().stream()
        .map(TripItem::getStartTime).toList());
    var overflow = normalizer.normalize(trip(node("深夜活动", "OTHER", "23:00", "2", "10", false)), null, true);
    assertTrue(overflow.issues().stream().anyMatch(i -> i.code().equals("DAY_END_OVERFLOW")));
    var unchanged = overflow.draft().getDays().get(0).getItems().get(0);
    assertEquals("23:00", unchanged.getStartTime());
    assertEquals(0, unchanged.getCostTimeHours().compareTo(new BigDecimal("2")));
  }

  @Test
  void explicitUserTimeLimitsPreventAutomaticTimeChanges() {
    for (var fields : List.<Map<String, Object>>of(
        Map.of("timeConstraints", "必须十二点到酒店"),
        Map.of("description", "下午14:00必须集合"),
        Map.of("notes", "必须十点前到车站"),
        Map.of("mustVisit", List.of("人民公园09:00预约")))) {
      var result = normalizer.normalize(trip(node("观景", "SCENIC", "12:00", "1", "10", false),
          node("午餐", "FOOD", "12:30", "2", "10", false)), requirement(fields), true);
      assertTrue(result.issues().stream().anyMatch(i -> i.code().equals("TIME_OVERLAP")));
      assertEquals(List.of("12:00", "12:30"), result.draft().getDays().get(0).getItems().stream()
          .map(TripItem::getStartTime).toList());
      assertTrue(result.warnings().stream().anyMatch(w -> w.contains("明确时间限制")));
    }
  }

  @Test
  void unknownCostsAndTimesStayUnknownRatherThanBeingInventedOrTreatedAsFree() {
    var result = normalizer.normalize(trip(node("待确认", "OTHER", null, null, null, false),
        node("免费活动", "SCENIC", "12:00", "1", "0", false)), null, true);
    assertTrue(result.issues().stream().anyMatch(i -> i.code().equals("MISSING_COST")));
    assertTrue(result.issues().stream().anyMatch(i -> i.code().equals("MISSING_DURATION")));
    assertNull(result.draft().getTotalBudget());
    var unknown = result.draft().getDays().get(0).getItems().stream()
        .filter(i -> i.getItemName().equals("待确认")).findFirst().orElseThrow();
    assertNull(unknown.getStartTime());
    assertNull(unknown.getCostEstimate());
    assertNull(unknown.getCostTimeHours());
  }

  @Test
  void excessiveRealCostStaysVisibleWhileAliasesAndUnverifiedGeographyAreNormalized() {
    var item = node("观景", "attraction", "10:00", "1", "35360", false);
    item.setLongitude(new BigDecimal("181"));
    item.setPoiId("UNVERIFIED");
    item.setRating(5d);
    item.setCoverImage("https://unverified.invalid/image.png");
    var input = trip(item);
    input.getDays().get(0).setDayDate(input.getStartDate().plusDays(1));
    var r = requirement(Map.of("days", 10, "travelers", 6, "budget", 3000,
        "budgetMax", 4500, "budgetScope", "PER_PERSON", "currency", "CNY"));
    var result = normalizer.normalize(input, r, true);
    assertTrue(result.issues().stream().anyMatch(i -> i.code().equals("BUDGET_EXCEEDED")
        && i.message().contains("35360") && i.message().contains("27000")));
    var normalized = result.draft().getDays().get(0).getItems().get(0);
    assertEquals("SCENIC", normalized.getItemType());
    assertEquals(0, normalized.getCostEstimate().compareTo(new BigDecimal("35360")));
    assertNull(normalized.getLongitude());
    assertNull(normalized.getPoiId());
    assertNull(normalized.getRating());
    assertNull(normalized.getCoverImage());
    assertEquals(input.getStartDate().plusDays(1), result.draft().getDays().get(0).getDayDate());
  }

  @Test
  void issueIndicesPointAtReturnedOrderWhenSchedulingIsDisabled() {
    var result = normalizer.normalize(trip(node("A", "SCENIC", "12:00", "2", "10", false),
        node("B", "SCENIC", "09:00", "2", "10", false),
        node("C", "FOOD", "10:00", "1", "10", false)), null, false);
    var conflict = result.issues().stream().filter(i -> i.code().equals("TIME_OVERLAP")).findFirst().orElseThrow();
    assertEquals(1, conflict.dayIndex());
    assertEquals(2, conflict.itemIndex());
    assertEquals(1, conflict.relatedItemIndex());
    assertEquals("C", result.draft().getDays().get(0).getItems().get(conflict.itemIndex() - 1).getItemName());
  }

  @Test
  void failedMidnightShiftsStillDetectEveryRetainedOverlapAndFixedCollision() {
    var result = normalizer.normalize(trip(node("夜景", "SCENIC", "23:00", "1", "10", false),
        node("夜宵", "FOOD", "23:15", "0.5", "10", false),
        node("固定集合", "TRAFFIC", "23:30", "0.5", "10", true)), null, true);
    var items = result.draft().getDays().get(0).getItems();
    assertEquals(List.of("23:00", "23:15", "23:30"), items.stream().map(TripItem::getStartTime).toList());
    assertEquals(2, result.issues().stream().filter(i -> i.code().equals("DAY_END_OVERFLOW")).count());
    assertTrue(result.issues().stream().anyMatch(i -> i.code().equals("TIME_OVERLAP")
        && i.itemIndex() == 2 && i.relatedItemIndex() == 1
        && i.message().contains("夜景") && i.message().contains("23:00–24:00")
        && i.message().contains("夜宵") && i.message().contains("23:15–23:45")));
    assertEquals(2, result.issues().stream().filter(i -> i.code().equals("FIXED_NODE_CONFLICT")).count());
    assertTrue(result.issues().stream().filter(i -> i.code().equals("FIXED_NODE_CONFLICT"))
        .allMatch(i -> i.message().contains("固定集合") && i.message().contains("23:30–24:00")));
  }

  @Test
  void longActivityCannotHideCollisionBetweenTwoFixedAnchors() {
    var result = normalizer.normalize(trip(node("全天活动", "OTHER", "09:00", "6", "10", false),
        node("固定参观", "SCENIC", "10:00", "3", "10", true),
        node("固定集合", "TRAFFIC", "11:00", "0.5", "10", true)), null, false);
    assertEquals(3, result.issues().stream().filter(i -> i.code().equals("FIXED_NODE_CONFLICT")).count());
    assertTrue(result.issues().stream().anyMatch(i -> i.itemIndex() == 3 && i.relatedItemIndex() == 2
        && i.message().contains("固定参观") && i.message().contains("10:00–13:00")
        && i.message().contains("固定集合") && i.message().contains("11:00–11:30")));
  }

  @Test
  void roundsKnownItemCostsBeforeSummingAndCheckingBudgetWithoutChangingInput() {
    var first = node("首项", "OTHER", "09:00", "1", "1.005", false);
    var second = node("次项", "OTHER", "10:00", "1", "2.004", false);
    var result = normalizer.normalize(trip(first, second),
        requirement(Map.of("budget", 3, "budgetScope", "TOTAL", "currency", "CNY")), false);
    assertEquals(new BigDecimal("3.01"), result.draft().getTotalBudget());
    assertEquals(List.of(new BigDecimal("1.01"), new BigDecimal("2.00")), result.draft().getDays().get(0)
        .getItems().stream().map(TripItem::getCostEstimate).toList());
    assertTrue(result.issues().stream().anyMatch(i -> i.code().equals("BUDGET_EXCEEDED")));
    assertEquals(new BigDecimal("1.005"), first.getCostEstimate());
    assertEquals(new BigDecimal("2.004"), second.getCostEstimate());
  }

  @Test
  void unsupportedDurationPrecisionBlocksSavingBeforeWorkbenchRoundingCreatesOverlap() {
    for (boolean autoSchedule : List.of(true, false)) {
      var result = normalizer.normalize(trip(node("短活动", "OTHER", "09:00", "0.25", "10", true),
          node("下一项", "FOOD", "09:15", "0.5", "10", false)), null, autoSchedule);
      assertTrue(result.issues().stream().anyMatch(i -> i.code().equals("DURATION_PRECISION")
          && i.itemIndex() == 1 && i.message().contains("0.25")));
      var items = result.draft().getDays().get(0).getItems();
      assertEquals(new BigDecimal("0.25"), items.get(0).getCostTimeHours());
      assertEquals(List.of("09:00", "09:15"), items.stream().map(TripItem::getStartTime).toList());
      assertTrue(result.issues().stream().noneMatch(i -> i.code().equals("TIME_OVERLAP")));
    }
  }

  @Test
  void missingFixedOrOrdinaryTimingDoesNotPermitSpeculativeShifts() {
    for (var unknown : List.of(node("时间待定", "OTHER", null, "1", "10", true),
        node("时长待定", "OTHER", "08:00", null, "10", true),
        node("时间待定", "OTHER", null, "1", "10", false))) {
      var result = normalizer.normalize(trip(unknown,
          node("前项", "SCENIC", "12:00", "1", "10", false),
          node("后项", "FOOD", "12:30", "1", "10", false)), null, true);
      var retained = result.draft().getDays().get(0).getItems().stream()
          .filter(i -> i.getItemName().equals(unknown.getItemName())).findFirst().orElseThrow();
      assertEquals(unknown.getStartTime(), retained.getStartTime());
      assertEquals(unknown.getCostTimeHours(), retained.getCostTimeHours());
      assertTrue(result.issues().stream().anyMatch(i -> i.code().equals("TIME_OVERLAP")));
      assertEquals("12:30", result.draft().getDays().get(0).getItems().stream()
          .filter(i -> i.getItemName().equals("后项")).findFirst().orElseThrow().getStartTime());
      assertTrue(result.warnings().stream().anyMatch(w -> w.contains("未自动顺延")));
    }
  }

  @Test
  void aggregateMealLabelsWarnWithoutInventingSplitMealsOrBlockingOnlyForTheirName() {
    for (var label : List.of("三餐", "午餐+晚餐", "午晚餐")) {
      var result = normalizer.normalize(trip(node(label, "FOOD", "12:00", "2", "50", false)), null, true);
      assertTrue(result.issues().isEmpty());
      assertTrue(result.warnings().stream().anyMatch(w -> w.contains(label) && w.contains("手动拆分或核对")));
      var item = result.draft().getDays().get(0).getItems().get(0);
      assertEquals(label, item.getItemName());
      assertEquals("12:00", item.getStartTime());
      assertEquals(new BigDecimal("2"), item.getCostTimeHours());
      assertEquals(1, result.draft().getDays().get(0).getItems().size());
    }
  }
}

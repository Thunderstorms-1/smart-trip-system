package com.smarttrip;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smarttrip.common.BusinessException;
import com.smarttrip.entity.*;
import com.smarttrip.service.TravelRequirementService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;

class TravelRequirementTests {
  private final TravelRequirementService service = new TravelRequirementService();
  private final ObjectMapper json = new ObjectMapper();

  private TravelRequirement requirement(String body) throws Exception {
    return json.readValue(body, TravelRequirement.class);
  }

  private TripDetailVO draft() {
    var trip = new TripDetailVO();
    trip.setTitle("东京慢游");
    trip.setDestination("东京");
    trip.setTotalDays(1);
    trip.setTotalBudget(new BigDecimal("100"));
    var item = new TripItem();
    item.setItemName("上野公园");
    item.setItemType("SCENIC");
    item.setCostEstimate(new BigDecimal("100"));
    item.setCostTimeHours(BigDecimal.ONE);
    item.setStartTime("09:00");
    var day = new TripDayVO();
    day.setDayIndex(1);
    day.setItems(new ArrayList<>(List.of(item)));
    trip.setDays(List.of(day));
    return trip;
  }

  @Test
  void extractsChineseDaysAndBudgetWithoutInventingDateScopeOrTravelers() throws Exception {
    var review = service.review(requirement("""
        {"description":"从苏州出发去大兴安岭，秋天去看山林，大概玩五天，预算五千元，主要摄影，不想赶路"}
        """));
    var r = (TravelRequirement) review.get("requirement");
    assertEquals("苏州", r.origin());
    assertEquals(List.of("大兴安岭"), r.destinations());
    assertEquals(5, r.days());
    assertEquals(new BigDecimal("5000"), r.budget());
    assertEquals("CNY", r.currency());
    assertEquals("UNKNOWN", r.budgetScope());
    assertEquals("RELAXED", r.pace());
    assertNull(r.startDate());
    assertNull(r.travelers());
    assertEquals("秋天", r.timeDescription());
    assertTrue(((List<?>) review.get("clarifications")).size() <= 3);
    assertThrows(BusinessException.class, () -> service.confirmed(r));
    var foreign = (TravelRequirement) service.review(requirement("{\"description\":\"去东京玩五天，预算五千美元\",\"budgetScope\":\"TOTAL\"}")).get("requirement");
    assertEquals("USD", foreign.currency());
    assertThrows(BusinessException.class, () -> service.confirmed(foreign));
  }

  @Test
  void structuredCorrectionsWinAndRemainPresentAcrossReview() throws Exception {
    var source = requirement("""
        {"description":"去东京玩五天，预算五千元", "destinations":["京都"], "days":4,
        "budget":6000,"budgetScope":"TOTAL","currency":"CNY","notes":"需要午休","avoid":["登山"]}
        """);
    var review = service.review(source);
    var normalized = (TravelRequirement) review.get("requirement");
    assertEquals(List.of("京都"), normalized.destinations());
    assertEquals(4, normalized.days());
    assertEquals(new BigDecimal("6000"), normalized.budget());
    assertTrue(((List<?>) review.get("conflicts")).size() >= 3);
    var again = service.confirmed(normalized);
    assertEquals("需要午休", again.notes());
    assertEquals(List.of("登山"), again.avoid());
    assertEquals(normalized, again);
  }

  @Test
  void perPersonBudgetNeedsCountAndUsesCombinedLimit() throws Exception {
    var missingCount = requirement("""
        {"destinations":["东京"],"days":1,"budget":60,"budgetScope":"PER_PERSON","currency":"CNY"}
        """);
    assertThrows(BusinessException.class, () -> service.confirmed(missingCount));
    var r = requirement("""
        {"destinations":["东京"],"days":1,"travelers":2,"budget":60,"budgetScope":"PER_PERSON","currency":"CNY"}
        """);
    assertDoesNotThrow(() -> service.validate(r, draft()));
    var expensive = draft();
    expensive.getDays().get(0).getItems().get(0).setCostEstimate(new BigDecimal("121"));
    expensive.setTotalBudget(new BigDecimal("121"));
    assertTrue(service.inspect(r, expensive).issues().stream()
        .anyMatch(issue -> issue.code().equals("BUDGET_EXCEEDED")));
    assertThrows(BusinessException.class, () -> service.validate(r, expensive));
  }

  @Test
  void rejectsInventedDatesWrongDaysAndForbiddenPlaces() throws Exception {
    var r = requirement("""
        {"destinations":["东京"],"days":1,"avoid":["富士山"]}
        """);
    var trip = draft();
    assertFalse(service.validate(r, trip).isEmpty());
    trip.setStartDate(LocalDate.of(2030, 1, 1));
    assertThrows(BusinessException.class, () -> service.validate(r, trip));
    trip.setStartDate(null);
    trip.setTotalDays(2);
    assertThrows(BusinessException.class, () -> service.validate(r, trip));
    trip.setTotalDays(1);
    trip.getDays().get(0).getItems().get(0).setItemName("富士山");
    assertThrows(BusinessException.class, () -> service.validate(r, trip));
  }

  @Test
  void rejectsKnownOverlapAndMissingMustVisit() throws Exception {
    var r = requirement("""
        {"destinations":["东京"],"days":1,"mustVisit":["上野公园"]}
        """);
    var trip = draft();
    var second = new TripItem();
    second.setItemName("午餐");
    second.setStartTime("09:30");
    second.setCostTimeHours(BigDecimal.ONE);
    second.setCostEstimate(BigDecimal.ZERO);
    trip.getDays().get(0).getItems().add(second);
    var overlapping = service.inspect(r, trip);
    assertTrue(overlapping.issues().stream().anyMatch(issue ->
        issue.code().equals("TIME_OVERLAP") && Objects.equals(issue.dayIndex(), 1)
            && Objects.equals(issue.itemIndex(), 2) && Objects.equals(issue.relatedItemIndex(), 1)));
    assertEquals("09:30", second.getStartTime());
    assertThrows(BusinessException.class, () -> service.validate(r, trip));
    trip.getDays().get(0).getItems().remove(second);
    trip.getDays().get(0).getItems().get(0).setItemName("其他公园");
    assertThrows(BusinessException.class, () -> service.validate(r, trip));
  }

  @Test
  void relaxedPreferenceOverReferenceLimitIsWarnedWithoutWeakeningHardChecks() throws Exception {
    var r = requirement("""
        {"destinations":["东京"],"days":1,"pace":"RELAXED","exertion":"避免爬坡","timeConstraints":"下午需要休息"}
        """);
    var trip = draft();
    assertTrue(service.validate(r, trip).stream().anyMatch(s -> s.contains("人工核实")));
    trip.getDays().get(0).getItems().get(0).setCostTimeHours(new BigDecimal("9"));
    assertTrue(service.validate(r, trip).stream().anyMatch(s -> s.contains("轻松节奏风险") && s.contains("9小时")));
    trip.getDays().get(0).getItems().get(0).setCostTimeHours(new BigDecimal("25"));
    assertThrows(BusinessException.class, () -> service.validate(r, trip));
    assertTrue(service.prompt(r).contains("startDate及每一天dayDate必须为null"));
  }

  @Test
  void outdoorTransportMealsAndLodgingDoNotCountAsScenicStops() throws Exception {
    var r = requirement("""
        {"destinations":["东京"],"days":1,"pace":"RELAXED","budget":100,"budgetScope":"TOTAL","currency":"CNY"}
        """);
    var trip = draft();
    var types = List.of("SCENIC", "SCENIC", "SCENIC", "TRAFFIC", "TRAFFIC", "FOOD", "HOTEL");
    for (int n = 0; n < types.size(); n++) {
      var item = new TripItem();
      item.setItemName("安排" + n);
      item.setItemType(types.get(n));
      item.setEnvironmentType("OUTDOOR");
      item.setCostEstimate(BigDecimal.ZERO);
      item.setCostTimeHours(new BigDecimal("0.5"));
      item.setStartTime(String.format("%02d:00", 10 + n));
      trip.getDays().get(0).getItems().add(item);
    }
    assertFalse(service.validate(r, trip).stream().anyMatch(s -> s.contains("轻松节奏风险")));
    var activity = new TripItem();
    activity.setItemName("户外活动");
    activity.setItemType("OTHER");
    activity.setEnvironmentType("OUTDOOR");
    activity.setCostEstimate(BigDecimal.ZERO);
    activity.setCostTimeHours(new BigDecimal("0.5"));
    activity.setStartTime("17:00");
    trip.getDays().get(0).getItems().add(activity);
    assertTrue(service.validate(r, trip).stream().anyMatch(s -> s.contains("轻松节奏风险") && s.contains("5处")));
    activity.setCostEstimate(BigDecimal.ONE);
    trip.setTotalBudget(new BigDecimal("101"));
    assertTrue(assertThrows(BusinessException.class, () -> service.validate(r, trip)).getMessage().contains("预算上限"));
    activity.setCostEstimate(BigDecimal.ZERO);
    trip.setTotalBudget(new BigDecimal("100"));
    activity.setStartTime("09:30");
    assertTrue(assertThrows(BusinessException.class, () -> service.validate(r, trip)).getMessage().contains("重叠"));
  }
  @Test
  void requiredAndAvoidedPlacesDoNotBecomeTravelDestinations() throws Exception {
    var input = requirement("""
        {"description":"一个人从成都出发在成都轻松玩一天，不含往返交通。日期待定，总预算600元人民币，费用含餐饮和市内交通，不安排住宿。必须去人民公园，喜欢城市漫步、人文和美食。",
         "destinations":["成都"],"days":1,"budget":600,"budgetScope":"TOTAL","currency":"CNY"}
        """);
    var reviewed = service.review(input, false);
    assertFalse(((List<?>) reviewed.get("conflicts")).stream()
        .anyMatch(message -> message.toString().contains("目的地")));
    var ordinary = (TravelRequirement) service.review(requirement("""
        {"description":"不想去大阪，想去东京玩三天，必须去上野公园，不要去富士山"}
        """)).get("requirement");
    assertEquals(List.of("东京"), ordinary.destinations());
    assertEquals(3, ordinary.days());
  }
  @Test
  void explicitRenminbiSurvivesReviewConfirmationAndGenerationSnapshot() throws Exception {
    var input = requirement("""
        {"description":"从苏州出发去稻城亚丁，8天，7人，人均3000～4500元", "origin":"苏州",
         "destinations":["稻城亚丁"],"days":8,"travelers":7,"budget":3000,"budgetMax":4500,
         "budgetScope":"PER_PERSON","currency":null,"includesTravelDays":true}
        """);
    for (boolean extract : List.of(true, false)) {
      var review = service.review(input, extract);
      var normalized = (TravelRequirement) review.get("requirement");
      assertEquals("CNY", normalized.currency());
      assertEquals(new BigDecimal("3000"), normalized.budget());
      assertEquals(new BigDecimal("4500"), normalized.budgetMax());
      assertFalse(review.get("unresolved").toString().contains("币种"));
      var confirmed = service.confirmed(normalized);
      assertEquals("CNY", service.confirmed(confirmed).currency());
      assertTrue(service.prompt(confirmed).contains("\"currency\":\"CNY\""));
    }
    var values = json.convertValue(input, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
    values.put("description", "预算使用人民币，日期待定");
    for (String alias : List.of("人民币", "RMB", " cny ")) {
      values.put("currency", alias);
      var reviewed = service.review(json.convertValue(values, TravelRequirement.class), false);
      assertEquals("CNY", ((TravelRequirement) reviewed.get("requirement")).currency());
      assertTrue(((List<?>) reviewed.get("conflicts")).isEmpty());
    }
    values.put("currency", null);
    assertEquals("CNY", service.confirmed(json.convertValue(values, TravelRequirement.class)).currency());
  }

  @Test
  void unknownForeignAndDeliberatelyClearedCurrenciesAreNotAssumedToBeRenminbi() throws Exception {
    var values = json.convertValue(requirement("""
        {"description":"从苏州出发去稻城亚丁，8天，7人，人均3000～4500", "origin":"苏州",
         "destinations":["稻城亚丁"],"days":8,"travelers":7,"budget":3000,"budgetMax":4500,
         "budgetScope":"PER_PERSON","currency":null}
        """), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
    var unknown = json.convertValue(values, TravelRequirement.class);
    for (boolean extract : List.of(true, false)) {
      var review = service.review(unknown, extract);
      assertNull(((TravelRequirement) review.get("requirement")).currency());
      assertTrue(review.get("unresolved").toString().contains("币种"));
    }
    assertThrows(BusinessException.class, () -> service.confirmed(unknown));
    for (String currency : List.of("日元", "美元", "新加坡元")) {
      values.put("description", "人均预算3000～4500" + currency);
      var foreign = (TravelRequirement) service.review(json.convertValue(values, TravelRequirement.class), false).get("requirement");
      assertNotEquals("CNY", foreign.currency());
      assertThrows(BusinessException.class, () -> service.confirmed(foreign));
    }
    values.put("description", "预算人民币3000元或美元500元，尚未决定");
    assertNull(((TravelRequirement) service.review(json.convertValue(values, TravelRequirement.class), false).get("requirement")).currency());
    values.put("description", "人均预算3000～4500元");
    var cleared = json.convertValue(values, TravelRequirement.class);
    assertNull(((TravelRequirement) service.interpreted(cleared, cleared, List.of("currency")).get("requirement")).currency());
    for (boolean extract : List.of(true, false))
      assertNull(((TravelRequirement) service.review(cleared, extract, List.of("currency")).get("requirement")).currency());
  }

  @Test
  void promptRequiresEveryRequestedDayAndOnlyMultipliesPerPersonBudgetByTravelers() throws Exception {
    for (var scenario : List.of(new int[] {8, 7}, new int[] {10, 6})) {
      int days = scenario[0], travelers = scenario[1];
      var r = requirement("""
          {"origin":"苏州","destinations":["稻城亚丁"],"days":%d,"travelers":%d,
          "budget":3000,"budgetMax":4500,"budgetScope":"PER_PERSON","currency":"CNY",
          "startDate":"2026-10-28"}
          """.formatted(days, travelers));
      String prompt = service.prompt(r);
      assertTrue(prompt.contains("必须完整输出" + days + "个逐日对象"));
      assertTrue(prompt.contains("每天items必须是非空数组"));
      assertTrue(prompt.contains("只乘人数，不再乘天数"));
      assertTrue(prompt.contains(travelers + "人合计总上限" + (4500 * travelers) + " CNY"));
      assertTrue(prompt.contains("上限不是预计支出"));
      assertEquals(days, r.days());
      assertEquals(new BigDecimal("4500"), r.budgetMax());
    }
  }
  @Test
  void clearingStructuredConditionsDoesNotResurrectEarlierDescription() throws Exception {
    var r = requirement("""
        {"description":"去东京五天，必须去上野公园，不去登山，预算五千元", "destinations":["京都"], "days":2,
          "mustVisit":[],"avoid":[],"budgetScope":"UNKNOWN","currency":"CNY"}
        """);
    var accepted = service.confirmed(r);
    assertEquals(2, accepted.days());
    assertNull(accepted.budget());
    assertTrue(accepted.mustVisit().isEmpty());
    assertTrue(accepted.avoid().isEmpty());
    var reviewed = (TravelRequirement) service.review(r).get("requirement");
    assertTrue(reviewed.mustVisit().isEmpty());
    assertTrue(reviewed.avoid().isEmpty());
  }

  @Test
  void promptKeepsStructuredKeysButRequiresNaturalChineseForReaderFacingCopy() throws Exception {
    String prompt = service.prompt(requirement("""
        {"destinations":["京都"],"days":1,"budget":500,"budgetScope":"TOTAL","currency":"CNY"}
        """));
    assertTrue(prompt.contains("summary、daySummary、aiTips、transportTips"));
    assertTrue(prompt.contains("简明自然中文"));
    assertTrue(prompt.contains("不得出现DTO字段名、内部枚举"));
    assertTrue(prompt.contains("结构化字段名称及枚举值仍严格按上述契约"));
    assertTrue(prompt.contains("costEstimate(number或null)"));
  }
}

package com.smarttrip.service.quality;

import static org.junit.jupiter.api.Assertions.*;

import com.smarttrip.common.BusinessException;
import com.smarttrip.entity.*;
import com.smarttrip.service.ItineraryAnalysisService.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;

class QualityEngineTests {
  final QualityPolicy p = QualityPolicy.defaults();
  final ItineraryQualityEngine engine = new ItineraryQualityEngine();

  TripItem item(long id, String type, String time, String hours) {
    var i = new TripItem();
    i.setId(id);
    i.setItemName("节点" + id);
    i.setItemType(type);
    i.setStartTime(time);
    i.setCostTimeHours(hours == null ? null : new BigDecimal(hours));
    i.setCostEstimate(BigDecimal.ZERO);
    return i;
  }

  TripDayVO day(long id, TripItem... items) {
    var d = new TripDayVO();
    d.setId(id);
    d.setDayIndex((int) id);
    d.setItems(new ArrayList<>(List.of(items)));
    return d;
  }

  TripDetailVO trip(TripDayVO... days) {
    var t = new TripDetailVO();
    t.setId(10L);
    t.setVersion(2L);
    t.setTitle("质量验收");
    t.setDestination("成都");
    t.setDays(new ArrayList<>(List.of(days)));
    return t;
  }

  QualityReport report(TripDayVO... days) {
    return engine.evaluate(trip(days), List.of(), p);
  }

  boolean finding(QualityReport r, String code) {
    return r.findings().stream().anyMatch(f -> f.ruleId().equals(code));
  }

  QualityReport.Dimension dim(QualityReport r, String key) {
    return r.dimensions().stream().filter(d -> key.equals(d.key())).findFirst().orElseThrow();
  }

  @Test
  void touchingActivitiesAreNotOverlappingAndSingleDayBalanceIsNotApplicable() {
    var r = report(day(1, item(1, "SCENIC", "09:00", "1"), item(2, "SCENIC", "10:00", "1")));
    assertEquals(100, dim(r, "TIME").score());
    assertEquals("NOT_APPLICABLE", dim(r, "BALANCE").status());
    assertEquals(100, r.totalScore());
    assertEquals(120, r.days().get(0).metrics().occupiedActivityMinutes());
  }

  @Test
  void unknownTimesDoNotBecomeHighScores() {
    var r = report(day(1, item(1, "SCENIC", null, "2")));
    assertNull(r.totalScore());
    assertNull(dim(r, "TIME").score());
    assertEquals("UNKNOWN", r.days().get(0).loadLevel());
    assertEquals(List.of(1L), r.days().get(0).coverage().missingItemIds());
  }

  @Test
  void zeroDurationIsIncomplete() {
    var r = report(day(1, item(1, "FOOD", "12:00", "0")));
    assertNull(r.totalScore());
    assertEquals(0, r.days().get(0).coverage().complete());
  }

  @Test
  void overnightIsUnknownNotAutoSplit() {
    var r = report(day(1, item(1, "TRAFFIC", "23:00", "2")));
    assertNull(r.totalScore());
    assertEquals(List.of(1L), r.days().get(0).coverage().overnightItemIds());
  }

  @Test
  void emptyTripAndEmptyDayCannotScoreOneHundred() {
    assertNull(report().totalScore());
    assertNull(report(day(1)).totalScore());
  }

  @Test
  void hotelAloneDoesNotProveRest() {
    var r = report(day(1, item(1, "HOTEL", "00:00", "24")));
    assertNull(r.totalScore());
    assertNull(r.days().get(0).metrics().daySpanMinutes());
  }

  @Test
  void intervalUnionDoesNotDoubleCountOverlaps() {
    var r = report(day(1, item(1, "SCENIC", "09:00", "2"), item(2, "SCENIC", "10:00", "2")));
    assertEquals(180, r.days().get(0).metrics().occupiedActivityMinutes());
    assertEquals(80, dim(r, "TIME").score());
    assertNull(dim(r, "PACE").score());
    assertNull(r.totalScore());
  }

  @Test
  void wrongOriginalOrderStillShowsConflict() {
    var r = report(day(1, item(1, "SCENIC", "12:00", "1"), item(2, "SCENIC", "09:00", "1")));
    assertEquals(90, dim(r, "TIME").score());
    assertEquals("UNKNOWN", r.days().get(0).loadLevel());
  }

  @Test
  void decimalHoursUseSixMinuteSavedPrecision() {
    var r = report(day(1, item(1, "SCENIC", "09:00", "0.1")));
    assertEquals(6, r.days().get(0).metrics().occupiedActivityMinutes());
  }

  @Test
  void exactContinuousThresholdDoesNotDeduct() {
    var r = report(day(1, item(1, "SCENIC", "09:00", "3")));
    assertFalse(finding(r, "QUALITY_CONTINUOUS_ACTIVITY"));
  }

  @Test
  void continuousHighTakesOnlyHighestTier() {
    var r = report(day(1, item(1, "SCENIC", "09:00", "5.1")));
    var f =
        r.findings().stream()
            .filter(v -> v.ruleId().equals("QUALITY_CONTINUOUS_ACTIVITY"))
            .toList();
    assertEquals(1, f.size());
    assertEquals(20, f.get(0).penalty());
  }

  @Test
  void thirtyMinuteGapBreaksContinuousActivity() {
    var r = report(day(1, item(1, "SCENIC", "09:00", "2"), item(2, "SCENIC", "11:30", "2")));
    assertEquals(120, r.days().get(0).metrics().longestContinuousMinutes());
    assertFalse(finding(r, "QUALITY_CONTINUOUS_ACTIVITY"));
  }

  @Test
  void validFoodBreakAndLunchDoNotTriggerMissingMeal() {
    var r =
        report(
            day(
                1,
                item(1, "SCENIC", "09:00", "3"),
                item(2, "FOOD", "12:00", "0.5"),
                item(3, "SCENIC", "12:30", "2")));
    assertEquals(180, r.days().get(0).metrics().longestContinuousMinutes());
    assertFalse(finding(r, "QUALITY_LUNCH_WINDOW"));
  }

  @Test
  void overlappingFoodCannotBreakBusyActivity() {
    var r = report(day(1, item(1, "SCENIC", "09:00", "6"), item(2, "FOOD", "12:00", "0.5")));
    assertEquals(360, r.days().get(0).metrics().longestContinuousMinutes());
    assertNull(dim(r, "MEAL_REST").score());
  }

  @Test
  void otherNamedRestIsStillScheduledActivity() {
    var i = item(1, "OTHER", "09:00", "4");
    i.setItemName("休息");
    var r = report(day(1, i));
    assertTrue(finding(r, "QUALITY_CONTINUOUS_ACTIVITY"));
  }

  @Test
  void unknownFoodDoesNotProveLunchOrAbsenceOfLunch() {
    var r = report(day(1, item(1, "SCENIC", "10:00", "5"), item(2, "FOOD", null, "1")));
    assertFalse(finding(r, "QUALITY_LUNCH_WINDOW"));
    assertNull(dim(r, "MEAL_REST").score());
  }

  @Test
  void occupiedLunchWindowProducesHeuristicManualSuggestion() {
    var r = report(day(1, item(1, "SCENIC", "10:00", "5")));
    var f =
        r.findings().stream()
            .filter(v -> v.ruleId().equals("QUALITY_LUNCH_WINDOW"))
            .findFirst()
            .orElseThrow();
    assertEquals("MANUAL", f.action());
    assertEquals(10, f.penalty());
    assertTrue(f.message().contains("建议"));
  }

  @Test
  void morningOnlyAndThirtyMinutesUnallocatedDoNotClaimMissingLunch() {
    assertFalse(finding(report(day(1, item(1, "SCENIC", "08:00", "2"))), "QUALITY_LUNCH_WINDOW"));
    assertFalse(
        finding(
            report(day(1, item(1, "SCENIC", "11:30", "1"), item(2, "SCENIC", "13:00", "1"))),
            "QUALITY_LUNCH_WINDOW"));
  }

  @Test
  void sevenShortScenicActivitiesAreNotOverloadedByCount() {
    var items = new TripItem[7];
    for (int n = 0; n < 7; n++)
      items[n] = item(n + 1, "SCENIC", String.format("%02d:00", 9 + n), "0.2");
    var r = report(day(1, items));
    assertFalse(finding(r, "QUALITY_DENSE_DAY"));
    assertNotEquals("OVERLOADED", r.days().get(0).loadLevel());
  }

  @Test
  void unevenCompleteDaysAreEvaluatedButEmptyDaysNotTreatedAsZero() {
    var r =
        report(day(1, item(1, "SCENIC", "08:00", "11")), day(2, item(2, "SCENIC", "09:00", "3")));
    assertEquals(65, dim(r, "BALANCE").score());
    assertTrue(finding(r, "QUALITY_UNEVEN_DAYS"));
    var unknown = report(day(1, item(1, "SCENIC", "09:00", "5")), day(2));
    assertNull(dim(unknown, "BALANCE").score());
    assertFalse(finding(unknown, "QUALITY_UNEVEN_DAYS"));
  }

  @Test
  void weightedTotalAndEveryDeductionAreDeterministic() {
    var t = trip(day(1, item(1, "SCENIC", "09:00", "9")), day(2, item(2, "SCENIC", "09:00", "9")));
    var a = engine.evaluate(t, List.of(), p);
    var b = engine.evaluate(t, List.of(), p);
    assertEquals(a, b);
    assertEquals(85, dim(a, "PACE").score());
    assertEquals(70, dim(a, "MEAL_REST").score());
    assertEquals(90, a.totalScore());
  }

  Source route(long day, Leg... legs) {
    return new Source(
        "ROUTE:" + day, day, "ROUTE", "READY", "已测", null, null, List.of(legs), false);
  }

  @Test
  void routeUnknownDoesNotPretendZeroAndTrafficNotDoubleCounted() {
    var t = trip(day(1, item(1, "SCENIC", "09:00", "1"), item(2, "SCENIC", "10:30", "1")));
    var r = engine.evaluate(t, List.of(), p);
    assertNull(r.days().get(0).metrics().gaps().get(0).routeMinutes());
    assertEquals("UNKNOWN", r.external().route().status());
    t.getDays().get(0).getItems().get(1).setItemType("TRAFFIC");
    r = engine.evaluate(t, List.of(route(1, new Leg(1L, 2L, 2400))), p);
    assertEquals(60, r.days().get(0).metrics().scheduledTrafficMinutes());
    assertNull(r.days().get(0).metrics().gaps().get(0).routeMinutes());
    assertEquals("NOT_APPLICABLE", r.external().route().status());
  }

  @Test
  void changedAdjacencyCannotReuseOldRouteLeg() {
    var t =
        trip(
            day(
                1,
                item(1, "SCENIC", "09:00", "1"),
                item(3, "SCENIC", "11:00", "1"),
                item(2, "SCENIC", "13:00", "1")));
    var r = engine.evaluate(t, List.of(route(1, new Leg(1L, 2L, 900))), p);
    assertEquals(0, r.external().route().available());
    assertEquals(2, r.external().route().expected());
  }

  @Test
  void partialDayWeatherCannotMakeWholeTripReady() {
    var t = trip(day(1, item(1, "SCENIC", "09:00", "1")), day(2, item(2, "SCENIC", "09:00", "1")));
    var r =
        engine.evaluate(
            t,
            List.of(
                new Source("WEATHER:1", 1L, "WEATHER", "READY", "日雨", null, null, List.of(), true)),
            p);
    assertEquals("PARTIAL", r.external().weather().status());
    assertEquals(1, r.external().weather().available());
    assertEquals(100, r.totalScore());
  }

  @Test
  void measuredBufferIsSeparateFromCoreScore() {
    var t = trip(day(1, item(1, "SCENIC", "09:00", "1"), item(2, "SCENIC", "10:30", "1")));
    var r = engine.evaluate(t, List.of(route(1, new Leg(1L, 2L, 1500))), p);
    assertTrue(finding(r, "QUALITY_TRANSFER_BUFFER"));
    assertEquals(5, r.days().get(0).metrics().gaps().get(0).remainingMinutes());
    assertEquals(100, r.totalScore());
  }

  @Test
  void qualityComparisonAcceptsRestImprovementAndRejectsNeutralChanges() {
    var a = trip(day(1, item(1, "SCENIC", "09:00", "2"), item(2, "SCENIC", "11:00", "2")));
    var b = trip(day(1, item(1, "SCENIC", "09:00", "2"), item(2, "SCENIC", "11:30", "2")));
    var c = QualityComparison.compute(a, b, List.of(), p);
    assertDoesNotThrow(() -> c.validate(a, b, List.of(), true));
    assertFalse(c.resolved().isEmpty());
    var neutral = trip(day(1, item(1, "SCENIC", "08:00", "2"), item(2, "SCENIC", "10:00", "2")));
    assertThrows(
        BusinessException.class,
        () ->
            QualityComparison.compute(a, neutral, List.of(), p)
                .validate(a, neutral, List.of(), true));
  }

  @Test
  void fillingUnknownTimeDoesNotInventBeforeScore() {
    var a = trip(day(1, item(1, "SCENIC", null, "1")));
    var b = trip(day(1, item(1, "SCENIC", "09:00", "1")));
    var c = QualityComparison.compute(a, b, List.of(), p);
    assertNull(c.before().totalScore());
    assertEquals(100, c.after().totalScore());
    assertDoesNotThrow(() -> c.validate(a, b, List.of(), true));
  }

  @Test
  void worseDimensionCannotBeCompensatedByOverallScore() {
    var a = trip(day(1, item(1, "SCENIC", "08:00", "3")));
    var b = trip(day(1, item(1, "SCENIC", "11:00", "3")));
    assertThrows(
        BusinessException.class,
        () -> QualityComparison.compute(a, b, List.of(), p).validate(a, b, List.of(), true));
  }

  @Test
  void incompleteUnselectedDayDoesNotHideKnownLocalImprovement() {
    var a =
        trip(
            day(1, item(1, "SCENIC", "09:00", "2"), item(2, "SCENIC", "11:00", "2")),
            day(2, item(3, "SCENIC", null, "1")));
    var b =
        trip(
            day(1, item(1, "SCENIC", "09:00", "2"), item(2, "SCENIC", "11:30", "2")),
            day(2, item(3, "SCENIC", null, "1")));
    var comparison = QualityComparison.compute(a, b, List.of(), p);
    assertNull(comparison.before().totalScore());
    assertNull(comparison.after().totalScore());
    assertDoesNotThrow(() -> comparison.validate(a, b, List.of(), true));
  }

  @Test
  void policySnapshotControlsThresholdsAndWeights() throws Exception {
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    var node = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.valueToTree(p);
    node.put("rulesVersion", "quality-test");
    node.put("activityWarning", 120);
    node.put("activityHigh", 180);
    var alternate = mapper.treeToValue(node, QualityPolicy.class);
    var t = trip(day(1, item(1, "SCENIC", "09:00", "3")));
    assertEquals(100, dim(engine.evaluate(t, List.of(), p), "PACE").score());
    var result = engine.evaluate(t, List.of(), alternate);
    assertEquals("quality-test", result.rulesVersion());
    assertEquals(85, dim(result, "PACE").score());
    assertThrows(
        UnsupportedOperationException.class, () -> alternate.parameters().put("weight.TIME", 1));
  }
}

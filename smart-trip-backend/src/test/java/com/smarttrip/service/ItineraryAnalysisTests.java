package com.smarttrip.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.smarttrip.common.BusinessException;
import com.smarttrip.entity.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;

class ItineraryAnalysisTests {
  TripDetailVO base;
  WorkbenchService trips;
  ResourceAccess access;
  GeoService geo;
  ItineraryAnalysisService service;
  MutableClock clock;

  static class MutableClock extends Clock {
    Instant value = Instant.parse("2026-10-04T10:00:00Z");

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId z) {
      return this;
    }

    public Instant instant() {
      return value;
    }
  }

  @BeforeEach
  void setup() {
    access = mock(ResourceAccess.class);
    geo = mock(GeoService.class);
    clock = new MutableClock();
    trips = spy(new WorkbenchService(null, null, access));
    base =
        trips.decode(
            "{\"id\":10,\"version\":2,\"title\":\"成都旅行\",\"destination\":\"成都\",\"startDate\":\"2026-10-04\",\"days\":[{\"id\":11,\"dayIndex\":1,\"dayDate\":\"2026-10-04\",\"items\":[{\"id\":101,\"itemName\":\"公园\",\"itemType\":\"SCENIC\",\"startTime\":\"09:00\",\"costTimeHours\":2,\"costEstimate\":50,\"environmentType\":\"OUTDOOR\",\"poiId\":\"B00001\",\"aiTips\":\"保留备注\"},{\"id\":102,\"itemName\":\"博物馆\",\"itemType\":\"SCENIC\",\"startTime\":\"10:00\",\"costTimeHours\":1,\"costEstimate\":0,\"environmentType\":\"INDOOR\"}]},{\"id\":12,\"dayIndex\":2,\"dayDate\":\"2026-10-05\",\"items\":[{\"id\":103,\"itemName\":\"车站\",\"itemType\":\"TRAFFIC\",\"startTime\":\"09:00\",\"costTimeHours\":1,\"costEstimate\":120,\"scheduleLocked\":true}]}]}");
    doReturn(base).when(trips).load(10L);
    when(access.read(10L)).thenReturn(base);
    when(access.userId()).thenReturn(1L);
    service = new ItineraryAnalysisService(trips, access, geo, clock);
  }

  ItineraryAnalysisService.Report report(Long... days) {
    return service.create(10L, 2L, List.of(days), "walking");
  }

  ItineraryAnalysisService.Prepared prepared(Long... days) {
    var r = report(days);
    return service.prepare(10L, r.id());
  }

  String adjust(String rows, String reasons) {
    return "{\"days\":" + rows + ",\"reasons\":" + reasons + "}";
  }

  String day1(String time) {
    return "[{\"dayId\":11,\"items\":[{\"itemId\":101},{\"itemId\":102,\"startTime\":\""
        + time
        + "\"}]}]";
  }

  String reason(ItineraryAnalysisService.Prepared p, Long... ids) {
    return "[{\"itemIds\":"
        + Arrays.toString(ids)
        + ",\"evidenceIds\":[\""
        + p.issues().get(0).id()
        + "\"],\"text\":\"错开两个安排\"}]";
  }

  @Test
  void offlineChecksDoNotCallGeoAndRespectScope() {
    var r = report(11L);
    assertTrue(r.issues().stream().anyMatch(i -> i.code().equals("TIME_OVERLAP")));
    verifyNoInteractions(geo);
    assertFalse(r.issues().stream().anyMatch(i -> i.dayId() == 12L));
  }

  @Test
  void invalidScopeModeAndVersionAreRejected() {
    assertEquals(
        409,
        assertThrows(
                BusinessException.class, () -> service.create(10L, 1L, List.of(11L), "walking"))
            .getCode());
    assertThrows(BusinessException.class, () -> report(99L));
    assertThrows(BusinessException.class, () -> report(11L, 11L));
    assertThrows(BusinessException.class, () -> service.create(10L, 2L, List.of(11L), "transit"));
  }

  @Test
  void disabledSourcesMakeZeroProviderCalls() {
    var r = report(11L);
    var w = service.source(10L, r.id(), 11L, "WEATHER", false);
    assertEquals("UNAVAILABLE", w.sources().get(0).status());
    verify(geo).available();
    verifyNoMoreInteractions(geo);
  }

  @Test
  void aNewSessionExpiresPriorSessionAndWrongUserCannotRead() {
    var r = report(11L);
    when(access.userId()).thenReturn(2L);
    assertThrows(BusinessException.class, () -> service.get(10L, r.id()));
    when(access.userId()).thenReturn(1L);
    report(12L);
    assertThrows(BusinessException.class, () -> service.get(10L, r.id()));
  }

  @Test
  void controllableExpiryAndVersionInvalidation() {
    var first = report(11L);
    clock.value = clock.value.plusSeconds(1800);
    assertEquals(
        410, assertThrows(BusinessException.class, () -> service.get(10L, first.id())).getCode());
    clock.value = clock.value.minusSeconds(1800);
    var r = report(11L);
    base.setVersion(3L);
    String id = r.id();
    assertEquals(409, assertThrows(BusinessException.class, () -> service.get(10L, id)).getCode());
  }

  @Test
  void weatherIsDailyOnlyAndUnknownEnvironmentRemainsUnknown() {
    when(geo.available()).thenReturn(true);
    when(geo.weather("成都", LocalDate.parse("2026-10-04")))
        .thenReturn(
            Map.of(
                "available",
                true,
                "dayWeather",
                "暴雨",
                "nightWeather",
                "多云",
                "high",
                "20",
                "low",
                "15",
                "retrievedAt",
                clock.instant().toString(),
                "issuedAt",
                "2026-10-04 12:00"));
    base.getDays().get(0).getItems().get(1).setEnvironmentType("UNKNOWN");
    var r = report(11L);
    var w = service.source(10L, r.id(), 11L, "WEATHER", false);
    assertTrue(
        w.issues().stream()
            .anyMatch(i -> i.code().equals("WEATHER_OUTDOOR") && i.message().contains("无法判断具体")));
    assertTrue(w.issues().stream().anyMatch(i -> i.code().equals("UNKNOWN_ENVIRONMENT")));
    assertFalse(w.sources().get(0).summary().contains("14:00"));
    service.source(10L, r.id(), 11L, "WEATHER", false);
    verify(geo, times(1)).weather(anyString(), any());
    service.source(10L, r.id(), 11L, "WEATHER", true);
    verify(geo, times(2)).weather(anyString(), any());
  }

  @Test
  void routeSummaryStripsGeometryAndComputesTransferWarning() {
    base.getDays().get(0).getItems().get(1).setStartTime("11:05");
    when(geo.available()).thenReturn(true);
    when(geo.route(10L, 11L, "walking"))
        .thenReturn(
            Map.of(
                "tripVersion",
                2L,
                "retrievedAt",
                clock.instant().toString(),
                "points",
                List.of(Map.of("location", "104,30")),
                "legs",
                List.of(
                    Map.of(
                        "from",
                        101L,
                        "to",
                        102L,
                        "available",
                        true,
                        "durationSeconds",
                        "1200",
                        "polylines",
                        List.of("104,30;105,31")))));
    var r = report(11L);
    r = service.source(10L, r.id(), 11L, "ROUTE", false);
    assertTrue(r.issues().stream().anyMatch(i -> i.code().equals("TRANSFER_TIME")));
    String encoded = trips.encode(r);
    assertFalse(encoded.contains("polylines"));
    assertFalse(encoded.contains("104,30"));
    var p = service.prepare(10L, r.id());
    assertFalse(service.persistedInput(p).contains("durationSeconds"));
  }

  @Test
  void incompleteOrInvalidRouteMetricsNeverBecomeComplete() {
    when(geo.available()).thenReturn(true);
    when(geo.route(10L, 11L, "walking"))
        .thenReturn(
            Map.of(
                "tripVersion",
                2L,
                "retrievedAt",
                clock.instant().toString(),
                "legs",
                List.of(
                    Map.of(
                        "from", 101L, "to", 102L, "available", true, "durationSeconds", "NaN"))));
    var r = report(11L);
    r = service.source(10L, r.id(), 11L, "ROUTE", false);
    assertEquals("PARTIAL", r.sources().get(0).status());
    assertTrue(r.sources().get(0).legs().isEmpty());
  }

  @Test
  void failedWeatherDoesNotDiscardRouteOrLocalProblems() {
    when(geo.available()).thenReturn(true);
    when(geo.weather(anyString(), any())).thenThrow(new BusinessException(504, "超时"));
    var r = report(11L);
    r = service.source(10L, r.id(), 11L, "WEATHER", false);
    assertEquals("FAILED", r.sources().get(0).status());
    assertTrue(r.issues().stream().anyMatch(i -> i.code().equals("TIME_OVERLAP")));
  }

  @Test
  void previewChangesTimeAndPreservesIdentityMoneyPoiAndNotes() {
    var p = prepared(11L);
    var built = service.build(p, adjust(day1("11:00"), reason(p, 102L)));
    var item = built.trip().getDays().get(0).getItems().get(0);
    assertEquals("B00001", item.getPoiId());
    assertEquals(new BigDecimal("50.00"), item.getCostEstimate());
    assertEquals("保留备注", item.getAiTips());
    assertEquals("10:00", base.getDays().get(0).getItems().get(1).getStartTime());
    assertEquals("11:00", built.trip().getDays().get(0).getItems().get(1).getStartTime());
    assertEquals(1, built.proposal().changes().size());
    assertNull(built.proposal().expiresAt());
    verify(trips, never()).save(any(), any());
  }

  @Test
  void crossDayMovesPreserveEveryIdAndFlagNewRoutes() {
    var p = prepared(11L, 12L);
    String rows =
        "[{\"dayId\":11,\"items\":[{\"itemId\":101}]},{\"dayId\":12,\"items\":[{\"itemId\":103},{\"itemId\":102,\"startTime\":\"11:00\"}]}]";
    var b = service.build(p, adjust(rows, reason(p, 102L)));
    assertEquals(
        List.of(103L, 102L),
        b.trip().getDays().get(1).getItems().stream().map(TripItem::getId).toList());
    assertEquals(Set.of(11L, 12L), new HashSet<>(b.proposal().unverifiedRouteDayIds()));
  }

  @Test
  void rejectsDeletedDuplicateForeignNodesAndScopeChanges() {
    var p = prepared(11L);
    for (String rows :
        List.of(
            "[{\"dayId\":11,\"items\":[{\"itemId\":101}]}]",
            "[{\"dayId\":11,\"items\":[{\"itemId\":101},{\"itemId\":101}]}]",
            "[{\"dayId\":11,\"items\":[{\"itemId\":101},{\"itemId\":999}]}]",
            "[{\"dayId\":12,\"items\":[]}]"))
      assertThrows(BusinessException.class, () -> service.build(p, adjust(rows, "[]")));
  }

  @Test
  void fixedNodesCannotMoveOrChangeTime() {
    var p = prepared(12L);
    assertThrows(
        BusinessException.class,
        () ->
            service.build(
                p,
                adjust(
                    "[{\"dayId\":12,\"items\":[{\"itemId\":103,\"startTime\":\"10:00\"}]}]",
                    "[]")));
  }

  @Test
  void rejectsUnknownFieldsUnsupportedReasonsClearingTimesAndMalformedJson() {
    var p = prepared(11L);
    for (String output :
        List.of(
            "{} trailing",
            adjust(
                day1("11:00").replace("\"itemId\":101", "\"itemId\":101,\"costEstimate\":0"), "[]"),
            adjust(day1(""), "[]"),
            adjust(
                day1("11:00"),
                "[{\"itemIds\":[102],\"evidenceIds\":[\"invented\"],\"text\":\"编造依据\"}]"),
            adjust(day1("11:00"), "[]")))
      assertThrows(BusinessException.class, () -> service.build(p, output));
  }

  @Test
  void newTimeConflictsAreRejected() {
    base.getDays().get(0).getItems().get(1).setStartTime("11:00");
    var p = prepared(11L);
    assertThrows(BusinessException.class, () -> service.build(p, adjust(day1("09:30"), "[]")));
  }

  @Test
  void missingTimesAndOvernightAreExplicitAndNoChangeIsValid() {
    base.getDays().get(0).getItems().get(0).setStartTime("23:00");
    base.getDays().get(0).getItems().get(1).setStartTime(null);
    var p = prepared(11L);
    assertTrue(p.issues().stream().anyMatch(i -> i.code().equals("OVERNIGHT")));
    assertTrue(p.issues().stream().anyMatch(i -> i.code().equals("MISSING_TIME")));
    var b =
        service.build(
            p, adjust("[{\"dayId\":11,\"items\":[{\"itemId\":101},{\"itemId\":102}]}]", "[]"));
    assertTrue(b.proposal().changes().isEmpty());
  }

  @Test
  void aLateProviderResponseCannotAttachAfterVersionChange() {
    when(geo.available()).thenReturn(true);
    when(geo.route(10L, 11L, "walking"))
        .thenAnswer(
            invocation -> {
              base.setVersion(3L);
              return Map.of("tripVersion", 2L, "legs", List.of());
            });
    var r = report(11L);
    assertEquals(
        409,
        assertThrows(
                BusinessException.class, () -> service.source(10L, r.id(), 11L, "ROUTE", false))
            .getCode());
  }

  @Test
  void fixedNodeCannotCrossToAnotherSelectedDay() {
    var p = prepared(11L, 12L);
    assertThrows(
        BusinessException.class,
        () ->
            service.build(
                p,
                adjust(
                    "[{\"dayId\":11,\"items\":[{\"itemId\":101},{\"itemId\":102},{\"itemId\":103}]},{\"dayId\":12,\"items\":[]}]",
                    "[]")));
  }
}

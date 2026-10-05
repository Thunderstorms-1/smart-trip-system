package com.smarttrip.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.smarttrip.common.*;
import com.smarttrip.config.GeoProperties;
import com.smarttrip.entity.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;
import okhttp3.*;
import org.junit.jupiter.api.Test;

class GeoRound25Tests {
  GeoProperties config() {
    var c = new GeoProperties();
    c.setEnabled(true);
    c.setPoiEnabled(true);
    c.setRouteEnabled(true);
    c.setWeatherEnabled(true);
    c.setMapEnabled(true);
    c.setFreeQuotaVerified(true);
    c.setAmapKey("local-fixture");
    return c;
  }

  TripItem item(long id, String poi) {
    var i = new TripItem();
    i.setId(id);
    i.setItemName("节点" + id);
    i.setPoiId(poi);
    return i;
  }

  WorkbenchService store(TripItem... items) {
    var t = new TripDetailVO();
    t.setId(1L);
    t.setVersion(7L);
    var d = new TripDayVO();
    d.setId(2L);
    d.setItems(List.of(items));
    t.setDays(List.of(d));
    var s = mock(WorkbenchService.class);
    when(s.load(1L)).thenReturn(t);
    return s;
  }

  String detail(String loc) {
    return "{\"status\":\"1\",\"pois\":[{\"id\":\"B00001\",\"name\":\"地点\",\"location\":\""
        + loc
        + "\"}]}";
  }

  String direction =
      "{\"status\":\"1\",\"route\":{\"paths\":[{\"distance\":\"100\",\"cost\":{\"duration\":\"30\"},\"steps\":[{\"polyline\":\"104,30;105,31\"}]}]}}";

  Response response(Interceptor.Chain c, String body) {
    return new Response.Builder()
        .request(c.request())
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("fixture")
        .body(ResponseBody.create(body, MediaType.get("application/json")))
        .build();
  }

  List<Map<String, Object>> points(Map<String, Object> r) {
    return (List<Map<String, Object>>) r.get("points");
  }

  @Test
  void repeatedPoiIsResolvedOncePerRequestWithoutSharingNodeIdentity() {
    var calls = new AtomicInteger();
    var client =
        new OkHttpClient.Builder()
            .addInterceptor(
                c -> {
                  calls.incrementAndGet();
                  return response(
                      c,
                      c.request().url().encodedPath().contains("detail")
                          ? detail("104.00,30.00")
                          : direction);
                })
            .build();
    var g = new GeoService(config(), store(item(3, "B00001"), item(4, "B00001")), client);
    var p = points(g.route(1L, 2L, "walking"));
    assertEquals(2, calls.get());
    assertEquals(3L, p.get(0).get("itemId"));
    assertEquals(4L, p.get(1).get("itemId"));
    assertEquals("节点4", p.get(1).get("name"));
    assertEquals("104,30", p.get(1).get("location"));
    g.route(1L, 2L, "walking");
    assertEquals(4, calls.get());
  }

  @Test
  void missingPoiIsAlsoMemoizedAndNeverFallsBackToStoredCoordinates() {
    var count = new AtomicInteger();
    var a = item(3, "B00001");
    a.setLongitude(new BigDecimal("104"));
    a.setLatitude(new BigDecimal("30"));
    a.setCoordinateSystem("GCJ02");
    var c =
        new OkHttpClient.Builder()
            .addInterceptor(
                chain -> {
                  count.incrementAndGet();
                  return response(chain, "{\"status\":\"1\",\"pois\":[]}");
                })
            .build();
    var p =
        points(new GeoService(config(), store(a, item(4, "B00001")), c).route(1L, 2L, "walking"));
    assertEquals(1, count.get());
    assertEquals(false, p.get(0).get("resolved"));
    assertEquals(false, p.get(1).get("resolved"));
  }

  @Test
  void poiReferenceRemainsAuthoritativeOverUnlinkedCoordinates() {
    var a = item(3, "B00001");
    a.setLongitude(new BigDecimal("100"));
    a.setLatitude(new BigDecimal("20"));
    a.setCoordinateSystem("GCJ02");
    var c =
        new OkHttpClient.Builder()
            .addInterceptor(chain -> response(chain, detail("105,31")))
            .build();
    var p = points(new GeoService(config(), store(a), c).route(1L, 2L, "walking"));
    assertEquals("105,31", p.get(0).get("location"));
  }

  @Test
  void invalidVendorCoordinatesAreExcludedFromSearchAndDetail() {
    String[] invalid = {
      "1..2,3", "181,0", "0,91", "NaN,0", "1,2,3", "", "Infinity,1", "1e-2147483647,0", ",1", "1,"
    };
    for (String loc : invalid) {
      var c =
          new OkHttpClient.Builder().addInterceptor(chain -> response(chain, detail(loc))).build();
      var g = new GeoService(config(), store(item(3, "B00001")), c);
      assertTrue(g.search("成都", "地点").isEmpty(), loc);
      assertEquals(false, points(g.route(1L, 2L, "walking")).get(0).get("resolved"), loc);
    }
    var valid =
        new OkHttpClient.Builder().addInterceptor(c -> response(c, detail("-180,90"))).build();
    assertEquals(
        "-180,90",
        new GeoService(config(), store(), valid).search("成都", "边界").get(0).get("location"));
  }

  @Test
  void storedWgs84OrInvalidGcj02NeverBecomesAConfirmedPoint() {
    var a = item(3, null);
    a.setLongitude(new BigDecimal("104"));
    a.setLatitude(new BigDecimal("30"));
    a.setCoordinateSystem("WGS84");
    var b = item(4, null);
    b.setLongitude(new BigDecimal("181"));
    b.setLatitude(new BigDecimal("30"));
    b.setCoordinateSystem("GCJ02");
    var calls = new AtomicInteger();
    var c =
        new OkHttpClient.Builder()
            .addInterceptor(
                chain -> {
                  calls.incrementAndGet();
                  return response(chain, "{}");
                })
            .build();
    var p = points(new GeoService(config(), store(a, b), c).route(1L, 2L, "walking"));
    assertEquals(false, p.get(0).get("resolved"));
    assertEquals(false, p.get(1).get("resolved"));
    assertEquals(0, calls.get());
  }

  @Test
  void totalDeadlineStopsLaterRequestsAndMapsToHttp504() {
    var time = new AtomicLong();
    var count = new AtomicInteger();
    var c =
        new OkHttpClient.Builder()
            .addInterceptor(
                chain -> {
                  count.incrementAndGet();
                  time.addAndGet(TimeUnit.SECONDS.toNanos(16));
                  return response(chain, detail("104,30"));
                })
            .build();
    var g =
        new GeoService(
            config(), store(item(3, "B00001"), item(4, "B00002"), item(5, "B00003")), c, time::get);
    var e = assertThrows(BusinessException.class, () -> g.route(1L, 2L, "walking"));
    assertEquals(504, e.getCode());
    assertEquals(2, count.get());
    assertEquals(504, new GlobalExceptionHandler().business(e).getStatusCode().value());
  }

  @Test
  void eachCallIsBoundedByFifteenSecondsAndTheRemainingDeadline() {
    var time = new AtomicLong();
    var count = new AtomicInteger();
    var c =
        new OkHttpClient.Builder()
            .addInterceptor(
                chain -> {
                  int n = count.incrementAndGet();
                  assertEquals(
                      TimeUnit.SECONDS.toNanos(n == 1 ? 15 : 8),
                      chain.call().timeout().timeoutNanos());
                  time.addAndGet(TimeUnit.SECONDS.toNanos(n == 1 ? 22 : 8));
                  if (n == 2) throw new java.io.InterruptedIOException("fixture deadline");
                  return response(chain, detail("104,30"));
                })
            .build();
    var g = new GeoService(config(), store(item(3, "B00001"), item(4, "B00002")), c, time::get);
    assertEquals(
        504, assertThrows(BusinessException.class, () -> g.route(1L, 2L, "walking")).getCode());
    assertEquals(2, count.get());
  }

  @Test
  void disabledCapabilityMakesNoRouteCalls() {
    var config = config();
    config.setFreeQuotaVerified(false);
    var count = new AtomicInteger();
    var c =
        new OkHttpClient.Builder()
            .addInterceptor(
                chain -> {
                  count.incrementAndGet();
                  return response(chain, "{}");
                })
            .build();
    assertEquals(
        503,
        assertThrows(
                BusinessException.class,
                () -> new GeoService(config, store(item(3, "B00001")), c).route(1L, 2L, "walking"))
            .getCode());
    assertEquals(0, count.get());
  }
}

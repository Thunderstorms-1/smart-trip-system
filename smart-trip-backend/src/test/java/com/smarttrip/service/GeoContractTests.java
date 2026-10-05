package com.smarttrip.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.smarttrip.common.BusinessException;
import com.smarttrip.config.GeoProperties;
import com.smarttrip.entity.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.*;
import org.junit.jupiter.api.Test;

class GeoContractTests {
  GeoProperties config() {
    var p = new GeoProperties();
    p.setEnabled(true);
    p.setPoiEnabled(true);
    p.setRouteEnabled(true);
    p.setWeatherEnabled(true);
    p.setMapEnabled(true);
    p.setFreeQuotaVerified(true);
    p.setAmapKey("fixture-only-key");
    return p;
  }

  OkHttpClient response(String body, AtomicInteger calls) {
    return new OkHttpClient.Builder()
        .addInterceptor(
            chain -> {
              calls.incrementAndGet();
              return new Response.Builder()
                  .request(chain.request())
                  .protocol(Protocol.HTTP_1_1)
                  .code(200)
                  .message("Local fixture")
                  .body(ResponseBody.create(body, MediaType.get("application/json")))
                  .build();
            })
        .build();
  }

  @Test
  void unverifiedEntitlementMakesZeroRequests() {
    var p = config();
    p.setFreeQuotaVerified(false);
    var count = new AtomicInteger();
    var g = new GeoService(p, mock(WorkbenchService.class), response("{}", count));
    assertThrows(BusinessException.class, () -> g.search("成都", "宽窄巷子"));
    assertEquals(0, count.get());
  }

  @Test
  void searchUsesVendorCoordinatesAndNeverInventsRating() {
    var count = new AtomicInteger();
    var g =
        new GeoService(
            config(),
            mock(WorkbenchService.class),
            response(
                "{\"status\":\"1\",\"pois\":[{\"id\":\"B000TEST\",\"name\":\"宽窄巷子\",\"address\":\"成都\",\"location\":\"104.05,30.66\",\"adcode\":\"510100\"}]}",
                count));
    var p = g.search("成都", "宽窄巷子").get(0);
    assertEquals("GCJ02", p.get("coordinateSystem"));
    assertEquals("104.05,30.66", p.get("location"));
    assertFalse(p.containsKey("rating"));
    assertEquals(1, count.get());
  }

  @Test
  void farFutureDateHasNoFabricatedForecastAndMakesZeroRequests() {
    var count = new AtomicInteger();
    var g = new GeoService(config(), mock(WorkbenchService.class), response("{}", count));
    assertEquals(false, g.weather("成都", LocalDate.now().plusYears(2)).get("available"));
    assertEquals(0, count.get());
  }

  @Test
  void forecastMustMatchActualRequestedDate() {
    String date = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString();
    var count = new AtomicInteger();
    var g =
        new GeoService(
            config(),
            mock(WorkbenchService.class),
            response(
                "{\"status\":\"1\",\"forecasts\":[{\"city\":\"成都\",\"reporttime\":\"2026-10-04"
                    + " 10:00:00\",\"casts\":[{\"date\":\""
                    + date
                    + "\",\"dayweather\":\"多云\",\"nightweather\":\"晴\",\"daytemp\":\"24\",\"nighttemp\":\"18\"}]}]}",
                count));
    var w = g.weather("510100", LocalDate.parse(date));
    assertEquals(true, w.get("available"));
    assertEquals("24", w.get("high"));
    assertEquals(false, g.weather("510100", LocalDate.parse(date).plusDays(1)).get("available"));
  }

  @Test
  void unresolvedStopsRemainUnresolvedWithoutStraightLineFallback() {
    var trip = new TripDetailVO();
    trip.setVersion(7L);
    var day = new TripDayVO();
    day.setId(1L);
    var a = new TripItem();
    a.setId(2L);
    a.setItemName("未知地点");
    var b = new TripItem();
    b.setId(3L);
    b.setItemName("另一个未知地点");
    day.setItems(List.of(a, b));
    trip.setDays(List.of(day));
    var store = mock(WorkbenchService.class);
    when(store.load(9L)).thenReturn(trip);
    var count = new AtomicInteger();
    var geo = new GeoService(config(), store, response("{}", count));
    var r = geo.route(9L, 1L, "walking");
    var legs = (List<Map<String, Object>>) r.get("legs");
    assertEquals(false, legs.get(0).get("available"));
    assertEquals(0, count.get());
  }

  @Test
  void routeReturnsProviderPolylineDistanceAndDuration() {
    var trip = new TripDetailVO();
    trip.setVersion(7L);
    var day = new TripDayVO();
    day.setId(1L);
    var items = new ArrayList<TripItem>();
    for (int n = 0; n < 2; n++) {
      var item = new TripItem();
      item.setId((long) n + 2);
      item.setItemName("自有地点" + n);
      item.setLatitude(new BigDecimal("30.01"));
      item.setLongitude(new BigDecimal("104.01").add(new BigDecimal(n)));
      item.setCoordinateSystem("GCJ02");
      items.add(item);
    }
    day.setItems(items);
    trip.setDays(List.of(day));
    var store = mock(WorkbenchService.class);
    when(store.load(9L)).thenReturn(trip);
    var count = new AtomicInteger();
    var geo =
        new GeoService(
            config(),
            store,
            response(
                "{\"status\":\"1\",\"route\":{\"paths\":[{\"distance\":\"1020\",\"cost\":{\"duration\":\"680\"},\"steps\":[{\"polyline\":\"104.01,30.01;104.02,30.02\"}]}]}}",
                count));
    var result = geo.route(9L, 1L, "walking");
    var leg = ((List<Map<String, Object>>) result.get("legs")).get(0);
    assertEquals("1020", leg.get("distanceMeters"));
    assertEquals("680", leg.get("durationSeconds"));
    assertEquals(List.of("104.01,30.01;104.02,30.02"), leg.get("polylines"));
  }
}

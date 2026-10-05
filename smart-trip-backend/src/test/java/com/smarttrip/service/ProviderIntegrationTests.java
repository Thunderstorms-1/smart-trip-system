package com.smarttrip.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smarttrip.common.*;
import com.smarttrip.config.*;
import com.smarttrip.controller.GeoController;
import com.smarttrip.entity.*;
import java.io.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import okhttp3.*;
import okio.Buffer;
import org.junit.jupiter.api.Test;

class ProviderIntegrationTests {
  private final ObjectMapper json = new ObjectMapper();

  AiProperties aiConfig() {
    var p = new AiProperties();
    p.setEnabled(true);
    p.setFreeQuotaVerified(true);
    p.setApiKey("fixture-provider-key");
    return p;
  }

  GeoProperties geoConfig() {
    var p = new GeoProperties();
    p.setEnabled(true);
    p.setFreeQuotaVerified(true);
    p.setAmapKey("fixture-web-key");
    return p;
  }

  Response response(Interceptor.Chain c, int code, String body) {
    return new Response.Builder()
        .request(c.request())
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("fixture")
        .body(ResponseBody.create(body, MediaType.get("application/json")))
        .header("x-request-id", "fixture-request-id")
        .build();
  }

  String sse(String answer) throws Exception {
    return "data: "
        + json.writeValueAsString(
            Map.of(
                "choices",
                List.of(Map.of("delta", Map.of("content", answer), "finish_reason", "stop"))))
        + "\n\n"
        + "data:"
        + " {\"choices\":[],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":5,\"total_tokens\":17}}\n\n"
        + "data: [DONE]\n\n";
  }

  @Test
  void defaultsRequireExplicitFeatureOptIn() {
    var p = geoConfig();
    var g = new GeoService(p, mock(WorkbenchService.class));
    assertEquals("DISABLED", g.status());
    assertFalse(g.available());
    assertFalse(g.mapAvailable());
    p.setRouteEnabled(true);
    assertTrue(g.routeAvailable());
    assertFalse(g.poiAvailable());
    assertFalse(g.weatherAvailable());
    p.setEnabled(false);
    assertFalse(g.routeAvailable());
  }

  @Test
  void mapCanBeConfiguredIndependentlyFromWebServices() {
    var p = geoConfig();
    p.setAmapKey("");
    p.setMapEnabled(true);
    var g = new GeoService(p, mock(WorkbenchService.class));
    assertEquals("BROWSER_KEY_MISSING", g.mapStatus());
    p.setAmapJsKey("fixture-js-key");
    p.setAmapSecurityKey("fixture-security-code");
    assertTrue(g.mapAvailable());
    assertFalse(g.available());
    assertEquals("GCJ02", g.mapConfig().get("coordinateSystem"));
    p.setFreeQuotaVerified(false);
    assertEquals("QUOTA_UNVERIFIED", g.mapStatus());
  }

  @Test
  void disabledFeaturesMakeNoRequestsEvenWhenAnotherFeatureIsReady() {
    var p = geoConfig();
    p.setRouteEnabled(true);
    var calls = new AtomicInteger();
    var c =
        new OkHttpClient.Builder()
            .addInterceptor(
                chain -> {
                  calls.incrementAndGet();
                  return response(chain, 200, "{}");
                })
            .build();
    var g = new GeoService(p, mock(WorkbenchService.class), c);
    assertEquals(503, assertThrows(BusinessException.class, () -> g.search("成都", "公园")).getCode());
    assertEquals(
        503,
        assertThrows(BusinessException.class, () -> g.weather("510100", LocalDate.now()))
            .getCode());
    p.setRouteEnabled(false);
    p.setWeatherEnabled(true);
    assertEquals(
        503, assertThrows(BusinessException.class, () -> g.route(1L, 2L, "walking")).getCode());
    assertEquals(0, calls.get());
  }

  @Test
  void capabilitiesAreSafeAndExposeIndependentStates() throws Exception {
    var p = geoConfig();
    p.setWeatherEnabled(true);
    var g = new GeoService(p, mock(WorkbenchService.class));
    var caps = new GeoController(g, new AiProvider(new AiProperties())).capabilities();
    var tree = json.valueToTree(caps.getData());
    assertTrue(tree.path("weather").asBoolean());
    assertFalse(tree.path("route").asBoolean());
    assertFalse(tree.toString().contains("fixture-web-key"));
    assertFalse(tree.toString().contains("securityJsCode"));
    assertEquals("DISABLED", tree.path("status").path("route").asText());
  }

  @Test
  void aiUsesJsonBoundsNonThinkingAndUsageWithoutExtraCalls() throws Exception {
    var calls = new AtomicInteger();
    var p = aiConfig();
    p.setMaxOutputTokens(8192);
    var c =
        new OkHttpClient.Builder()
            .addInterceptor(
                chain -> {
                  calls.incrementAndGet();
                  var buffer = new Buffer();
                  chain.request().body().writeTo(buffer);
                  var payload = json.readTree(buffer.readUtf8());
                  assertEquals(8192, payload.path("max_tokens").asInt());
                  assertFalse(payload.path("enable_thinking").asBoolean(true));
                  assertEquals(
                      "json_object", payload.path("response_format").path("type").asText());
                  assertTrue(payload.path("stream_options").path("include_usage").asBoolean());
                  try {
                    return response(chain, 200, sse("{\"days\":[]}"));
                  } catch (Exception e) {
                    throw new IOException(e);
                  }
                })
            .build();
    var provider = new AiProvider(p, c);
    assertEquals("{\"days\":[]}", provider.complete("fixture"));
    assertEquals(1, calls.get());
  }

  @Test
  void aiGateAndInvalidTokenBoundMakeZeroRequests() {
    var calls = new AtomicInteger();
    var c =
        new OkHttpClient.Builder()
            .addInterceptor(
                chain -> {
                  calls.incrementAndGet();
                  return response(chain, 200, "{}");
                })
            .build();
    var p = aiConfig();
    p.setEnabled(false);
    var a = new AiProvider(p, c);
    assertEquals(503, assertThrows(BusinessException.class, () -> a.complete("fixture")).getCode());
    p.setEnabled(true);
    p.setFreeQuotaVerified(false);
    assertThrows(BusinessException.class, () -> a.complete("fixture"));
    p.setFreeQuotaVerified(true);
    p.setMaxOutputTokens(0);
    assertThrows(BusinessException.class, () -> a.complete("fixture"));
    assertEquals(0, calls.get());
  }

  @Test
  void upstream401NeverExpiresTheApplicationSession() {
    for (int status : new int[] {401, 403, 429, 500, 302}) {
      var calls = new AtomicInteger();
      var c =
          new OkHttpClient.Builder()
              .addInterceptor(
                  chain -> {
                    calls.incrementAndGet();
                    return response(
                        chain, status, "{\"error\":{\"message\":\"private raw detail\"}}");
                  })
              .build();
      var e =
          assertThrows(
              BusinessException.class, () -> new AiProvider(aiConfig(), c).complete("fixture"));
      assertEquals(status == 429 ? 429 : status == 401 || status == 403 ? 503 : 502, e.getCode());
      assertNotEquals(401, new GlobalExceptionHandler().business(e).getStatusCode().value());
      assertFalse(e.getMessage().contains("private"));
      assertEquals(1, calls.get());
    }
  }

  @Test
  void freeTierOnlyHttpAndStreamFailuresAreRecognized() {
    for (boolean stream : new boolean[] {false, true}) {
      var calls = new AtomicInteger();
      var body = "{\"error\":{\"code\":\"AllocationQuota.FreeTierOnly\",\"message\":\"private\"}}";
      var c =
          new OkHttpClient.Builder()
              .addInterceptor(
                  chain -> {
                    calls.incrementAndGet();
                    return response(
                        chain, stream ? 200 : 403, stream ? "data: " + body + "\n\n" : body);
                  })
              .build();
      var e =
          assertThrows(
              BusinessException.class, () -> new AiProvider(aiConfig(), c).complete("fixture"));
      assertEquals(503, e.getCode());
      assertTrue(e.getMessage().contains("免费额度"));
      assertFalse(e.getMessage().contains("private"));
      assertEquals(1, calls.get());
    }
  }

  @Test
  void timeoutIs504AndDoesNotRetry() {
    var calls = new AtomicInteger();
    var c =
        new OkHttpClient.Builder()
            .addInterceptor(
                chain -> {
                  calls.incrementAndGet();
                  throw new java.net.SocketTimeoutException("fixture");
                })
            .build();
    assertEquals(
        504,
        assertThrows(
                BusinessException.class, () -> new AiProvider(aiConfig(), c).complete("fixture"))
            .getCode());
    assertEquals(1, calls.get());
  }

  @Test
  void doneWithoutStopOrTruncatedContentCannotSucceed() {
    for (String reason : new String[] {"null", "\"length\"", "\"content_filter\""}) {
      var body =
          "data: {\"choices\":[{\"delta\":{\"content\":\"{}\"},\"finish_reason\":"
              + reason
              + "}]}\n\ndata: [DONE]\n\n";
      var c =
          new OkHttpClient.Builder().addInterceptor(chain -> response(chain, 200, body)).build();
      assertThrows(
          BusinessException.class, () -> new AiProvider(aiConfig(), c).complete("fixture"));
    }
  }

  @Test
  void geoBusinessErrorsAreClassifiedWithoutLeakingResponses() {
    for (String code :
        new String[] {"10001", "10002", "10003", "10004", "10009", "10029", "99999"}) {
      var p = geoConfig();
      p.setPoiEnabled(true);
      var calls = new AtomicInteger();
      var c =
          new OkHttpClient.Builder()
              .addInterceptor(
                  chain -> {
                    calls.incrementAndGet();
                    return response(
                        chain,
                        200,
                        "{\"status\":\"0\",\"infocode\":\"" + code + "\",\"info\":\"private\"}");
                  })
              .build();
      var e =
          assertThrows(
              BusinessException.class,
              () -> new GeoService(p, mock(WorkbenchService.class), c).search("成都", "公园"));
      assertEquals(
          Set.of("10004", "10029").contains(code) ? 429 : "99999".equals(code) ? 502 : 503,
          e.getCode());
      assertFalse(e.getMessage().contains("private"));
      assertEquals(1, calls.get());
    }
  }

  @Test
  void geoHttpAuthAndTimeoutDoNotBecomeUserAuthErrors() {
    var p = geoConfig();
    p.setPoiEnabled(true);
    var c =
        new OkHttpClient.Builder().addInterceptor(chain -> response(chain, 401, "private")).build();
    assertEquals(
        503,
        assertThrows(
                BusinessException.class,
                () -> new GeoService(p, mock(WorkbenchService.class), c).search("成都", "公园"))
            .getCode());
    var timeout =
        new OkHttpClient.Builder()
            .addInterceptor(
                chain -> {
                  throw new java.net.SocketTimeoutException();
                })
            .build();
    assertEquals(
        504,
        assertThrows(
                BusinessException.class,
                () -> new GeoService(p, mock(WorkbenchService.class), timeout).search("成都", "公园"))
            .getCode());
  }

  WorkbenchService trip() {
    var t = new TripDetailVO();
    t.setVersion(1L);
    var d = new TripDayVO();
    d.setId(2L);
    var items = new ArrayList<TripItem>();
    for (long n = 1; n <= 2; n++) {
      var i = new TripItem();
      i.setId(n);
      i.setItemName("自有地点" + n);
      i.setCoordinateSystem("GCJ02");
      i.setLongitude(new BigDecimal("104"));
      i.setLatitude(new BigDecimal("30"));
      items.add(i);
    }
    d.setItems(items);
    t.setDays(List.of(d));
    var store = mock(WorkbenchService.class);
    when(store.load(1L)).thenReturn(t);
    return store;
  }

  @Test
  void malformedRouteNeverExposesInventedZeroValuesOrGeometry() {
    for (String fields :
        List.of(
            "\"distance\":\"NaN\",\"cost\":{\"duration\":\"30\"},\"steps\":[{\"polyline\":\"104,30;105,31\"}]",
            "\"distance\":\"100\",\"cost\":{\"duration\":\"-1\"},\"steps\":[{\"polyline\":\"104,30;105,31\"}]",
            "\"distance\":\"100\",\"cost\":{\"duration\":\"30\"},\"steps\":[{\"polyline\":\"104,30;NaN,31\"}]",
            "\"distance\":\"100\",\"steps\":[{\"polyline\":\"104,30;105,31\"}]")) {
      var p = geoConfig();
      p.setRouteEnabled(true);
      var c =
          new OkHttpClient.Builder()
              .addInterceptor(
                  chain ->
                      response(
                          chain,
                          200,
                          "{\"status\":\"1\",\"route\":{\"paths\":[{" + fields + "}]}}"))
              .build();
      var leg =
          (Map<?, ?>)
              ((List<?>) new GeoService(p, trip(), c).route(1L, 2L, "walking").get("legs")).get(0);
      assertEquals(false, leg.get("available"));
      assertFalse(leg.containsKey("durationSeconds"));
      assertFalse(leg.containsKey("polylines"));
    }
  }

  @Test
  void forecastUsesActualReturnedDatesAndNormalizesTemperatureOrder() {
    var date = LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(2);
    var p = geoConfig();
    p.setWeatherEnabled(true);
    var c =
        new OkHttpClient.Builder()
            .addInterceptor(
                chain ->
                    response(
                        chain,
                        200,
                        "{\"status\":\"1\",\"forecasts\":[{\"city\":\"成都\",\"reporttime\":\"fixture\",\"casts\":[{\"date\":\""
                            + date
                            + "\",\"dayweather\":\"晴\",\"nightweather\":\"多云\",\"daytemp\":\"15\",\"nighttemp\":\"20\"}]}]}"))
            .build();
    var g = new GeoService(p, mock(WorkbenchService.class), c);
    var w = g.weather("510100", date);
    assertEquals(true, w.get("available"));
    assertEquals("20", w.get("high"));
    assertEquals("15", w.get("low"));
    assertEquals(false, g.weather("510100", date.plusDays(1)).get("available"));
  }

  @Test
  void incompleteForecastDoesNotBecomeAnAiWeatherFact() {
    var date = LocalDate.now(ZoneId.of("Asia/Shanghai"));
    var p = geoConfig();
    p.setWeatherEnabled(true);
    var c =
        new OkHttpClient.Builder()
            .addInterceptor(
                chain ->
                    response(
                        chain,
                        200,
                        "{\"status\":\"1\",\"forecasts\":[{\"casts\":[{\"date\":\""
                            + date
                            + "\",\"dayweather\":\"暴雨\",\"daytemp\":\"NaN\"}]}]}"))
            .build();
    var w = new GeoService(p, mock(WorkbenchService.class), c).weather("510100", date);
    assertEquals(false, w.get("available"));
    assertFalse(w.containsKey("dayWeather"));
  }

  @Test
  void missingOrWrongPoiIdentityIsNotOfferedToUsers() {
    var p = geoConfig();
    p.setPoiEnabled(true);
    var c =
        new OkHttpClient.Builder()
            .addInterceptor(
                chain ->
                    response(
                        chain,
                        200,
                        "{\"status\":\"1\",\"pois\":[{\"location\":\"104,30\"},{\"id\":\"bad/id\",\"name\":\"公园\",\"location\":\"104,30\"}]}"))
            .build();
    assertTrue(new GeoService(p, mock(WorkbenchService.class), c).search("成都", "公园").isEmpty());
  }
}

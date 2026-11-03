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

  String completion(String answer, String reason) throws Exception {
    return json.writeValueAsString(Map.of(
        "id", "fixture-completion", "object", "chat.completion", "created", 1,
        "model", "fixture-model", "choices", List.of(Map.of("index", 0,
            "message", Map.of("role", "assistant", "content", answer), "finish_reason", reason)),
        "usage", Map.of("prompt_tokens", 12, "completion_tokens", 5, "total_tokens", 17)));
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
                  assertFalse(payload.path("stream").asBoolean());
                  assertFalse(payload.has("tools"));
                  assertEquals("/compatible-mode/v1/chat/completions", chain.request().url().encodedPath());
                  try {
                    return response(chain, 200, completion("{\"days\":[]}", "stop"));
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
  void springAiInterpretationConverterPreservesExplicitRequirementsAndUnknownFields() throws Exception {
    String description = "想从苏州去大兴安岭旅行5天，和父母同行，喜欢摄影，预算5000；人数、日期和预算口径还没确定";
    var extracted = json.readTree("""
        {"description":null,"origin":"苏州","destinations":["大兴安岭"],"days":5,
         "startDate":null,"timeDescription":null,"includesTravelDays":null,"travelers":null,
         "companions":"父母","budget":5000,"budgetMax":null,"budgetScope":"UNKNOWN","currency":null,
         "budgetIncludes":null,"interests":["摄影"],"pace":null,"exertion":null,"transport":null,
         "accommodation":null,"mustVisit":null,"avoid":null,"timeConstraints":null,"notes":null}
        """);
    var calls = new AtomicInteger();
    var config = aiConfig();
    config.setBaseUrl("https://fixture.invalid/compatible-mode/v1");
    var client = new OkHttpClient.Builder().addInterceptor(chain -> {
      calls.incrementAndGet();
      var buffer = new Buffer();
      chain.request().body().writeTo(buffer);
      var request = json.readTree(buffer.readUtf8());
      assertEquals("json_object", request.path("response_format").path("type").asText());
      assertEquals(description, request.path("messages").get(1).path("content").asText());
      String system = request.path("messages").get(0).path("content").asText();
      String prefix = "以下JSON Schema：";
      int schemaStart = system.indexOf(prefix);
      assertTrue(schemaStart >= 0);
      var schema = json.readTree(system.substring(schemaStart + prefix.length()));
      assertTrue(schema.path("properties").path("budget").path("anyOf").isArray());
      assertTrue(schema.path("properties").path("travelers").path("anyOf").isArray());
      assertFalse(request.has("tools"));
      try { return response(chain, 200, completion(extracted.toString(), "stop")); }
      catch (Exception e) { throw new IOException(e); }
    }).build();
    var requirement = new AiProvider(config, client).interpret(description);
    assertEquals(1, calls.get());
    assertEquals(description, requirement.description());
    assertEquals("苏州", requirement.origin());
    assertEquals(List.of("大兴安岭"), requirement.destinations());
    assertEquals(5, requirement.days().intValue());
    assertEquals("父母", requirement.companions());
    assertEquals(List.of("摄影"), requirement.interests());
    assertEquals(0, new BigDecimal("5000").compareTo(requirement.budget()));
    assertEquals("UNKNOWN", requirement.budgetScope());
    assertNull(requirement.travelers());
    assertNull(requirement.startDate());
    assertNull(requirement.includesTravelDays());
    assertNull(requirement.currency());
    assertNull(requirement.budgetMax());
    assertNull(requirement.pace());
    assertNull(requirement.transport());
    assertNull(requirement.accommodation());
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
  void freeTierOnlyHttpAndBodyFailuresAreRecognized() {
    for (boolean stream : new boolean[] {false, true}) {
      var calls = new AtomicInteger();
      var body = "{\"error\":{\"code\":\"AllocationQuota.FreeTierOnly\",\"message\":\"private\"}}";
      var c =
          new OkHttpClient.Builder()
              .addInterceptor(
                  chain -> {
                    calls.incrementAndGet();
                    return response(
                        chain, stream ? 200 : 403, body);
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
  void incompleteOrMalformedModelOutputCannotSucceed() throws Exception {
    for (String reason : new String[] {"length", "content_filter", "tool_calls"}) {
      var body = completion("{}", reason);
      var c = new OkHttpClient.Builder().addInterceptor(chain -> response(chain, 200, body)).build();
      assertThrows(BusinessException.class, () -> new AiProvider(aiConfig(), c).complete("fixture"));
    }
    for (String text : new String[] {"not json", "[]", "{} {}"}) {
      var body = completion(text, "stop");
      var c = new OkHttpClient.Builder().addInterceptor(chain -> response(chain, 200, body)).build();
      assertThrows(BusinessException.class, () -> new AiProvider(aiConfig(), c).complete("fixture"));
    }
  }

  @Test
  void incompleteGenerationKeepsExactResponseAndConsumableSafeDiagnostics() throws Exception {
    String raw = "\u0060\u0060\u0060json\n{\"title\":\"fixture\",\"days\":[]}\n\u0060\u0060\u0060";
    var calls = new AtomicInteger();
    var body = completion(raw, "stop");
    var client = new OkHttpClient.Builder().addInterceptor(chain -> {
      calls.incrementAndGet();
      return response(chain, 200, body);
    }).build();
    var provider = new AiProvider(aiConfig(), client);
    String answer = provider.complete("GENERATE_DIAGNOSTIC_FIXTURE");
    assertEquals(422, assertThrows(BusinessException.class, () -> provider.parseGeneration(answer)).getCode());
    var captured = provider.takeDiagnostics();
    assertEquals(raw, captured.get("responseText"));
    assertEquals(raw.length(), captured.get("responseCharacters"));
    assertEquals("fixture-completion", captured.get("requestId"));
    assertEquals("STOP", captured.get("finishReason"));
    assertEquals(8192, captured.get("maxOutputTokens"));
    assertEquals(12, captured.get("inputTokens"));
    assertEquals(5, captured.get("outputTokens"));
    assertEquals(17, captured.get("totalTokens"));
    var shape = (Map<?, ?>) captured.get("shape");
    assertEquals("ARRAY", shape.get("daysType"));
    assertEquals(0, shape.get("dayCount"));
    assertEquals(0, shape.get("nodeCount"));
    assertTrue(provider.takeDiagnostics().isEmpty());
    assertEquals(1, calls.get());
  }

  @Test
  void generationParserOnlyUnwrapsSingleUnambiguousPlansAndNeverFillsMissingNodes() throws Exception {
    var provider = new AiProvider(new AiProperties());
    String valid = "{\"title\":\"fixture\",\"destination\":\"fixture\",\"days\":[{\"items\":[{\"itemName\":\"fixture\"}]}]}";
    assertEquals(valid, provider.parseGeneration(valid).json());
    for (String name : List.of("data", "result", "trip")) {
      var wrapper = json.createObjectNode();
      wrapper.set(name, json.readTree(valid));
      assertEquals(json.readTree(valid), json.readTree(provider.parseGeneration(wrapper.toString()).json()));
    }
    for (String invalid : List.of(
        "{\"days\":[]}", "{\"days\":[{\"items\":[]}]}", "{\"days\":[{}]}",
        "{\"data\":" + valid + ",\"result\":" + valid + "}",
        "{\"days\":[],\"trip\":" + valid + "}",
        "{\"data\":" + valid + ",\"otherCandidate\":" + valid + "}",
        "{\"data\":[" + valid + "]}", "{\"days\":[],\"days\":[{\"items\":[{}]}]}")) {
      assertEquals(422, assertThrows(BusinessException.class, () -> provider.parseGeneration(invalid)).getCode());
    }
    // ADJUST keeps the legacy days-array contract; generation checks must not alter it.
    assertEquals("{\"days\":[],\"reasons\":[]}", provider.parse("{\"days\":[],\"reasons\":[]}").json());
  }

  @Test
  void generationNormalizationPreservesActivitiesAndLeavesAmbiguousValuesUnknown() throws Exception {
    String raw = """
        {"data":{"title":"fixture","destination":"fixture","startDate":"2026/10/28",
          "totalDays":"2","totalBudget":"0","days":[
          {"dayIndex":"1","dayDate":"2026/10/28","items":[
            {"itemName":"fixture scenic","itemType":"景点","startTime":"9:00","costTimeHours":"1.50","costEstimate":"80.00"},
            {"itemName":"fixture meal","itemType":"餐饮","startTime":"08:00:00","costTimeHours":"1","costEstimate":"约20元"},
            {"itemName":"fixture transit","itemType":"transportation","startTime":"11:30:15","costTimeHours":"约2小时","costEstimate":"30"}]},
          {"dayIndex":"2","dayDate":"2026/02/30","items":[
            {"itemName":"fixture hotel","itemType":"住宿","startTime":"18:00","costTimeHours":"0.5","costEstimate":"100"}]}]}}
        """;
    var calls = new AtomicInteger();
    var body = completion(raw, "stop");
    var client = new OkHttpClient.Builder().addInterceptor(chain -> {
      calls.incrementAndGet();
      var buffer = new Buffer();
      chain.request().body().writeTo(buffer);
      var request = json.readTree(buffer.readUtf8());
      var system = request.path("messages").get(0).path("content").asText();
      assertTrue(system.contains("唯一输出契约"));
      assertFalse(system.contains("days[{"));
      return response(chain, 200, body);
    }).build();
    var provider = new AiProvider(aiConfig(), client);
    var answer = provider.complete("以下JSON是用户已确认需求：NORMALIZATION_FIXTURE");
    var normalized = json.readTree(provider.parseGeneration(answer).json());
    assertEquals("2026-10-28", normalized.path("startDate").asText());
    assertTrue(normalized.path("totalDays").isIntegralNumber());
    assertEquals(2, normalized.path("days").size());
    assertEquals(0, normalized.path("totalBudget").asInt()); // No supplier sum is fabricated here.
    var firstDay = normalized.path("days").get(0);
    assertEquals("2026-10-28", firstDay.path("dayDate").asText());
    var nodes = firstDay.path("items");
    assertEquals(3, nodes.size());
    assertEquals("fixture scenic", nodes.get(0).path("itemName").asText());
    assertEquals("SCENIC", nodes.get(0).path("itemType").asText());
    assertEquals("09:00", nodes.get(0).path("startTime").asText());
    assertEquals(0, new BigDecimal("80").compareTo(nodes.get(0).path("costEstimate").decimalValue()));
    assertEquals("FOOD", nodes.get(1).path("itemType").asText());
    assertEquals("08:00", nodes.get(1).path("startTime").asText()); // No schedule reordering or moving times.
    assertTrue(nodes.get(1).path("costEstimate").isNull());
    assertEquals("TRAFFIC", nodes.get(2).path("itemType").asText());
    assertTrue(nodes.get(2).path("startTime").isNull()); // Non-zero seconds are not silently discarded.
    assertTrue(nodes.get(2).path("costTimeHours").isNull());
    assertEquals("HOTEL", normalized.path("days").get(1).path("items").get(0).path("itemType").asText());
    assertTrue(normalized.path("days").get(1).path("dayDate").isNull());
    assertEquals(raw, provider.takeDiagnostics().get("responseText"));
    assertEquals(1, calls.get());
  }

  @Test
  void truncatedResponseIsDiagnosableAndNextRejectedRequestClearsThreadLocalState() throws Exception {
    var calls = new AtomicInteger();
    var config = aiConfig();
    var body = completion("{\"days\":[]}", "length");
    var client = new OkHttpClient.Builder().addInterceptor(chain -> {
      calls.incrementAndGet();
      return response(chain, 200, body);
    }).build();
    var provider = new AiProvider(config, client);
    assertEquals(422, assertThrows(BusinessException.class, () -> provider.complete("GENERATE_DIAGNOSTIC_FIXTURE")).getCode());
    var captured = provider.takeDiagnostics();
    assertEquals("LENGTH", captured.get("finishReason"));
    assertEquals("{\"days\":[]}", captured.get("responseText"));
    assertThrows(BusinessException.class, () -> provider.complete("GENERATE_DIAGNOSTIC_FIXTURE"));
    config.setEnabled(false);
    assertEquals(503, assertThrows(BusinessException.class, () -> provider.complete("ANOTHER_GENERATION_FIXTURE")).getCode());
    assertTrue(provider.takeDiagnostics().isEmpty());
    assertEquals(2, calls.get());
  }

  @Test
  void fullEndpointUrlIsNotDuplicatedAndAdjustKeepsItsConstraints() throws Exception {
    var p = aiConfig();
    p.setBaseUrl("https://fixture.invalid/compatible-mode/v1/chat/completions/");
    var calls = new AtomicInteger();
    var c = new OkHttpClient.Builder().addInterceptor(chain -> {
      calls.incrementAndGet();
      assertEquals("/compatible-mode/v1/chat/completions", chain.request().url().encodedPath());
      var buffer = new Buffer();
      chain.request().body().writeTo(buffer);
      var payload = json.readTree(buffer.readUtf8());
      var system = payload.path("messages").get(0).path("content").asText();
      assertTrue(system.contains("固定安排不能移动"));
      assertTrue(system.contains("不能新增删除节点"));
      assertEquals("ADJUST_SCHEMA_V2 fixture", payload.path("messages").get(1).path("content").asText());
      try { return response(chain, 200, completion("{\"days\":[],\"reasons\":[]}", "stop")); }
      catch (Exception e) { throw new IOException(e); }
    }).build();
    assertEquals("{\"days\":[],\"reasons\":[]}", new AiProvider(p, c).complete("ADJUST_SCHEMA_V2 fixture"));
    assertEquals(1, calls.get());
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

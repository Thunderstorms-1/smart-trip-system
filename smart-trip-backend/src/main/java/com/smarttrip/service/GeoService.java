package com.smarttrip.service;

import com.fasterxml.jackson.databind.*;
import com.smarttrip.common.*;
import com.smarttrip.config.GeoProperties;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import okhttp3.*;
import org.springframework.stereotype.Service;

@Service
public class GeoService {
  private final GeoProperties config;
  private final WorkbenchService trips;
  private final ObjectMapper json = new ObjectMapper();
  private final OkHttpClient client;
  @org.springframework.beans.factory.annotation.Autowired
  public GeoService(GeoProperties config, WorkbenchService trips) {this(config,trips,new OkHttpClient.Builder().connectTimeout(5,TimeUnit.SECONDS).readTimeout(10,TimeUnit.SECONDS).callTimeout(15,TimeUnit.SECONDS).build());}
  GeoService(GeoProperties config,WorkbenchService trips,OkHttpClient client){this.config=config;this.trips=trips;this.client=client;}
  public boolean available() {
    return config.isEnabled() && config.isFreeQuotaVerified() && !config.getAmapKey().isBlank();
  }

  public boolean mapAvailable() {
    return available()
        && !config.getAmapJsKey().isBlank()
        && !config.getAmapSecurityKey().isBlank();
  }

  public Map<String, Object> mapConfig() {
    if (!mapAvailable()) throw new BusinessException(503, "地图服务的免费权限及浏览器Key尚未配置");
    return Map.of(
        "key",
        config.getAmapJsKey(),
        "securityJsCode",
        config.getAmapSecurityKey(),
        "provider",
        "AMAP",
        "coordinateSystem",
        "GCJ02");
  }

  private JsonNode call(String path, Map<String, String> args) {
    if (!available()) throw new BusinessException(503, "地图和天气免费权限尚未确认，暂未启用");
    HttpUrl.Builder url =
        Objects.requireNonNull(HttpUrl.parse("https://restapi.amap.com" + path))
            .newBuilder()
            .addQueryParameter("key", config.getAmapKey());
    args.forEach(url::addQueryParameter);
    try (Response r =
        client.newCall(new Request.Builder().url(url.build()).get().build()).execute()) {
      if (!r.isSuccessful() || r.body() == null) throw new BusinessException(502, "地理服务暂不可用");
      JsonNode n = json.readTree(r.body().string());
      if (!"1".equals(n.path("status").asText()))
        throw new BusinessException(502, "地理服务拒绝请求，请检查免费权限或配额");
      return n;
    } catch (BusinessException e) {
      throw e;
    } catch (Exception e) {
      throw new BusinessException(502, "地理服务连接失败，请稍后重试");
    }
  }

  public List<Map<String, Object>> search(String city, String keyword) {
    if (city == null
        || city.isBlank()
        || city.length() > 100
        || keyword == null
        || keyword.isBlank()
        || keyword.length() > 100) throw new BusinessException(400, "城市和地点名称必填");
    JsonNode n =
        call(
            "/v5/place/text",
            Map.of(
                "keywords",
                keyword,
                "region",
                city,
                "city_limit",
                "true",
                "page_size",
                "10",
                "show_fields",
                "business"));
    List<Map<String, Object>> result = new ArrayList<>();
    for (var p : n.path("pois")) {
      String location = p.path("location").asText();
      if (!location.matches("-?[0-9.]+,-?[0-9.]+")) continue;
      result.add(
          Map.of(
              "poiId",
              p.path("id").asText(),
              "name",
              p.path("name").asText(),
              "address",
              p.path("address").asText(),
              "location",
              location,
              "adcode",
              p.path("adcode").asText(),
              "coordinateSystem",
              "GCJ02",
              "provider",
              "AMAP"));
    }
    return result;
  }

  private Map<String, Object> point(com.smarttrip.entity.TripItem item) {
    if (item.getPoiId() != null && !item.getPoiId().isBlank()) {
      if (!item.getPoiId().matches("[A-Za-z0-9]{5,80}"))
        throw new BusinessException(400, "POI标识无效");
      var n = call("/v3/place/detail", Map.of("id", item.getPoiId(), "extensions", "base"));
      var p = n.path("pois");
      if (!p.isArray() || p.isEmpty())
        return Map.of("itemId", item.getId(), "name", item.getItemName(), "resolved", false);
      String loc = p.get(0).path("location").asText();
      if (!loc.matches("-?[0-9.]+,-?[0-9.]+"))
        return Map.of("itemId", item.getId(), "name", item.getItemName(), "resolved", false);
      return Map.of(
          "itemId",
          item.getId(),
          "name",
          item.getItemName(),
          "resolved",
          true,
          "location",
          loc,
          "coordinateSystem",
          "GCJ02");
    }
    if (item.getLongitude() != null
        && item.getLatitude() != null
        && "GCJ02".equals(item.getCoordinateSystem()))
      return Map.of(
          "itemId",
          item.getId(),
          "name",
          item.getItemName(),
          "resolved",
          true,
          "location",
          item.getLongitude() + "," + item.getLatitude(),
          "coordinateSystem",
          "GCJ02");
    return Map.of("itemId", item.getId(), "name", item.getItemName(), "resolved", false);
  }

  public Map<String, Object> route(Long tripId, Long dayId, String mode) {
    if (!available()) throw new BusinessException(503, "路径服务免费权限尚未确认");
    if (!Set.of("walking", "driving").contains(mode)) throw new BusinessException(400, "目前支持步行或驾车");
    var t = trips.load(tripId);
    var day =
        t.getDays().stream()
            .filter(d -> Objects.equals(d.getId(), dayId))
            .findFirst()
            .orElseThrow(() -> new BusinessException(404, "日程不存在"));
    if (day.getItems().size() > 20) throw new BusinessException(400, "单次路线最多20个节点，请精简当日路线");
    List<Map<String, Object>> points = new ArrayList<>(), legs = new ArrayList<>();
    for (var item : day.getItems()) points.add(point(item));
    for (int i = 1; i < points.size(); i++) {
      var a = points.get(i - 1);
      var b = points.get(i);
      if (!Boolean.TRUE.equals(a.get("resolved")) || !Boolean.TRUE.equals(b.get("resolved"))) {
        legs.add(
            Map.of(
                "from",
                a.get("itemId"),
                "to",
                b.get("itemId"),
                "available",
                false,
                "reason",
                "请先选择真实地点"));
        continue;
      }
      var n =
          call(
              "/v5/direction/" + mode,
              Map.of(
                  "origin",
                  a.get("location").toString(),
                  "destination",
                  b.get("location").toString(),
                  "show_fields",
                  "cost,polyline"));
      var paths = n.path("route").path("paths");
      if (!paths.isArray() || paths.isEmpty()) {
        legs.add(
            Map.of(
                "from",
                a.get("itemId"),
                "to",
                b.get("itemId"),
                "available",
                false,
                "reason",
                "暂无可用路线"));
        continue;
      }
      var route = paths.get(0);
      List<String> polylines = new ArrayList<>();
      for (var step : route.path("steps"))
        if (!step.path("polyline").asText().isBlank())
          polylines.add(step.path("polyline").asText());
      legs.add(
          Map.of(
              "from",
              a.get("itemId"),
              "to",
              b.get("itemId"),
              "available",
              !polylines.isEmpty(),
              "distanceMeters",
              route.path("distance").asText(),
              "durationSeconds",
              route.path("cost").path("duration").asText(),
              "polylines",
              polylines));
    }
    return Map.of(
        "provider",
        "AMAP",
        "coordinateSystem",
        "GCJ02",
        "tripVersion",
        t.getVersion(),
        "points",
        points,
        "legs",
        legs,
        "mode",
        mode,
        "retrievedAt",
        Instant.now().toString());
  }

  public Map<String, Object> weather(String city, LocalDate date) {
    if (city == null || city.isBlank() || city.length() > 100 || date == null)
      throw new BusinessException(400, "城市和日期必填");
    LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
    if (date.isBefore(today) || date.isAfter(today.plusDays(3)))
      return Map.of(
          "available", false, "reason", "所选日期超出当前天气预报范围，临近出发时再查看", "date", date.toString());
    String adcode = city;
    if (!city.matches("[0-9]{6}")) {
      var districts =
          call("/v3/config/district", Map.of("keywords", city, "subdistrict", "0"))
              .path("districts");
      if (!districts.isArray() || districts.size() != 1)
        throw new BusinessException(400, "城市不明确，请输入城市区划代码");
      adcode = districts.get(0).path("adcode").asText();
    }
    var n = call("/v3/weather/weatherInfo", Map.of("city", adcode, "extensions", "all"));
    var forecasts = n.path("forecasts");
    for (var f : forecasts)
      for (var cast : f.path("casts"))
        if (date.toString().equals(cast.path("date").asText()))
          return Map.of(
              "available",
              true,
              "provider",
              "AMAP",
              "city",
              f.path("city").asText(),
              "date",
              date.toString(),
              "dayWeather",
              cast.path("dayweather").asText(),
              "nightWeather",
              cast.path("nightweather").asText(),
              "high",
              cast.path("daytemp").asText(),
              "low",
              cast.path("nighttemp").asText(),
              "issuedAt",
              f.path("reporttime").asText(),
              "retrievedAt",
              Instant.now().toString());
    return Map.of("available", false, "reason", "数据源暂未提供该日预报", "date", date.toString());
  }
}

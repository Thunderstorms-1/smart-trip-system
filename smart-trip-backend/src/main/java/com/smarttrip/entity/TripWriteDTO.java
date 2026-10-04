package com.smarttrip.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Whitelist for editable fields. Ownership, deletion, totals and day dates are server-owned. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TripWriteDTO(
    String title,
    String destination,
    LocalDate startDate,
    String tripStyle,
    String travelGroup,
    String summary,
    Long version,
    List<DayWrite> days) {
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record DayWrite(Long id, String dayTheme, String daySummary, List<ItemWrite> items) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ItemWrite(
      Long id,
      String itemType,
      String itemName,
      String startTime,
      BigDecimal costTimeHours,
      BigDecimal costEstimate,
      String transportTips,
      String aiTips,
      String address,
      BigDecimal longitude,
      BigDecimal latitude,
      String poiId,
      String coordinateSystem,
      String environmentType,
      Boolean scheduleLocked) {}

  public TripDetailVO toTrip() {
    var t = new TripDetailVO();
    t.setTitle(title);
    t.setDestination(destination);
    t.setStartDate(startDate);
    t.setTripStyle(tripStyle);
    t.setTravelGroup(travelGroup);
    t.setSummary(summary);
    t.setVersion(version);
    if (days == null) return t;
    var targetDays = new ArrayList<TripDayVO>();
    for (var source : days) {
      if (source == null) {
        targetDays.add(null);
        continue;
      }
      var d = new TripDayVO();
      d.setId(source.id());
      d.setDayTheme(source.dayTheme());
      d.setDaySummary(source.daySummary());
      var targetItems = new ArrayList<TripItem>();
      if (source.items() != null)
        for (var node : source.items()) {
          if (node == null) {
            targetItems.add(null);
            continue;
          }
          var i = new TripItem();
          i.setId(node.id());
          i.setItemType(node.itemType());
          i.setItemName(node.itemName());
          i.setStartTime(node.startTime());
          i.setCostTimeHours(node.costTimeHours());
          i.setCostEstimate(node.costEstimate());
          i.setTransportTips(node.transportTips());
          i.setAiTips(node.aiTips());
          i.setAddress(node.address());
          i.setLongitude(node.longitude());
          i.setLatitude(node.latitude());
          i.setPoiId(node.poiId());
          i.setCoordinateSystem(node.coordinateSystem());
          i.setEnvironmentType(node.environmentType());
          i.setScheduleLocked(node.scheduleLocked());
          targetItems.add(i);
        }
      d.setItems(targetItems);
      targetDays.add(d);
    }
    t.setDays(targetDays);
    return t;
  }
}

package com.smarttrip;

import static org.junit.jupiter.api.Assertions.*;

import com.smarttrip.common.BusinessException;
import com.smarttrip.entity.*;
import com.smarttrip.service.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class AiModificationGuardTests {
  private final WorkbenchService json = new WorkbenchService(null, null, null);

  private TripDetailVO base() {
    return json.decode(
        """
        {"id":1,"userId":10,"isPublic":0,"title":"固定预约","destination":"成都","startDate":"2026-10-10","days":[
          {"id":11,"items":[{"id":101,"itemName":"博物馆","itemType":"SCENIC","startTime":"09:00",
            "costTimeHours":2,"costEstimate":0,"scheduleLocked":true,"environmentType":"INDOOR",
            "poiId":"BOOKED","coordinateSystem":"GCJ02","longitude":104.06,"latitude":30.67,
            "address":"预约地址","aiTips":"预约备注","transportTips":"步行"},
            {"id":102,"itemName":"自由活动","itemType":"SCENIC","scheduleLocked":false}]},
          {"id":12,"items":[{"id":103,"itemName":"公园","itemType":"SCENIC","scheduleLocked":false}]}]}
        """);
  }

  enum Bypass {
    UNLOCK,
    DELETE,
    REPLACE_ID,
    REORDER,
    CROSS_DAY,
    DAY_ORDER,
    TIME,
    DURATION,
    NAME,
    TYPE,
    COST,
    NOTES,
    TRANSPORT,
    POI,
    ADDRESS,
    COORDINATE_SYSTEM,
    LONGITUDE,
    LATITUDE,
    ENVIRONMENT,
    DESTINATION,
    START_DATE,
    DUPLICATE_ITEM,
    FOREIGN_ITEM,
    FOREIGN_DAY,
    DUPLICATE_DAY,
    LOCK_UNLOCKED_NODE
  }

  @ParameterizedTest
  @EnumSource(Bypass.class)
  void cannotBypassFixedArrangement(Bypass bypass) {
    var base = base();
    var proposal = json.decode(json.encode(base));
    var items = proposal.getDays().get(0).getItems();
    var fixed = items.get(0);
    switch (bypass) {
      case UNLOCK -> fixed.setScheduleLocked(false);
      case DELETE -> items.remove(0);
      case REPLACE_ID -> fixed.setId(null);
      case REORDER -> Collections.swap(items, 0, 1);
      case CROSS_DAY -> {
        items.remove(0);
        proposal.getDays().get(1).getItems().add(fixed);
      }
      case DAY_ORDER -> Collections.swap(proposal.getDays(), 0, 1);
      case TIME -> fixed.setStartTime("15:00");
      case DURATION -> fixed.setCostTimeHours(BigDecimal.ONE);
      case NAME -> fixed.setItemName("其他地点");
      case TYPE -> fixed.setItemType("FOOD");
      case COST -> fixed.setCostEstimate(BigDecimal.ONE);
      case NOTES -> fixed.setAiTips("删除预约提示");
      case TRANSPORT -> fixed.setTransportTips("开车");
      case POI -> fixed.setPoiId("OTHER");
      case ADDRESS -> fixed.setAddress("其他地址");
      case COORDINATE_SYSTEM -> fixed.setCoordinateSystem("WGS84");
      case LONGITUDE -> fixed.setLongitude(BigDecimal.ONE);
      case LATITUDE -> fixed.setLatitude(BigDecimal.ONE);
      case ENVIRONMENT -> fixed.setEnvironmentType("OUTDOOR");
      case DESTINATION -> proposal.setDestination("北京");
      case START_DATE -> proposal.setStartDate(LocalDate.of(2026, 10, 11));
      case DUPLICATE_ITEM -> items.add(fixed);
      case FOREIGN_ITEM -> items.get(1).setId(999L);
      case FOREIGN_DAY -> proposal.getDays().get(1).setId(999L);
      case DUPLICATE_DAY -> proposal.getDays().add(proposal.getDays().get(1));
      case LOCK_UNLOCKED_NODE -> items.get(1).setScheduleLocked(true);
    }
    assertEquals(
        422,
        assertThrows(BusinessException.class, () -> AiModificationGuard.verify(base, proposal))
            .getCode());
    assertTrue(base.getDays().get(0).getItems().get(0).getScheduleLocked());
  }

  @Test
  void omittedOptionalMetadataIsRestoredFromSavedFixedNode() {
    var base = base();
    var proposal = json.decode(json.encode(base));
    var fixed = proposal.getDays().get(0).getItems().get(0);
    fixed.setScheduleLocked(null);
    fixed.setEnvironmentType(null);
    fixed.setPoiId(null);
    fixed.setCoordinateSystem(null);
    fixed.setLongitude(null);
    fixed.setLatitude(null);
    fixed.setAddress(null);
    fixed.setCostEstimate(null);
    fixed.setAiTips(null);
    fixed.setTransportTips(null);
    fixed.setCostTimeHours(new BigDecimal("2.00"));
    AiModificationGuard.verify(base, proposal);
    assertEquals(json.encode(base.getDays().get(0).getItems().get(0)), json.encode(fixed));
  }

  @Test
  void unlockedContentCanBeEditedAddedDeletedAndMovedWithoutChangingOwnership() {
    var base = base();
    var proposal = json.decode(json.encode(base));
    proposal.setTitle("自由修改预览");
    proposal.setUserId(999L);
    proposal.setIsPublic(1);
    var removed = proposal.getDays().get(0).getItems().remove(1);
    removed.setItemName("修改后的自由活动");
    removed.setScheduleLocked(null);
    proposal.getDays().get(1).getItems().clear();
    proposal.getDays().get(1).getItems().add(removed);
    var added = new TripItem();
    added.setItemName("新增节点");
    added.setItemType("FOOD");
    proposal.getDays().get(0).getItems().add(added);
    AiModificationGuard.verify(base, proposal);
    assertEquals("自由修改预览", proposal.getTitle());
    assertEquals(10L, proposal.getUserId());
    assertEquals(0, proposal.getIsPublic());
    assertFalse(removed.getScheduleLocked());
    assertEquals(
        json.encode(base.getDays().get(0).getItems().get(0)),
        json.encode(proposal.getDays().get(0).getItems().get(0)));
  }
}

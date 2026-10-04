package com.smarttrip;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.smarttrip.common.BusinessException;
import com.smarttrip.entity.*;
import com.smarttrip.repository.TripRepository;
import com.smarttrip.service.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class WorkbenchValidationTests {
  WorkbenchService service =
      new WorkbenchService(
          mock(JdbcTemplate.class), mock(TripRepository.class), mock(ResourceAccess.class));

  TripDetailVO trip() {
    var t = new TripDetailVO();
    t.setTitle("成都漫步");
    t.setDestination("成都");
    var d = new TripDayVO();
    var i = new TripItem();
    i.setItemName("宽窄巷子");
    i.setCostEstimate(new BigDecimal("25"));
    d.setItems(new ArrayList<>(List.of(i)));
    t.setDays(new ArrayList<>(List.of(d)));
    return t;
  }

  @Test
  void validatesHonestUnknownLocationAndSetsDefaultType() {
    var t = trip();
    service.validate(t);
    assertEquals("SCENIC", t.getDays().get(0).getItems().get(0).getItemType());
    assertNull(t.getDays().get(0).getItems().get(0).getLatitude());
  }

  @Test
  void negativeEstimateAndInvalidTimeAreRejected() {
    var t = trip();
    var i = t.getDays().get(0).getItems().get(0);
    i.setCostEstimate(new BigDecimal("-1"));
    assertThrows(BusinessException.class, () -> service.validate(t));
    i.setCostEstimate(BigDecimal.ZERO);
    i.setStartTime("25:99");
    assertThrows(BusinessException.class, () -> service.validate(t));
  }

  @Test
  void duplicateStableIdsCannotBeApplied() {
    var t = trip();
    var i = t.getDays().get(0).getItems().get(0);
    i.setId(1L);
    var j = new TripItem();
    j.setId(1L);
    j.setItemName("另一个地点");
    t.getDays().get(0).getItems().add(j);
    assertThrows(BusinessException.class, () -> service.validate(t));
  }

  @Test
  void unknownCoordinateSystemIsRejected() {
    var t = trip();
    var i = t.getDays().get(0).getItems().get(0);
    i.setLatitude(new BigDecimal("30"));
    i.setLongitude(new BigDecimal("104"));
    assertThrows(BusinessException.class, () -> service.validate(t));
    i.setCoordinateSystem("GCJ02");
    assertDoesNotThrow(() -> service.validate(t));
  }

  @Test
  void cloneClearsAllPrivateOwnershipAndStableIds() {
    var t = trip();
    t.setId(8L);
    t.setUserId(7L);
    t.setIsPublic(1);
    t.getDays().get(0).setId(9L);
    var i = t.getDays().get(0).getItems().get(0);
    i.setId(10L);
    i.setPoiId("B000TEST");
    service.clearIds(t);
    assertNull(t.getId());
    assertNull(t.getUserId());
    assertEquals(0, t.getIsPublic());
    assertNull(i.getId());
    assertEquals("B000TEST", i.getPoiId());
  }
}

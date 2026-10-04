package com.smarttrip;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.smarttrip.common.BusinessException;
import com.smarttrip.common.UserContext;
import com.smarttrip.entity.TripDetailVO;
import com.smarttrip.entity.TripItem;
import com.smarttrip.entity.TripMember;
import com.smarttrip.mapper.TripMemberMapper;
import com.smarttrip.repository.TripRepository;
import com.smarttrip.service.ResourceAccess;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ResourceAccessTests {
  TripRepository trips;
  TripMemberMapper members;
  ResourceAccess access;

  @BeforeEach
  void setup() {
    trips = mock(TripRepository.class);
    members = mock(TripMemberMapper.class);
    access = new ResourceAccess(trips, members);
    TripDetailVO trip = new TripDetailVO();
    trip.setId(10L);
    trip.setUserId(1L);
    when(trips.find(10L)).thenReturn(trip);
    UserContext.setUserId(1L);
  }

  @AfterEach
  void clean() {
    UserContext.clear();
  }

  @Test
  void ownerCanReadAndWrite() {
    assertEquals(10L, access.read(10L).getId());
    assertEquals(10L, access.write(10L).getId());
  }

  @Test
  void unauthenticatedAccessIsRejected() {
    UserContext.clear();
    assertEquals(401, assertThrows(BusinessException.class, () -> access.read(10L)).getCode());
  }

  @Test
  void viewerCanReadButCannotWrite() {
    UserContext.setUserId(2L);
    TripMember member = new TripMember();
    member.setUserId(2L);
    member.setRole("VIEWER");
    when(members.selectActiveMembersByTripId(10L)).thenReturn(List.of(member));
    assertNotNull(access.read(10L));
    assertEquals(403, assertThrows(BusinessException.class, () -> access.write(10L)).getCode());
  }

  @Test
  void strangerCannotReadPublicSourceDraft() {
    UserContext.setUserId(2L);
    when(members.selectActiveMembersByTripId(10L)).thenReturn(List.of());
    assertEquals(403, assertThrows(BusinessException.class, () -> access.read(10L)).getCode());
  }

  @Test
  void deletedNodeCannotBeEdited() {
    TripItem item = new TripItem();
    item.setId(3L);
    item.setTripId(10L);
    item.setIsDeleted(1);
    when(trips.item(3L)).thenReturn(item);
    assertEquals(404, assertThrows(BusinessException.class, () -> access.item(3L)).getCode());
  }
}

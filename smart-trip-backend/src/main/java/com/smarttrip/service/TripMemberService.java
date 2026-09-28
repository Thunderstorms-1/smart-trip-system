package com.smarttrip.service;

import com.smarttrip.entity.TripMember;
import java.util.List;

public interface TripMemberService {
    String generateInviteCode(Long tripId, Long currentUserId);
    Long joinTripByCode(String inviteCode, Long currentUserId);
    List<TripMember> getTripMembers(Long tripId);
    void removeMember(Long tripId, Long targetUserId, Long currentUserId);
}
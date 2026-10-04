package com.smarttrip.service.impl;

import com.smarttrip.entity.TripDetailVO;
import com.smarttrip.entity.TripMember;
import com.smarttrip.entity.User;
import com.smarttrip.mapper.TripMapper;
import com.smarttrip.mapper.TripMemberMapper;
import com.smarttrip.mapper.UserMapper;
import com.smarttrip.service.TripMemberService;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class TripMemberServiceImpl implements TripMemberService {

  private final TripMemberMapper tripMemberMapper;
  private final com.smarttrip.service.MembershipService membership;
  private final com.smarttrip.service.ResourceAccess access;
  private final TripMapper tripMapper;
  private final UserMapper userMapper;

  @Override
  public String generateInviteCode(Long tripId, Long currentUserId) {
    return (String) membership.invite(tripId, "EDITOR").get("inviteCode");
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public Long joinTripByCode(String inviteCode, Long currentUserId) {
    return membership.join(inviteCode);
  }

  @Override
  public List<TripMember> getTripMembers(Long tripId) {
    access.read(tripId);
    TripDetailVO trip = tripMapper.selectTripById(tripId);
    if (trip == null) return new ArrayList<>();

    List<TripMember> members = new ArrayList<>();

    // 1. 先加入创建者作为 OWNER
    User creator = userMapper.findById(trip.getUserId());
    TripMember owner = new TripMember();
    owner.setTripId(tripId);
    owner.setUserId(trip.getUserId());
    owner.setRole("OWNER");
    owner.setNicknameSnapshot(creator != null ? creator.getNickname() : "行程发起人");
    owner.setAvatarSnapshot(creator != null ? creator.getAvatar() : null);
    members.add(owner);

    // 2. 加入其他协作者
    List<TripMember> collaborators = tripMemberMapper.selectActiveMembersByTripId(tripId);
    if (collaborators != null) {
      for (TripMember m : collaborators) {
        if (!m.getUserId().equals(trip.getUserId())) {
          members.add(m);
        }
      }
    }
    return members;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void removeMember(Long tripId, Long targetUserId, Long currentUserId) {
    membership.remove(tripId, targetUserId);
  }
}

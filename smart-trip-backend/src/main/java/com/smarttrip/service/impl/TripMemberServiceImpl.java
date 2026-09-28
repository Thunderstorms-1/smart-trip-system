package com.smarttrip.service.impl;

import com.smarttrip.common.BusinessException;
import com.smarttrip.entity.TripDetailVO;
import com.smarttrip.entity.TripMember;
import com.smarttrip.entity.User;
import com.smarttrip.mapper.TripMapper;
import com.smarttrip.mapper.TripMemberMapper;
import com.smarttrip.mapper.UserMapper;
import com.smarttrip.service.TripMemberService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class TripMemberServiceImpl implements TripMemberService {

    private final TripMemberMapper tripMemberMapper;
    private final TripMapper tripMapper;
    private final UserMapper userMapper;

    @Override
    public String generateInviteCode(Long tripId, Long currentUserId) {
        TripDetailVO trip = tripMapper.selectTripById(tripId);
        if (trip == null) throw new BusinessException(404, "行程不存在");

        // 校验权限：仅创建者或协作者可生成邀请码
        boolean isCreator = trip.getUserId().equals(currentUserId);
        boolean isEditor = tripMemberMapper.checkUserIsEditor(tripId, currentUserId) > 0;
        if (!isCreator && !isEditor) {
            throw new BusinessException(403, "您无权邀请好友加入此行程");
        }

        // 格式: TRIP-{tripId}-{4位哈希校验码}
        int checksum = (tripId.toString() + "SMART_TRIP_SALT").hashCode() & 0xFFFF;
        return String.format("TRIP-%d-%04X", tripId, checksum);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long joinTripByCode(String inviteCode, Long currentUserId) {
        if (inviteCode == null || !inviteCode.startsWith("TRIP-")) {
            throw new BusinessException(400, "邀请口令格式不正确");
        }

        String[] parts = inviteCode.split("-");
        if (parts.length < 3) throw new BusinessException(400, "无效的邀请口令");

        Long tripId;
        try {
            tripId = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            throw new BusinessException(400, "口令解析失败");
        }

        // 校验口令合法性
        int expectedChecksum = (tripId.toString() + "SMART_TRIP_SALT").hashCode() & 0xFFFF;
        int actualChecksum = Integer.parseInt(parts[2], 16);
        if (expectedChecksum != actualChecksum) {
            throw new BusinessException(400, "邀请口令已失效或校验失败");
        }

        TripDetailVO trip = tripMapper.selectTripById(tripId);
        if (trip == null) throw new BusinessException(404, "目标行程不存在或已被删除");

        // 如果是创建者自己加入，直接放行
        if (trip.getUserId().equals(currentUserId)) {
            return tripId;
        }

        User user = userMapper.findById(currentUserId);
        if (user == null) throw new BusinessException(401, "用户未登录");

        // 插入或恢复协作者权限
        String nickname = user.getNickname() != null ? user.getNickname() : "同行探险家";
        String avatar = user.getAvatar();
        tripMemberMapper.insertOrReactivateMember(tripId, currentUserId, "EDITOR", nickname, avatar);

        log.info("用户 [{}] 成功通过口令加入行程 [{}] 成为协同编辑者", currentUserId, tripId);
        return tripId;
    }

    @Override
    public List<TripMember> getTripMembers(Long tripId) {
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
        TripDetailVO trip = tripMapper.selectTripById(tripId);
        if (trip == null) throw new BusinessException(404, "行程不存在");

        // 只有创建者可以踢人，或者协作者自己主动退出
        boolean isCreator = trip.getUserId().equals(currentUserId);
        boolean isSelf = targetUserId.equals(currentUserId);

        if (!isCreator && !isSelf) {
            throw new BusinessException(403, "您无权移除该成员");
        }

        tripMemberMapper.softDeleteMember(tripId, targetUserId);
        log.info("成员 [{}] 已退出/被移出行程 [{}]", targetUserId, tripId);
    }
}
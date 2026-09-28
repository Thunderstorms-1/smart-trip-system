package com.smarttrip.mapper;

import com.smarttrip.entity.TripMember;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface TripMemberMapper {

    // 1. 检查用户是否为该行程具有写权限的有效协作者 (EDITOR 或 OWNER)
    @Select("SELECT COUNT(*) FROM trip_member " +
            "WHERE trip_id = #{tripId} AND user_id = #{userId} " +
            "AND role IN ('OWNER', 'EDITOR') AND is_deleted = 0")
    int checkUserIsEditor(@Param("tripId") Long tripId, @Param("userId") Long userId);

    // 2. 查询行程的所有有效协作者成员列表
    @Select("SELECT * FROM trip_member WHERE trip_id = #{tripId} AND is_deleted = 0 ORDER BY id ASC")
    List<TripMember> selectActiveMembersByTripId(@Param("tripId") Long tripId);

    // 3. 插入新协作者或恢复已退出的协作者状态
    @Insert("INSERT INTO trip_member (trip_id, user_id, role, nickname_snapshot, avatar_snapshot, is_deleted) " +
            "VALUES (#{tripId}, #{userId}, #{role}, #{nicknameSnapshot}, #{avatarSnapshot}, 0) " +
            "ON DUPLICATE KEY UPDATE role = #{role}, is_deleted = 0, nickname_snapshot = #{nicknameSnapshot}, " +
            "avatar_snapshot = #{avatarSnapshot}, update_time = NOW()")
    int insertOrReactivateMember(@Param("tripId") Long tripId,
                                 @Param("userId") Long userId,
                                 @Param("role") String role,
                                 @Param("nicknameSnapshot") String nickname,
                                 @Param("avatarSnapshot") String avatar);

    // 4. 成员主动退出或被移除
    @Update("UPDATE trip_member SET is_deleted = 1, update_time = NOW() " +
            "WHERE trip_id = #{tripId} AND user_id = #{userId}")
    int softDeleteMember(@Param("tripId") Long tripId, @Param("userId") Long userId);
}
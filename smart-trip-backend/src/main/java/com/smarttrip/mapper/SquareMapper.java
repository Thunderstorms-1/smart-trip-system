package com.smarttrip.mapper;

import com.smarttrip.entity.SquareTripVO;
import com.smarttrip.entity.TripDayVO;
import com.smarttrip.entity.TripDetailVO;
import com.smarttrip.entity.TripItem;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface SquareMapper {

    // 1. 分页检索公开路书 (演示精简版 SQL，默认查最新 20 条公开路书)
    @Select("SELECT p.id, p.title, p.destination, p.total_days AS totalDays, " +
            "p.total_budget AS totalBudget, p.trip_style AS tripStyle, " +
            "p.like_count AS likeCount, p.clone_count AS cloneCount, " +
            "u.nickname AS authorNickname, u.avatar AS authorAvatar " +
            "FROM trip_plan p LEFT JOIN sys_user u ON p.user_id = u.id " +
            "WHERE p.is_deleted = 0 ORDER BY p.like_count DESC, p.create_time DESC LIMIT 20")
    List<SquareTripVO> selectSquareTrips();

    // 2. 插入新克隆的路书主表
    @Insert("INSERT INTO trip_plan (user_id, title, destination, total_days, total_budget, trip_style, travel_group, summary, is_public, is_ai_generated) " +
            "VALUES (#{userId}, #{title}, #{destination}, #{totalDays}, #{totalBudget}, #{tripStyle}, #{travelGroup}, #{summary}, 0, 0)")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertCloneTrip(TripDetailVO vo);

    // 3. 插入新克隆的日程天数
    @Insert("INSERT INTO trip_day (trip_id, day_index, day_theme, day_summary) " +
            "VALUES (#{tripId}, #{dayIndex}, #{dayTheme}, #{daySummary})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertCloneDay(TripDayVO day);

    // 4. 插入新克隆的节点
    @Insert("INSERT INTO trip_item (day_id, trip_id, item_type, item_name, start_time, cost_time_hours, cost_estimate, transport_tips, ai_tips, sort_order) " +
            "VALUES (#{dayId}, #{tripId}, #{itemType}, #{itemName}, #{startTime}, #{costTimeHours}, #{costEstimate}, #{transportTips}, #{aiTips}, #{sortOrder})")
    int insertCloneItem(TripItem item);

    // 5. 原路书克隆计数 +1
    @Update("UPDATE trip_plan SET clone_count = clone_count + 1 WHERE id = #{tripId}")
    int incrementCloneCount(@Param("tripId") Long tripId);
}
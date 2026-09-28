package com.smarttrip.mapper;

import com.smarttrip.entity.*;
import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Map;

@Mapper
public interface TripMapper {

    // =========================================================================
    // 【核心新增 1】：查询指定用户的所有有效个人行程（限定 is_deleted=0，按更新时间倒序）
    // =========================================================================
    @Select("SELECT id, user_id AS userId, title, destination, total_days AS totalDays, " +
            "total_budget AS totalBudget, trip_style AS tripStyle, is_public AS isPublic, " +
            "create_time AS createTime, update_time AS updateTime " +
            "FROM trip_plan WHERE user_id = #{userId} AND is_deleted = 0 ORDER BY update_time DESC")
    List<TripDetailVO> selectTripsByUserId(@Param("userId") Long userId);

    // =========================================================================
    // 【核心新增 2】：软删除整份路书主表（携带 user_id 双重防越权）
    // =========================================================================
    @Update("UPDATE trip_plan SET is_deleted = 1, update_time = NOW() " +
            "WHERE id = #{tripId} AND user_id = #{userId} AND is_deleted = 0")
    int softDeleteTripByIdAndUserId(@Param("tripId") Long tripId, @Param("userId") Long userId);

    // =========================================================================
    // 【核心新增 3】：级联软删除该路书下的所有日程天数
    // =========================================================================
    @Update("UPDATE trip_day SET is_deleted = 1, update_time = NOW() WHERE trip_id = #{tripId} AND is_deleted = 0")
    int softDeleteDaysByTripId(@Param("tripId") Long tripId);

    // =========================================================================
    // 【核心新增 4】：级联软删除该路书下的所有卡片节点
    // =========================================================================
    @Update("UPDATE trip_item SET is_deleted = 1, update_time = NOW() WHERE trip_id = #{tripId} AND is_deleted = 0")
    int softDeleteItemsByTripId(@Param("tripId") Long tripId);

    // --- 保留已有基础查询与管理方法 ---
    @Select("SELECT * FROM trip_plan WHERE id = #{tripId} AND is_deleted = 0 LIMIT 1")
    TripDetailVO selectTripById(@Param("tripId") Long tripId);

    @Select("SELECT * FROM trip_day WHERE trip_id = #{tripId} AND is_deleted = 0 ORDER BY day_index ASC")
    List<TripDayVO> selectDaysByTripId(@Param("tripId") Long tripId);

    @Select("SELECT * FROM trip_day WHERE id = #{dayId} AND is_deleted = 0 LIMIT 1")
    TripDayVO selectDayById(@Param("dayId") Long dayId);

    @Select("SELECT COUNT(*) FROM trip_day WHERE trip_id = #{tripId} AND is_deleted = 0")
    int countActiveDaysByTripId(@Param("tripId") Long tripId);

    @Select("SELECT COALESCE(MAX(day_index), 0) FROM trip_day WHERE trip_id = #{tripId} AND is_deleted = 0")
    int selectMaxDayIndexByTripId(@Param("tripId") Long tripId);

    @Insert("INSERT INTO trip_day (trip_id, day_index, day_theme, day_summary, is_deleted) " +
            "VALUES (#{tripId}, #{dayIndex}, #{dayTheme}, #{daySummary}, 0)")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertTripDay(TripDayVO day);

    @Update("UPDATE trip_day SET is_deleted = 1, update_time = NOW() WHERE id = #{dayId} AND is_deleted = 0")
    int softDeleteDayById(@Param("dayId") Long dayId);

    @Update("UPDATE trip_day SET day_index = #{dayIndex}, update_time = NOW() WHERE id = #{dayId}")
    int updateDayIndex(@Param("dayId") Long dayId, @Param("dayIndex") Integer dayIndex);

    @Update("UPDATE trip_plan SET total_days = #{totalDays}, update_time = NOW() WHERE id = #{tripId}")
    int updateTripTotalDays(@Param("tripId") Long tripId, @Param("totalDays") Integer totalDays);

    @Select("SELECT * FROM trip_item WHERE day_id = #{dayId} AND is_deleted = 0 ORDER BY sort_order ASC, start_time ASC")
    List<TripItem> selectItemsByDayId(@Param("dayId") Long dayId);

    @Select("SELECT * FROM trip_item WHERE id = #{itemId} LIMIT 1")
    TripItem selectItemById(@Param("itemId") Long itemId);

    @Select("SELECT * FROM trip_item WHERE trip_id = #{tripId} AND is_deleted = 0 AND cost_estimate > 0 ORDER BY sort_order ASC")
    List<TripItem> selectCostItemsByTripId(@Param("tripId") Long tripId);

    @Select("SELECT COALESCE(MAX(sort_order), 0) FROM trip_item WHERE day_id = #{dayId} AND is_deleted = 0")
    int selectMaxSortOrderByDayId(@Param("dayId") Long dayId);

    @Insert("INSERT INTO trip_item (day_id, trip_id, item_type, item_name, start_time, " +
            "cost_time_hours, cost_estimate, transport_tips, ai_tips, sort_order, is_deleted) " +
            "VALUES (#{dayId}, #{tripId}, #{itemType}, #{itemName}, #{startTime}, " +
            "#{costTimeHours}, #{costEstimate}, #{transportTips}, #{aiTips}, #{sortOrder}, 0)")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertSingleTripItem(TripItem item);

    @Update("UPDATE trip_item SET item_name = #{itemName}, start_time = #{startTime}, " +
            "cost_time_hours = #{costTimeHours}, cost_estimate = #{costEstimate}, " +
            "ai_tips = #{aiTips}, is_deleted = #{isDeleted}, update_time = NOW() WHERE id = #{id}")
    int updateTripItem(TripItem item);

    @Update("UPDATE trip_item SET is_deleted = 1, update_time = NOW() WHERE id = #{itemId} AND is_deleted = 0")
    int softDeleteItemById(@Param("itemId") Long itemId);

    @Update("UPDATE trip_item SET sort_order = #{sortOrder}, update_time = NOW() WHERE id = #{itemId}")
    int updateItemSortOrder(@Param("itemId") Long itemId, @Param("sortOrder") Integer sortOrder);

    @Delete("DELETE FROM trip_item WHERE day_id = #{dayId}")
    int deleteItemsByDayId(@Param("dayId") Long dayId);

    @Update("UPDATE trip_plan SET total_budget = (" +
            "   SELECT COALESCE(SUM(cost_estimate), 0.00) " +
            "   FROM trip_item " +
            "   WHERE trip_id = #{tripId} AND is_deleted = 0" +
            "), update_time = NOW() WHERE id = #{tripId} AND is_deleted = 0")
    int recalculateAndSyncTotalCost(@Param("tripId") Long tripId);

    @Update("UPDATE trip_item SET is_deleted = 1, update_time = NOW() WHERE day_id = #{dayId} AND is_deleted = 0")
    int softDeleteItemsByDayId(@Param("dayId") Long dayId);

    // =========================================================================
    // 【足迹大屏聚合 1】：汇总当前用户的总路书数、总天数、总预算
    // =========================================================================
    @Select("SELECT COUNT(*) AS totalTrips, COALESCE(SUM(total_days), 0) AS totalDays, " +
            "COALESCE(SUM(total_budget), 0.00) AS totalBudget " +
            "FROM trip_plan WHERE user_id = #{userId} AND is_deleted = 0")
    Map<String, Object> selectUserTripKpis(@Param("userId") Long userId);

    // =========================================================================
    // 【足迹大屏聚合 2】：按目的地分组统计点亮城市及规划频次
    // =========================================================================
    @Select("SELECT destination, COUNT(*) AS count FROM trip_plan " +
            "WHERE user_id = #{userId} AND is_deleted = 0 GROUP BY destination")
    List<Map<String, Object>> selectUserCityStats(@Param("userId") Long userId);

    // =========================================================================
    // 【足迹大屏聚合 3】：按旅行风格偏好分组统计权重
    // =========================================================================
    @Select("SELECT trip_style AS style, COUNT(*) AS count FROM trip_plan " +
            "WHERE user_id = #{userId} AND is_deleted = 0 GROUP BY trip_style")
    List<Map<String, Object>> selectUserStyleStats(@Param("userId") Long userId);
}
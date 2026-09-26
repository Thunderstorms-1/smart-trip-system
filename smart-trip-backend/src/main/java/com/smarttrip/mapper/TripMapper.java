package com.smarttrip.mapper;

import com.smarttrip.entity.TripDayVO;
import com.smarttrip.entity.TripDetailVO;
import com.smarttrip.entity.TripItem;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface TripMapper {

    // 1. 查询路书主表
    @Select("SELECT * FROM trip_plan WHERE id = #{tripId} AND is_deleted = 0 LIMIT 1")
    TripDetailVO selectTripById(@Param("tripId") Long tripId);

    // 2. 查询未删除的天数列表
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

    @Update("UPDATE trip_item SET is_deleted = 1, update_time = NOW() WHERE day_id = #{dayId} AND is_deleted = 0")
    int softDeleteItemsByDayId(@Param("dayId") Long dayId);

    @Update("UPDATE trip_day SET day_index = #{dayIndex}, update_time = NOW() WHERE id = #{dayId}")
    int updateDayIndex(@Param("dayId") Long dayId, @Param("dayIndex") Integer dayIndex);

    @Update("UPDATE trip_plan SET total_days = #{totalDays}, update_time = NOW() WHERE id = #{tripId}")
    int updateTripTotalDays(@Param("tripId") Long tripId, @Param("totalDays") Integer totalDays);

    // 3. 严格按排序号升序排布卡片
    @Select("SELECT * FROM trip_item WHERE day_id = #{dayId} AND is_deleted = 0 ORDER BY sort_order ASC, start_time ASC")
    List<TripItem> selectItemsByDayId(@Param("dayId") Long dayId);

    @Select("SELECT * FROM trip_item WHERE id = #{itemId} LIMIT 1")
    TripItem selectItemById(@Param("itemId") Long itemId);

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

    // 4. 【排序核心】：更新单个卡片的 sort_order
    @Update("UPDATE trip_item SET sort_order = #{sortOrder}, update_time = NOW() WHERE id = #{itemId} AND is_deleted = 0")
    int updateItemSortOrder(@Param("itemId") Long itemId, @Param("sortOrder") Integer sortOrder);

    @Delete("DELETE FROM trip_item WHERE day_id = #{dayId}")
    int deleteItemsByDayId(@Param("dayId") Long dayId);

    @Update("UPDATE trip_plan SET total_budget = (" +
            "   SELECT COALESCE(SUM(cost_estimate), 0.00) " +
            "   FROM trip_item " +
            "   WHERE trip_id = #{tripId} AND is_deleted = 0" +
            "), update_time = NOW() WHERE id = #{tripId} AND is_deleted = 0")
    int recalculateAndSyncTotalCost(@Param("tripId") Long tripId);

    // 查询某行程下所有未删除且有预估花费 (> 0) 的卡片
    @Select("SELECT * FROM trip_item WHERE trip_id = #{tripId} AND is_deleted = 0 AND cost_estimate > 0 ORDER BY sort_order ASC")
    List<TripItem> selectCostItemsByTripId(@Param("tripId") Long tripId);
}
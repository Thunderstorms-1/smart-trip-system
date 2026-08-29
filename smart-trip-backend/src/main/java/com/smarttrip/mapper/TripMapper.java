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

    // 2. 查询天数列表
    @Select("SELECT * FROM trip_day WHERE trip_id = #{tripId} AND is_deleted = 0 ORDER BY day_index ASC")
    List<TripDayVO> selectDaysByTripId(@Param("tripId") Long tripId);

    // 3. 【核心修复 1】：严格按照时间 (start_time ASC) 升序排！彻底杜绝时间倒流错乱！
    @Select("SELECT * FROM trip_item WHERE day_id = #{dayId} AND is_deleted = 0 ORDER BY start_time ASC, sort_order ASC")
    List<TripItem> selectItemsByDayId(@Param("dayId") Long dayId);

    // 4. 【核心修复 2】：补齐 is_deleted 字段，确保微调时旧卡片能被真正软删除！
    @Update("UPDATE trip_item SET item_name = #{itemName}, start_time = #{startTime}, " +
            "cost_time_hours = #{costTimeHours}, cost_estimate = #{costEstimate}, " +
            "ai_tips = #{aiTips}, is_deleted = #{isDeleted}, update_time = NOW() WHERE id = #{id}")
    int updateTripItem(TripItem item);

    // 5. 拖拽排序更新
    @Update("UPDATE trip_item SET sort_order = #{sortOrder}, update_time = NOW() WHERE id = #{itemId}")
    int updateItemSortOrder(@Param("itemId") Long itemId, @Param("sortOrder") Integer sortOrder);

    // 6. 物理清空某天下的所有卡片（微调专用，100% 干净，绝不残留旧卡片！）
    @Delete("DELETE FROM trip_item WHERE day_id = #{dayId}")
    int deleteItemsByDayId(@Param("dayId") Long dayId);
}
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

    // 3. 严格按时间升序排布卡片
    @Select("SELECT * FROM trip_item WHERE day_id = #{dayId} AND is_deleted = 0 ORDER BY start_time ASC, sort_order ASC")
    List<TripItem> selectItemsByDayId(@Param("dayId") Long dayId);

    // 4. 根据 itemId 查询单条卡片明细（用于获取其所属的 tripId）
    @Select("SELECT * FROM trip_item WHERE id = #{itemId} LIMIT 1")
    TripItem selectItemById(@Param("itemId") Long itemId);

    // 5. 更新卡片节点明细
    @Update("UPDATE trip_item SET item_name = #{itemName}, start_time = #{startTime}, " +
            "cost_time_hours = #{costTimeHours}, cost_estimate = #{costEstimate}, " +
            "ai_tips = #{aiTips}, is_deleted = #{isDeleted}, update_time = NOW() WHERE id = #{id}")
    int updateTripItem(TripItem item);

    // 6. 软删除单张卡片节点
    @Update("UPDATE trip_item SET is_deleted = 1, update_time = NOW() WHERE id = #{itemId} AND is_deleted = 0")
    int softDeleteItemById(@Param("itemId") Long itemId);

    // 7. 拖拽排序更新
    @Update("UPDATE trip_item SET sort_order = #{sortOrder}, update_time = NOW() WHERE id = #{itemId}")
    int updateItemSortOrder(@Param("itemId") Long itemId, @Param("sortOrder") Integer sortOrder);

    // 8. 物理清空某天下的所有卡片（AI 重排微调使用）
    @Delete("DELETE FROM trip_item WHERE day_id = #{dayId}")
    int deleteItemsByDayId(@Param("dayId") Long dayId);

    // =========================================================================
    // 【核心新增】：子项金额原子聚合回写主表 SQL (杜绝并发冲突与 NULL 异常)
    // 兼容 total_budget / estimated_total_cost 字段
    // =========================================================================
    @Update("UPDATE trip_plan SET total_budget = (" +
            "   SELECT COALESCE(SUM(cost_estimate), 0.00) " +
            "   FROM trip_item " +
            "   WHERE trip_id = #{tripId} AND is_deleted = 0" +
            "), update_time = NOW() WHERE id = #{tripId} AND is_deleted = 0")
    int recalculateAndSyncTotalCost(@Param("tripId") Long tripId);
}
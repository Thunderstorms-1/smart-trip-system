package com.smarttrip.mapper;

import com.smarttrip.entity.SquareQueryDTO;
import com.smarttrip.entity.SquareTripVO;
import com.smarttrip.entity.TripDayVO;
import com.smarttrip.entity.TripDetailVO;
import com.smarttrip.entity.TripItem;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface SquareMapper {

    // =========================================================================
    // 【保留原有 1】：旧版查广场列表（完全保留，防止老接口报错）
    // =========================================================================
    @Select("SELECT p.id, p.title, p.destination, p.total_days AS totalDays, " +
            "p.total_budget AS totalBudget, p.trip_style AS tripStyle, " +
            "p.like_count AS likeCount, p.clone_count AS cloneCount, " +
            "u.nickname AS authorNickname, u.avatar AS authorAvatar " +
            "FROM trip_plan p LEFT JOIN sys_user u ON p.user_id = u.id " +
            "WHERE p.is_deleted = 0 ORDER BY p.like_count DESC, p.create_time DESC LIMIT 20")
    List<SquareTripVO> selectSquareTrips();

    // =========================================================================
    // 【保留原有 2、3、4、5】：AI 生成落库与一键克隆核心方法（100% 原样保留，绝无破坏）
    // =========================================================================
    @Insert("INSERT INTO trip_plan (user_id, title, destination, total_days, total_budget, trip_style, travel_group, summary, is_public, is_ai_generated) " +
            "VALUES (#{userId}, #{title}, #{destination}, #{totalDays}, #{totalBudget}, #{tripStyle}, #{travelGroup}, #{summary}, 0, 0)")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertCloneTrip(TripDetailVO vo);

    @Insert("INSERT INTO trip_day (trip_id, day_index, day_theme, day_summary) " +
            "VALUES (#{tripId}, #{dayIndex}, #{dayTheme}, #{daySummary})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertCloneDay(TripDayVO day);

    @Insert("INSERT INTO trip_item (day_id, trip_id, item_type, item_name, start_time, cost_time_hours, cost_estimate, transport_tips, ai_tips, sort_order) " +
            "VALUES (#{dayId}, #{tripId}, #{itemType}, #{itemName}, #{startTime}, #{costTimeHours}, #{costEstimate}, #{transportTips}, #{aiTips}, #{sortOrder})")
    int insertCloneItem(TripItem item);

    @Update("UPDATE trip_plan SET clone_count = clone_count + 1 WHERE id = #{tripId}")
    int incrementCloneCount(@Param("tripId") Long tripId);


    // =========================================================================
    // 【全新追加 1】：多条件复合统计总数（支持关键词、风格、天数区间）
    // =========================================================================
    @Select("<script>" +
            "SELECT COUNT(*) FROM trip_plan p " +
            "WHERE p.is_public = 1 AND p.is_deleted = 0 " +
            "<if test='q.keyword != null and q.keyword != \"\"'>" +
            "  AND (p.destination LIKE CONCAT('%', #{q.keyword}, '%') OR p.title LIKE CONCAT('%', #{q.keyword}, '%')) " +
            "</if>" +
            "<if test='q.travelStyle != null and q.travelStyle != \"\" and q.travelStyle != \"全部\"'>" +
            "  AND p.trip_style = #{q.travelStyle} " +
            "</if>" +
            "<if test='q.minDays != null'>" +
            "  AND p.total_days &gt;= #{q.minDays} " +
            "</if>" +
            "<if test='q.maxDays != null'>" +
            "  AND p.total_days &lt;= #{q.maxDays} " +
            "</if>" +
            "</script>")
    long countSquareTrips(@Param("q") SquareQueryDTO query);

    // =========================================================================
    // 【全新追加 2】：多条件复合动态排序与分页查询列表
    // =========================================================================
    @Select("<script>" +
            "SELECT p.id, p.title, p.destination, p.total_days AS totalDays, " +
            "p.total_budget AS totalBudget, p.trip_style AS tripStyle, " +
            "p.like_count AS likeCount, p.clone_count AS cloneCount, p.create_time AS createTime, " +
            "COALESCE(u.nickname, '旅行探险家') AS authorNickname, u.avatar AS authorAvatar " +
            "FROM trip_plan p " +
            "LEFT JOIN sys_user u ON p.user_id = u.id " +
            "WHERE p.is_public = 1 AND p.is_deleted = 0 " +
            "<if test='q.keyword != null and q.keyword != \"\"'>" +
            "  AND (p.destination LIKE CONCAT('%', #{q.keyword}, '%') OR p.title LIKE CONCAT('%', #{q.keyword}, '%')) " +
            "</if>" +
            "<if test='q.travelStyle != null and q.travelStyle != \"\" and q.travelStyle != \"全部\"'>" +
            "  AND p.trip_style = #{q.travelStyle} " +
            "</if>" +
            "<if test='q.minDays != null'>" +
            "  AND p.total_days &gt;= #{q.minDays} " +
            "</if>" +
            "<if test='q.maxDays != null'>" +
            "  AND p.total_days &lt;= #{q.maxDays} " +
            "</if>" +
            "<choose>" +
            "  <when test='q.sortType == \"HOT\"'>" +
            "    ORDER BY p.like_count DESC, p.create_time DESC " +
            "  </when>" +
            "  <when test='q.sortType == \"BUDGET_ASC\"'>" +
            "    ORDER BY p.total_budget ASC, p.create_time DESC " +
            "  </when>" +
            "  <otherwise>" +
            "    ORDER BY p.create_time DESC " +
            "  </otherwise>" +
            "</choose>" +
            "LIMIT #{offset}, #{q.pageSize}" +
            "</script>")
    List<SquareTripVO> selectSquareTripsPaged(@Param("q") SquareQueryDTO query, @Param("offset") int offset);
}
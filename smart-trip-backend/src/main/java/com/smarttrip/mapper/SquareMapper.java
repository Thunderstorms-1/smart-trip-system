package com.smarttrip.mapper;

import com.smarttrip.entity.*;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface SquareMapper {

    // =========================================================================
    // 【核心新增】：从数据库动态拉取官方精选推荐路线 (命中 idx_featured_del 联合索引)
    // =========================================================================
    @Select("SELECT " +
            "   p.id, " +
            "   p.title, " +
            "   p.destination, " +
            "   p.total_days AS totalDays, " +
            "   p.total_budget AS totalBudget, " +
            "   p.trip_style AS tripStyle, " +
            "   p.cover_image AS coverImage, " +
            "   p.summary AS highlightDesc, " +
            "   p.clone_count AS cloneCount, " +
            "   CONCAT('🎯 ', p.trip_style) AS themeTag " +
            "FROM trip_plan p " +
            "WHERE p.is_featured = 1 AND p.is_deleted = 0 " +
            "ORDER BY p.like_count DESC, p.create_time DESC")
    List<FeaturedThemeVO> selectFeaturedThemedTrips();

    // --- 保留已有广场快照检索与克隆核心方法 ---
    @Select("<script>" +
            "SELECT COUNT(*) FROM square_post p " +
            "WHERE p.is_deleted = 0 " +
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

    @Select("<script>" +
            "SELECT p.id, p.title, p.destination, p.total_days AS totalDays, " +
            "p.total_budget AS totalBudget, p.trip_style AS tripStyle, " +
            "p.like_count AS likeCount, p.clone_count AS cloneCount, p.create_time AS createTime, " +
            "p.author_name AS authorNickname, p.author_avatar AS authorAvatar " +
            "FROM square_post p " +
            "WHERE p.is_deleted = 0 " +
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

    @Select("SELECT * FROM square_post WHERE trip_id = #{tripId} LIMIT 1")
    SquarePost selectPostByTripId(@Param("tripId") Long tripId);

    @Select("SELECT * FROM square_post WHERE id = #{postId} AND is_deleted = 0 LIMIT 1")
    SquarePost selectPostById(@Param("postId") Long postId);

    @Insert("INSERT INTO square_post (trip_id, user_id, author_name, author_avatar, title, destination, " +
            "total_days, total_budget, trip_style, cover_image, summary, snapshot_json, is_deleted) " +
            "VALUES (#{tripId}, #{userId}, #{authorName}, #{authorAvatar}, #{title}, #{destination}, " +
            "#{totalDays}, #{totalBudget}, #{tripStyle}, #{coverImage}, #{summary}, #{snapshotJson}, 0)")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertPost(SquarePost post);

    @Update("UPDATE square_post SET author_name = #{authorName}, author_avatar = #{authorAvatar}, " +
            "title = #{title}, destination = #{destination}, total_days = #{totalDays}, " +
            "total_budget = #{totalBudget}, trip_style = #{tripStyle}, summary = #{summary}, " +
            "snapshot_json = #{snapshotJson}, is_deleted = 0, update_time = NOW() " +
            "WHERE trip_id = #{tripId}")
    int updatePostSnapshot(SquarePost post);

    @Update("UPDATE square_post SET is_deleted = 1, update_time = NOW() WHERE trip_id = #{tripId}")
    int softDeletePostByTripId(@Param("tripId") Long tripId);

    @Update("UPDATE trip_plan SET is_public = #{isPublic}, update_time = NOW() WHERE id = #{tripId}")
    int updateTripPublicStatus(@Param("tripId") Long tripId, @Param("isPublic") Integer isPublic);

    @Update("UPDATE square_post SET clone_count = clone_count + 1 WHERE id = #{postId}")
    int incrementPostCloneCount(@Param("postId") Long postId);

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
}
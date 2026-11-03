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

}
package com.smarttrip.mapper;

import com.smarttrip.entity.TripExpense;
import com.smarttrip.entity.TripPackingItem;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.*;

@Mapper
public interface ToolMapper {
  @Select("SELECT trip_id FROM trip_packing_item WHERE id = #{itemId} AND is_deleted = 0")
  Long selectPackingTripId(@Param("itemId") Long itemId);

  // ========== 1. 记账相关 SQL ==========
  @Insert(
      "INSERT INTO trip_expense (trip_id, user_id, category, amount, expense_name, expense_date,"
          + " remark) VALUES (#{tripId}, #{userId}, #{category}, #{amount}, #{expenseName},"
          + " #{expenseDate}, #{remark})")
  @Options(useGeneratedKeys = true, keyProperty = "id")
  int insertExpense(TripExpense expense);

  @Select(
      "SELECT * FROM trip_expense WHERE trip_id = #{tripId} AND is_deleted = 0 ORDER BY"
          + " expense_date DESC, create_time DESC")
  List<TripExpense> selectExpensesByTripId(@Param("tripId") Long tripId);

  // 聚合查询：按分类统计已花金额
  @Select(
      "SELECT category, SUM(amount) AS totalAmount FROM trip_expense "
          + "WHERE trip_id = #{tripId} AND is_deleted = 0 GROUP BY category")
  List<Map<String, Object>> selectCategoryStats(@Param("tripId") Long tripId);

  // 统计总花费
  @Select(
      "SELECT COALESCE(SUM(amount), 0) FROM trip_expense WHERE trip_id = #{tripId} AND is_deleted ="
          + " 0")
  BigDecimal selectTotalSpent(@Param("tripId") Long tripId);

  // ========== 2. 行李清单相关 SQL ==========
  @Select(
      "SELECT * FROM trip_packing_item WHERE trip_id = #{tripId} AND is_deleted = 0 ORDER BY id"
          + " ASC")
  List<TripPackingItem> selectPackingItemsByTripId(@Param("tripId") Long tripId);

  @Insert(
      "<script>"
          + "INSERT INTO trip_packing_item (trip_id, category, item_name, is_checked) VALUES "
          + "<foreach collection='list' item='item' separator=','>"
          + "(#{item.tripId}, #{item.category}, #{item.itemName}, 0)"
          + "</foreach>"
          + "</script>")
  int batchInsertPackingItems(@Param("list") List<TripPackingItem> list);

  @Update(
      "UPDATE trip_packing_item SET is_checked = #{isChecked}, update_time = NOW() WHERE id = #{id}"
          + " AND is_deleted = 0")
  int updatePackingItemCheckStatus(@Param("id") Long id, @Param("isChecked") Integer isChecked);

  // 批量插入记账流水记录
  @Insert(
      "<script>INSERT INTO trip_expense (trip_id, user_id, category, amount, expense_name,"
          + " expense_date, remark) VALUES <foreach collection='list' item='e'"
          + " separator=','>(#{e.tripId}, #{e.userId}, #{e.category}, #{e.amount},"
          + " #{e.expenseName}, #{e.expenseDate}, #{e.remark})</foreach></script>")
  int batchInsertExpenses(@Param("list") List<TripExpense> list);
}

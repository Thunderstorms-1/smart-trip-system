package com.smarttrip.service;

import com.smarttrip.common.*;
import com.smarttrip.entity.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TravelToolsService {
  private final JdbcTemplate db;
  private final ResourceAccess access;
  private final WorkbenchService trips;

  public TravelToolsService(JdbcTemplate db, ResourceAccess access, WorkbenchService trips) {
    this.db = db;
    this.access = access;
    this.trips = trips;
  }

  public Map<String, Object> expenses(Long id) {
    var trip = access.read(id);
    var rows =
        db.queryForList(
            "SELECT id,category,amount,expense_name AS expenseName,expense_date AS"
                + " expenseDate,remark,source_item_id AS sourceItemId FROM trip_expense WHERE"
                + " trip_id=? AND is_deleted=0 ORDER BY expense_date DESC,id DESC",
            id);
    BigDecimal actual =
        rows.stream()
            .map(r -> (BigDecimal) r.get("amount"))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    return Map.of(
        "items",
        rows,
        "actual",
        actual,
        "estimated",
        trip.getTotalBudget() == null ? BigDecimal.ZERO : trip.getTotalBudget());
  }

  private void valid(TripExpense e) {
    if (e == null) throw new BusinessException(400, "缺少费用信息");
    if (e.getExpenseName() == null
        || e.getExpenseName().isBlank()
        || e.getExpenseName().length() > 100
        || e.getExpenseDate() == null) throw new BusinessException(400, "费用名称和日期必填");
    if (e.getAmount() == null
        || e.getAmount().signum() < 0
        || e.getAmount().compareTo(new BigDecimal("9999999")) > 0)
      throw new BusinessException(400, "金额无效");
    e.setAmount(e.getAmount().setScale(2, java.math.RoundingMode.HALF_UP));
    if (e.getCategory() == null
        || !Set.of("CATERING", "TRAFFIC", "HOTEL", "TICKET", "SHOPPING", "OTHER")
            .contains(e.getCategory())) throw new BusinessException(400, "费用分类无效");
    if (e.getRemark() != null && e.getRemark().length() > 255)
      throw new BusinessException(400, "备注最多255字");
  }

  @Transactional
  public void add(Long trip, TripExpense e, String requestKey, Long source) {
    access.write(trip);
    valid(e);
    if (requestKey == null || !requestKey.matches("[A-Za-z0-9_-]{8,80}"))
      throw new BusinessException(400, "缺少有效的请求标识");
    if (source != null) {
      var item = access.item(source);
      if (!Objects.equals(item.getTripId(), trip)) throw new BusinessException(403, "费用来源节点不属于该行程");
    }
    db.update(
        "INSERT INTO"
            + " trip_expense(trip_id,user_id,category,amount,expense_name,expense_date,remark,source_item_id,request_key,is_deleted)"
            + " VALUES(?,?,?,?,?,?,?,?,?,0) ON DUPLICATE KEY UPDATE"
            + " request_key=VALUES(request_key)",
        trip,
        access.userId(),
        e.getCategory(),
        e.getAmount(),
        e.getExpenseName(),
        e.getExpenseDate(),
        e.getRemark(),
        source,
        requestKey);
    var stored =
        db.queryForMap(
            "SELECT amount,expense_name,expense_date,category FROM trip_expense WHERE trip_id=? AND"
                + " request_key=?",
            trip,
            requestKey);
    if (((BigDecimal) stored.get("amount")).compareTo(e.getAmount()) != 0
        || !Objects.equals(stored.get("expense_name"), e.getExpenseName())
        || !Objects.equals(stored.get("category"), e.getCategory())
        || !stored.get("expense_date").toString().equals(e.getExpenseDate().toString()))
      throw new BusinessException(409, "同一请求标识不能用于不同账单");
  }

  private Long expenseTrip(Long id) {
    var rows = db.queryForList("SELECT trip_id FROM trip_expense WHERE id=? AND is_deleted=0", id);
    if (rows.isEmpty()) throw new BusinessException(404, "账单不存在");
    Long trip = ((Number) rows.get(0).get("trip_id")).longValue();
    access.write(trip);
    return trip;
  }

  public void edit(Long id, TripExpense e) {
    expenseTrip(id);
    valid(e);
    db.update(
        "UPDATE trip_expense SET category=?,amount=?,expense_name=?,expense_date=?,remark=? WHERE"
            + " id=?",
        e.getCategory(),
        e.getAmount(),
        e.getExpenseName(),
        e.getExpenseDate(),
        e.getRemark(),
        id);
  }

  public void deleteExpense(Long id) {
    expenseTrip(id);
    db.update("UPDATE trip_expense SET is_deleted=1 WHERE id=?", id);
  }

  public List<Map<String, Object>> packing(Long id) {
    access.read(id);
    return db.queryForList(
        "SELECT id,category,item_name AS itemName,is_checked AS isChecked FROM trip_packing_item"
            + " WHERE trip_id=? AND is_deleted=0 ORDER BY id",
        id);
  }

  public void addPacking(Long id, String name) {
    access.write(id);
    if (name == null || name.isBlank() || name.length() > 100)
      throw new BusinessException(400, "物品名称应为1至100字");
    db.update(
        "INSERT INTO trip_packing_item(trip_id,category,item_name,is_checked,is_deleted)"
            + " VALUES(?,'COMMON',?,0,0)",
        id,
        name.trim());
  }

  @Transactional
  public void template(Long id) {
    access.write(id);
    db.queryForObject("SELECT id FROM trip_plan WHERE id=? FOR UPDATE", Long.class, id);
    for (String name : List.of("身份证", "手机与充电器", "常用药品", "换洗衣物")) {
      if (db.queryForObject(
              "SELECT COUNT(*) FROM trip_packing_item WHERE trip_id=? AND item_name=? AND"
                  + " is_deleted=0",
              Integer.class,
              id,
              name)
          == 0) addPacking(id, name);
    }
  }

  public void packingItem(Long id, Boolean checked, boolean deleted) {
    var rows =
        db.queryForList("SELECT trip_id FROM trip_packing_item WHERE id=? AND is_deleted=0", id);
    if (rows.isEmpty()) throw new BusinessException(404, "清单条目不存在");
    access.write(((Number) rows.get(0).get("trip_id")).longValue());
    if (deleted) db.update("UPDATE trip_packing_item SET is_deleted=1 WHERE id=?", id);
    else {
      if (checked == null) throw new BusinessException(400, "缺少状态");
      db.update("UPDATE trip_packing_item SET is_checked=? WHERE id=?", checked ? 1 : 0, id);
    }
  }
}

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

  private String expenseHash(
      Long trip,
      Long user,
      String category,
      BigDecimal amount,
      String name,
      String date,
      String remark,
      Long source) {
    return CommunityService.hash(
        trips.encode(
            Arrays.asList(
                "EXPENSE_V1",
                trip,
                user,
                category,
                amount.setScale(2).toPlainString(),
                name,
                date,
                remark,
                source)));
  }

  @Transactional
  public void add(Long trip, TripExpense e, String requestKey, Long source) {
    access.writeLocked(trip);
    valid(e);
    if (requestKey == null || !requestKey.matches("[A-Za-z0-9_-]{8,80}"))
      throw new BusinessException(400, "缺少有效的请求标识");
    String hash =
        expenseHash(
            trip,
            access.userId(),
            e.getCategory(),
            e.getAmount(),
            e.getExpenseName(),
            e.getExpenseDate().toString(),
            e.getRemark(),
            source);
    // Check retries before resolving a source that may since have been removed.
    var existing =
        db.queryForList(
            "SELECT * FROM trip_expense WHERE trip_id=? AND request_key=? FOR UPDATE",
            trip,
            requestKey);
    if (!existing.isEmpty()) {
      var row = existing.get(0);
      String original = (String) row.get("request_hash");
      if (original == null) {
        original =
            expenseHash(
                trip,
                ((Number) row.get("user_id")).longValue(),
                (String) row.get("category"),
                (BigDecimal) row.get("amount"),
                (String) row.get("expense_name"),
                row.get("expense_date").toString(),
                (String) row.get("remark"),
                row.get("source_item_id") == null
                    ? null
                    : ((Number) row.get("source_item_id")).longValue());
      }
      if (!Objects.equals(original, hash)) throw new BusinessException(409, "同一请求标识不能用于不同账单");
      db.update(
          "UPDATE trip_expense SET request_hash=? WHERE id=? AND request_hash IS NULL",
          hash,
          row.get("id"));
      return; // Never restore a deleted bill or overwrite later edits.
    }
    if (source != null) {
      var item = access.item(source);
      if (!Objects.equals(item.getTripId(), trip)) throw new BusinessException(403, "费用来源节点不属于该行程");
    }
    db.update(
        "INSERT INTO"
            + " trip_expense(trip_id,user_id,category,amount,expense_name,expense_date,remark,source_item_id,request_key,request_hash,is_deleted)"
            + " VALUES(?,?,?,?,?,?,?,?,?,?,0)",
        trip,
        access.userId(),
        e.getCategory(),
        e.getAmount(),
        e.getExpenseName(),
        e.getExpenseDate(),
        e.getRemark(),
        source,
        requestKey,
        hash);
  }

  private void lockTarget(String table, Long id, String missing) {
    // The first read only locates the trip. All locking reads use plan -> member -> tool row.
    var rows = db.queryForList("SELECT trip_id FROM " + table + " WHERE id=? AND is_deleted=0", id);
    if (rows.isEmpty()) throw new BusinessException(404, missing);
    Long trip = ((Number) rows.get(0).get("trip_id")).longValue();
    access.writeLocked(trip);
    if (db.queryForList(
            "SELECT id FROM " + table + " WHERE id=? AND trip_id=? AND is_deleted=0 FOR UPDATE",
            id,
            trip)
        .isEmpty()) throw new BusinessException(404, missing);
  }

  @Transactional
  public void edit(Long id, TripExpense e) {
    lockTarget("trip_expense", id, "账单不存在");
    valid(e);
    db.update(
        "UPDATE trip_expense SET category=?,amount=?,expense_name=?,expense_date=?,remark=? WHERE"
            + " id=? AND is_deleted=0",
        e.getCategory(),
        e.getAmount(),
        e.getExpenseName(),
        e.getExpenseDate(),
        e.getRemark(),
        id);
  }

  @Transactional
  public void deleteExpense(Long id) {
    lockTarget("trip_expense", id, "账单不存在");
    db.update("UPDATE trip_expense SET is_deleted=1 WHERE id=? AND is_deleted=0", id);
  }

  public List<Map<String, Object>> packing(Long id) {
    access.read(id);
    return db.queryForList(
        "SELECT id,category,item_name AS itemName,is_checked AS isChecked FROM trip_packing_item"
            + " WHERE trip_id=? AND is_deleted=0 ORDER BY id",
        id);
  }

  @Transactional
  public void addPacking(Long id, String name) {
    access.writeLocked(id);
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
    access.writeLocked(id);
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

  @Transactional
  public void packingItem(Long id, Boolean checked, boolean deleted) {
    lockTarget("trip_packing_item", id, "清单条目不存在");
    if (deleted)
      db.update("UPDATE trip_packing_item SET is_deleted=1 WHERE id=? AND is_deleted=0", id);
    else {
      if (checked == null) throw new BusinessException(400, "缺少状态");
      db.update(
          "UPDATE trip_packing_item SET is_checked=? WHERE id=? AND is_deleted=0",
          checked ? 1 : 0,
          id);
    }
  }
}

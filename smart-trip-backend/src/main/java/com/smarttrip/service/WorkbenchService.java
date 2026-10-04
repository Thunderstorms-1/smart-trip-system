package com.smarttrip.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.smarttrip.common.*;
import com.smarttrip.entity.*;
import com.smarttrip.repository.TripRepository;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkbenchService {
  private final JdbcTemplate db;
  private final TripRepository repository;
  private final ResourceAccess access;
  private final ObjectMapper json =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  public WorkbenchService(JdbcTemplate db, TripRepository repository, ResourceAccess access) {
    this.db = db;
    this.repository = repository;
    this.access = access;
  }

  public Object parseObject(String v) {
    try {
      return json.readValue(v, Object.class);
    } catch (Exception e) {
      throw new BusinessException(422, "任务数据格式错误");
    }
  }

  public String encode(Object v) {
    try {
      return json.writeValueAsString(v);
    } catch (Exception e) {
      throw new BusinessException(400, "数据无法序列化");
    }
  }

  public TripDetailVO decode(String v) {
    try {
      return json.readValue(v, TripDetailVO.class);
    } catch (Exception e) {
      throw new BusinessException(400, "路书结构不符合要求");
    }
  }

  @Transactional(readOnly = true)
  public TripDetailVO load(Long id) {
    return repository.tree(access.read(id), false);
  }

  public void validate(TripDetailVO t) {
    if (t == null
        || t.getTitle() == null
        || t.getTitle().isBlank()
        || t.getTitle().length() > 100
        || t.getDestination() == null
        || t.getDestination().isBlank()
        || t.getDestination().length() > 100) throw new BusinessException(400, "标题和目的地必填，且不超过100字");
    if (t.getSummary() != null && t.getSummary().length() > 500)
      throw new BusinessException(400, "简介最多500字");
    if (t.getDays() == null || t.getDays().isEmpty() || t.getDays().size() > 30)
      throw new BusinessException(400, "行程应为1至30天");
    if (t.getTripStyle() != null && t.getTripStyle().length() > 50
        || t.getTravelGroup() != null && t.getTravelGroup().length() > 50)
      throw new BusinessException(400, "偏好长度超限");
    Set<Long> days = new HashSet<>(), items = new HashSet<>();
    int count = 0;
    for (var d : t.getDays()) {
      if (d == null || d.getId() != null && !days.add(d.getId()))
        throw new BusinessException(400, "日程重复");
      if (d.getDayTheme() != null && d.getDayTheme().length() > 100)
        throw new BusinessException(400, "日程主题最多100字");
      if (d.getDaySummary() != null && d.getDaySummary().length() > 5000)
        throw new BusinessException(400, "日程简介最多5000字");
      if (d.getItems() == null) d.setItems(new ArrayList<>());
      if (d.getItems().size() > 40) throw new BusinessException(400, "单日节点最多40个");
      for (var i : d.getItems()) {
        if (i == null || i.getId() != null && !items.add(i.getId()))
          throw new BusinessException(400, "节点重复");
        count++;
        if (i.getItemName() == null || i.getItemName().isBlank() || i.getItemName().length() > 100)
          throw new BusinessException(400, "地点名称必填且最多100字");
        if (i.getItemType() == null) i.setItemType("SCENIC");
        if (!Set.of("SCENIC", "FOOD", "HOTEL", "TRAFFIC", "OTHER").contains(i.getItemType()))
          throw new BusinessException(400, "节点类型无效");
        if (i.getStartTime() != null
            && !i.getStartTime().isBlank()
            && !i.getStartTime().matches("([01][0-9]|2[0-3]):[0-5][0-9]"))
          throw new BusinessException(400, "时间格式应为HH:mm");
        if (i.getCostEstimate() == null) i.setCostEstimate(BigDecimal.ZERO);
        if (i.getCostEstimate().signum() < 0
            || i.getCostEstimate().compareTo(new BigDecimal("9999999")) > 0)
          throw new BusinessException(400, "费用超出允许范围");
        i.setCostEstimate(i.getCostEstimate().setScale(2, java.math.RoundingMode.HALF_UP));
        if (i.getCostTimeHours() != null
            && (i.getCostTimeHours().signum() < 0
                || i.getCostTimeHours().compareTo(new BigDecimal("24")) > 0))
          throw new BusinessException(400, "停留时间应为0至24小时");
        if (i.getAiTips() != null && i.getAiTips().length() > 500
            || i.getAddress() != null && i.getAddress().length() > 255
            || i.getTransportTips() != null && i.getTransportTips().length() > 255)
          throw new BusinessException(400, "节点文本超限");
        if (i.getPoiId() != null && i.getPoiId().length() > 128
            || i.getCoordinateSystem() != null && i.getCoordinateSystem().length() > 20)
          throw new BusinessException(400, "地点引用超限");
        if ((i.getLongitude() == null) != (i.getLatitude() == null))
          throw new BusinessException(400, "经纬度必须成对提供");
        if (i.getLongitude() != null) {
          if (i.getLongitude().abs().compareTo(new BigDecimal("180")) > 0
              || i.getLatitude().abs().compareTo(new BigDecimal("90")) > 0
              || !Set.of("GCJ02", "WGS84")
                  .contains(i.getCoordinateSystem() == null ? "" : i.getCoordinateSystem()))
            throw new BusinessException(400, "坐标或坐标系无效");
        }
      }
    }
    if (count > 500) throw new BusinessException(400, "节点总数不能超过500");
  }

  private long insert(String sql, Object... args) {
    var key = new org.springframework.jdbc.support.GeneratedKeyHolder();
    db.update(
        c -> {
          var ps = c.prepareStatement(sql, java.sql.Statement.RETURN_GENERATED_KEYS);
          for (int n = 0; n < args.length; n++) ps.setObject(n + 1, args[n]);
          return ps;
        },
        key);
    return Objects.requireNonNull(key.getKey()).longValue();
  }

  private void normalize(TripDetailVO t) {
    int n = 0;
    BigDecimal total = BigDecimal.ZERO;
    for (var d : t.getDays()) {
      d.setDayIndex(++n);
      d.setTripId(t.getId());
      d.setDayDate(t.getStartDate() == null ? null : t.getStartDate().plusDays(n - 1));
      int order = 0;
      for (var i : d.getItems()) {
        i.setDayId(d.getId());
        i.setTripId(t.getId());
        i.setSortOrder(++order);
        i.setIsDeleted(0);
        if (i.getStartTime() != null && i.getStartTime().isBlank()) i.setStartTime(null);
        if (i.getCostTimeHours() != null)
          i.setCostTimeHours(i.getCostTimeHours().setScale(1, java.math.RoundingMode.HALF_UP));
        if (i.getLongitude() != null) {
          i.setLongitude(i.getLongitude().setScale(6, java.math.RoundingMode.HALF_UP));
          i.setLatitude(i.getLatitude().setScale(6, java.math.RoundingMode.HALF_UP));
        }
        total = total.add(i.getCostEstimate());
      }
    }
    t.setTotalDays(n);
    t.setTotalBudget(total);
  }

  public void clearIds(TripDetailVO t) {
    t.setId(null);
    t.setUserId(null);
    t.setIsPublic(0);
    for (var d : t.getDays()) {
      d.setId(null);
      d.setTripId(null);
      for (var i : d.getItems()) {
        i.setId(null);
        i.setTripId(null);
        i.setDayId(null);
      }
    }
  }

  @Transactional
  public TripDetailVO create(TripDetailVO t) {
    validate(t);
    clearIds(t);
    normalize(t);
    long id =
        insert(
            "INSERT INTO"
                + " trip_plan(user_id,title,destination,start_date,total_days,total_budget,trip_style,travel_group,summary,is_public,is_ai_generated,version)"
                + " VALUES(?,?,?,?,?,?,?,?,?,0,0,0)",
            access.userId(),
            t.getTitle(),
            t.getDestination(),
            t.getStartDate(),
            t.getTotalDays(),
            t.getTotalBudget(),
            t.getTripStyle(),
            t.getTravelGroup(),
            t.getSummary());
    t.setId(id);
    t.setVersion(0L);
    persistChanges(t, null);
    return load(id);
  }

  private List<Object> headerValues(TripDetailVO t) {
    return Arrays.asList(
        t.getTitle(),
        t.getDestination(),
        t.getStartDate(),
        t.getTotalDays(),
        t.getTotalBudget(),
        t.getTripStyle(),
        t.getTravelGroup(),
        t.getSummary());
  }

  private List<Object> dayValues(TripDayVO d) {
    return Arrays.asList(
        d.getId(), d.getDayIndex(), d.getDayDate(), d.getDayTheme(), d.getDaySummary());
  }

  private List<Object> itemValues(TripItem i) {
    return Arrays.asList(
        i.getId(),
        i.getDayId(),
        i.getItemType(),
        i.getItemName(),
        i.getStartTime(),
        decimal(i.getCostTimeHours()),
        decimal(i.getCostEstimate()),
        i.getTransportTips(),
        i.getAiTips(),
        i.getAddress(),
        decimal(i.getLongitude()),
        decimal(i.getLatitude()),
        i.getPoiId(),
        i.getCoordinateSystem(),
        i.getSortOrder());
  }

  private BigDecimal decimal(BigDecimal d) {
    return d == null ? null : d.stripTrailingZeros();
  }

  private boolean unchanged(TripDetailVO a, TripDetailVO b) {
    if (!headerValues(a).equals(headerValues(b)) || a.getDays().size() != b.getDays().size())
      return false;
    for (int n = 0; n < a.getDays().size(); n++) {
      var x = a.getDays().get(n);
      var y = b.getDays().get(n);
      if (!dayValues(x).equals(dayValues(y)) || x.getItems().size() != y.getItems().size())
        return false;
      for (int k = 0; k < x.getItems().size(); k++)
        if (!itemValues(x.getItems().get(k)).equals(itemValues(y.getItems().get(k)))) return false;
    }
    return true;
  }

  @Transactional
  public TripDetailVO save(Long id, TripDetailVO t) {
    return saveInternal(id, t, false);
  }

  private TripDetailVO saveInternal(Long id, TripDetailVO t, boolean restore) {
    validate(t);
    if (t.getVersion() == null) throw new BusinessException(400, "缺少版本号");
    var before = repository.tree(access.writeLocked(id), false);
    if (!Objects.equals(before.getVersion(), t.getVersion()))
      throw new BusinessException(409, "路书已被修改，请刷新后重试");
    TripDetailVO allowed = restore ? repository.tree(repository.find(id), true) : before;
    Set<Long> dayIds = new HashSet<>(), itemIds = new HashSet<>();
    for (var d : allowed.getDays()) {
      dayIds.add(d.getId());
      for (var i : d.getItems()) itemIds.add(i.getId());
    }
    for (var d : t.getDays()) {
      if (d.getId() != null && !dayIds.contains(d.getId()))
        throw new BusinessException(403, "日程不属于该路书");
      for (var i : d.getItems())
        if (i.getId() != null && !itemIds.contains(i.getId()))
          throw new BusinessException(403, "节点不属于该路书");
    }
    t.setId(id);
    normalize(t);
    if (unchanged(before, t)) return before;
    db.update(
        "INSERT INTO trip_revision(trip_id,version,snapshot_json,created_by) VALUES(?,?,?,?)",
        id,
        before.getVersion(),
        encode(before),
        access.userId());
    if (db.update(
            "UPDATE trip_plan SET"
                + " title=?,destination=?,start_date=?,total_days=?,total_budget=?,trip_style=?,travel_group=?,summary=?,version=version+1,update_time=NOW()"
                + " WHERE id=? AND version=? AND is_deleted=0",
            t.getTitle(),
            t.getDestination(),
            t.getStartDate(),
            t.getTotalDays(),
            t.getTotalBudget(),
            t.getTripStyle(),
            t.getTravelGroup(),
            t.getSummary(),
            id,
            t.getVersion())
        != 1) throw new BusinessException(409, "版本冲突");
    persistChanges(t, before);
    return load(id);
  }

  private void deleteRemoved(String table, Long trip, Collection<Long> removed) {
    if (removed.isEmpty()) return;
    List<Object> args = new ArrayList<>();
    args.add(trip);
    args.addAll(removed);
    db.update(
        "UPDATE "
            + table
            + " SET is_deleted=1 WHERE trip_id=? AND is_deleted=0 AND id IN ("
            + String.join(",", Collections.nCopies(removed.size(), "?"))
            + ")",
        args.toArray());
  }

  private void persistChanges(TripDetailVO t, TripDetailVO before) {
    Map<Long, TripDayVO> oldDays = new HashMap<>();
    Map<Long, TripItem> oldItems = new HashMap<>();
    if (before != null)
      for (var d : before.getDays()) {
        oldDays.put(d.getId(), d);
        for (var i : d.getItems()) oldItems.put(i.getId(), i);
      }
    Set<Long> removedDays = new HashSet<>(oldDays.keySet()),
        removedItems = new HashSet<>(oldItems.keySet());
    for (var d : t.getDays()) {
      d.setTripId(t.getId());
      if (d.getId() == null)
        d.setId(
            insert(
                "INSERT INTO trip_day(trip_id,day_index,day_date,day_theme,day_summary,is_deleted)"
                    + " VALUES(?,?,?,?,?,0)",
                t.getId(),
                d.getDayIndex(),
                d.getDayDate(),
                d.getDayTheme(),
                d.getDaySummary()));
      else {
        removedDays.remove(d.getId());
        var old = oldDays.get(d.getId());
        if (old == null || !dayValues(old).equals(dayValues(d)))
          db.update(
              "UPDATE trip_day SET day_index=?,day_date=?,day_theme=?,day_summary=?,is_deleted=0"
                  + " WHERE id=? AND trip_id=?",
              d.getDayIndex(),
              d.getDayDate(),
              d.getDayTheme(),
              d.getDaySummary(),
              d.getId(),
              t.getId());
      }
      for (var i : d.getItems()) {
        i.setDayId(d.getId());
        i.setTripId(t.getId());
        Object[] values = {
          i.getDayId(),
          i.getItemType(),
          i.getItemName(),
          i.getStartTime(),
          i.getCostTimeHours(),
          i.getCostEstimate(),
          i.getTransportTips(),
          i.getAiTips(),
          i.getAddress(),
          i.getLongitude(),
          i.getLatitude(),
          i.getPoiId(),
          i.getCoordinateSystem(),
          i.getSortOrder()
        };
        if (i.getId() == null) {
          List<Object> args = new ArrayList<>();
          args.add(t.getId());
          args.addAll(Arrays.asList(values));
          i.setId(
              insert(
                  "INSERT INTO"
                      + " trip_item(trip_id,day_id,item_type,item_name,start_time,cost_time_hours,cost_estimate,transport_tips,ai_tips,address,longitude,latitude,poi_id,coordinate_system,sort_order,is_deleted)"
                      + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0)",
                  args.toArray()));
        } else {
          removedItems.remove(i.getId());
          var old = oldItems.get(i.getId());
          if (old == null || !itemValues(old).equals(itemValues(i))) {
            List<Object> args = new ArrayList<>(Arrays.asList(values));
            args.add(i.getId());
            args.add(t.getId());
            db.update(
                "UPDATE trip_item SET"
                    + " day_id=?,item_type=?,item_name=?,start_time=?,cost_time_hours=?,cost_estimate=?,transport_tips=?,ai_tips=?,address=?,longitude=?,latitude=?,poi_id=?,coordinate_system=?,sort_order=?,is_deleted=0,update_time=NOW()"
                    + " WHERE id=? AND trip_id=?",
                args.toArray());
          }
        }
      }
    }
    deleteRemoved("trip_item", t.getId(), removedItems);
    deleteRemoved("trip_day", t.getId(), removedDays);
  }

  public List<Map<String, Object>> revisions(Long id) {
    access.read(id);
    return db.queryForList(
        "SELECT id,version,create_time FROM trip_revision WHERE trip_id=? ORDER BY id DESC LIMIT"
            + " 50",
        id);
  }

  @Transactional
  public TripDetailVO restore(Long id, Long revision, Long version) {
    access.writeLocked(id);
    var rows =
        db.queryForList(
            "SELECT snapshot_json FROM trip_revision WHERE id=? AND trip_id=?", revision, id);
    if (rows.isEmpty()) throw new BusinessException(404, "历史版本不存在");
    var t = decode((String) rows.get(0).get("snapshot_json"));
    t.setVersion(version);
    return saveInternal(id, t, true);
  }

  public List<TripDetailVO> mine() {
    return repository.mine(access.userId());
  }

  @Transactional
  public void delete(Long id) {
    var t = access.writeLocked(id);
    if (!Objects.equals(t.getUserId(), access.userId()))
      throw new BusinessException(403, "只有所有者可删除");
    db.update("UPDATE trip_plan SET is_deleted=1,version=version+1 WHERE id=?", id);
    db.update("UPDATE square_post SET is_deleted=1 WHERE trip_id=?", id);
    db.update("UPDATE trip_share SET revoked=1 WHERE trip_id=?", id);
  }
}

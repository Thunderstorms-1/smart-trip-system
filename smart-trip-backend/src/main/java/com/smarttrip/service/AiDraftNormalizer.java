package com.smarttrip.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.smarttrip.entity.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** Deterministic draft repair only. Unknown facts and blocking problems remain explicit. */
@Service
public class AiDraftNormalizer {
  public record Issue(String code, Integer dayIndex, Integer itemIndex,
      Integer relatedItemIndex, String message) {}

  public record Report(TripDetailVO draft, List<String> corrections,
      List<Issue> issues, List<String> warnings) {
    public Report {
      corrections = List.copyOf(corrections);
      issues = List.copyOf(issues);
      warnings = List.copyOf(warnings);
    }
  }

  private record Slot(TripItem item, int originalIndex, int start, int duration) {
    int end() { return start + duration; }
    boolean locked() { return Boolean.TRUE.equals(item.getScheduleLocked()); }
  }

  private static final BigDecimal MAX_COST = new BigDecimal("9999999");
  private static final Pattern CLOCK = Pattern.compile("(?:[01][0-9]|2[0-3]):[0-5][0-9]");
  private static final Pattern USER_CLOCK = Pattern.compile(
      "(?:[0-2]?[0-9][:：][0-5][0-9]|[零一二两三四五六七八九十几0-9]{1,3}[点时])");
  private static final Pattern TIME_LIMIT = Pattern.compile(
      "必须|务必|只能|最晚|最早|之前|之后|前|后|准时|预约|预订|预定|固定|航班|火车|高铁|集合|抵达|到达|出发|离开|回程|赶|不能|不得|需要|要在");
  private static final Pattern AGGREGATE_MEALS = Pattern.compile("三餐|午\\s*餐\\s*[+＋、/与和及]\\s*晚\\s*餐|午晚餐");
  private static final Map<String, String> TYPES = Map.ofEntries(
      Map.entry("ATTRACTION", "SCENIC"), Map.entry("SIGHTSEEING", "SCENIC"),
      Map.entry("SIGHT", "SCENIC"), Map.entry("景点", "SCENIC"), Map.entry("景区", "SCENIC"),
      Map.entry("RESTAURANT", "FOOD"), Map.entry("DINING", "FOOD"), Map.entry("MEAL", "FOOD"),
      Map.entry("餐饮", "FOOD"), Map.entry("美食", "FOOD"),
      Map.entry("ACCOMMODATION", "HOTEL"), Map.entry("LODGING", "HOTEL"),
      Map.entry("酒店", "HOTEL"), Map.entry("住宿", "HOTEL"),
      Map.entry("TRANSPORT", "TRAFFIC"), Map.entry("TRANSPORTATION", "TRAFFIC"),
      Map.entry("TRANSIT", "TRAFFIC"), Map.entry("交通", "TRAFFIC"),
      Map.entry("ACTIVITY", "OTHER"), Map.entry("活动", "OTHER"), Map.entry("其他", "OTHER"));

  private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());

  public Report normalize(TripDetailVO input, TravelRequirement requirement, boolean autoSchedule) {
    var corrections = new ArrayList<String>();
    var issues = new ArrayList<Issue>();
    var warnings = new LinkedHashSet<String>();
    if (input == null) return report(null, corrections,
        List.of(new Issue("MISSING_DRAFT", null, null, null, "未返回可读取的草稿")), warnings);
    TripDetailVO draft;
    try {
      draft = json.convertValue(input, TripDetailVO.class);
    } catch (IllegalArgumentException e) {
      return report(null, corrections,
          List.of(new Issue("INVALID_DRAFT", null, null, null, "草稿结构无法读取")), warnings);
    }
    if (draft.getDays() == null || draft.getDays().isEmpty())
      return report(draft, corrections,
          List.of(new Issue("MISSING_DAYS", null, null, null, "未返回逐日活动，未补造日程")), warnings);
    if (!Objects.equals(draft.getTotalDays(), draft.getDays().size())) {
      draft.setTotalDays(draft.getDays().size());
      corrections.add("声明天数已按实际日程数组重新计算");
    }
    boolean userTimeLimit = hasUserTimeLimit(requirement);
    if (autoSchedule && userTimeLimit)
      warnings.add("用户描述中存在明确时间限制，已停止自动顺延；涉及冲突需手动确认，未改写该限制");
    BigDecimal knownCosts = BigDecimal.ZERO;
    boolean completeCosts = true;
    for (int d = 0; d < draft.getDays().size(); d++) {
      var day = draft.getDays().get(d);
      int dayIndex = d + 1;
      if (day == null) {
        issues.add(new Issue("MISSING_DAY", dayIndex, null, null, "该日程内容缺失"));
        completeCosts = false;
        continue;
      }
      if (!Objects.equals(day.getDayIndex(), dayIndex)) {
        day.setDayIndex(dayIndex);
        corrections.add("第" + dayIndex + "天序号已按数组顺序规范化，未改动日期");
      }
      if (day.getItems() == null || day.getItems().isEmpty()) {
        issues.add(new Issue("MISSING_ITEMS", dayIndex, null, null, "该日缺少活动，未补造景点"));
        completeCosts = false;
        continue;
      }
      var original = new ArrayList<>(day.getItems());
      var localIssues = new ArrayList<Issue>();
      var slots = new ArrayList<Slot>();
      boolean unknownKnownDuration = false;
      boolean clearedGeo = false;
      int knownDurationMinutes = 0;
      for (int n = 0; n < original.size(); n++) {
        var item = original.get(n);
        int itemIndex = n + 1;
        if (item == null) {
          localIssues.add(new Issue("MISSING_ITEM", dayIndex, itemIndex, null, "活动内容缺失"));
          completeCosts = false;
          continue;
        }
        String type = item.getItemType();
        String canonical = type == null ? null : type.trim().toUpperCase(Locale.ROOT);
        canonical = TYPES.getOrDefault(canonical == null ? "" : canonical, canonical);
        if (canonical == null || !Set.of("SCENIC", "FOOD", "HOTEL", "TRAFFIC", "OTHER").contains(canonical))
          localIssues.add(new Issue("INVALID_ITEM_TYPE", dayIndex, itemIndex, null, "活动类型缺失或无法可靠识别"));
        else if (!canonical.equals(type)) {
          item.setItemType(canonical);
          corrections.add("第" + dayIndex + "天原第" + itemIndex + "项活动类型已规范化为" + canonical);
        }
        if ("FOOD".equals(item.getItemType()) && AGGREGATE_MEALS.matcher(name(item)).find())
          warnings.add("第" + dayIndex + "天「" + name(item)
              + "」合并了多餐，其估算区间不代表连续实际用餐，请手动拆分或核对；未补造节点、时间或费用");
        if (item.getLongitude() != null || item.getLatitude() != null || item.getPoiId() != null
            || item.getCoordinateSystem() != null || item.getRating() != null || item.getCoverImage() != null)
          clearedGeo = true;
        item.setLongitude(null);
        item.setLatitude(null);
        item.setPoiId(null);
        item.setCoordinateSystem(null);
        item.setRating(null);
        item.setCoverImage(null);
        if (item.getCostEstimate() == null) {
          completeCosts = false;
          localIssues.add(new Issue("MISSING_COST", dayIndex, itemIndex, null, "活动费用尚未估算，未按零元处理"));
        } else if (item.getCostEstimate().signum() < 0 || item.getCostEstimate().compareTo(MAX_COST) > 0) {
          completeCosts = false;
          localIssues.add(new Issue("INVALID_COST", dayIndex, itemIndex, null, "活动费用超出允许范围，原金额未改写"));
        } else {
          var rounded = item.getCostEstimate().setScale(2, RoundingMode.HALF_UP);
          if (item.getCostEstimate().compareTo(rounded) != 0)
            corrections.add("第" + dayIndex + "天原第" + itemIndex + "项费用已按工作台分位精度四舍五入，未调整预算上限");
          item.setCostEstimate(rounded);
          knownCosts = knownCosts.add(rounded);
        }
        Integer start = start(item);
        String rawTime = item.getStartTime();
        if (rawTime == null || rawTime.isBlank()) {
          unknownKnownDuration = true;
          warnings.add("第" + dayIndex + "天存在待定开始时间，未自动编造时刻，完整时间衔接仍需确认");
          if (Boolean.TRUE.equals(item.getScheduleLocked())) {
            unknownKnownDuration = true;
            localIssues.add(new Issue("FIXED_TIME_MISSING", dayIndex, itemIndex, null, "固定活动缺少开始时间，无法可靠安排其他活动"));
          }
        } else if (start == null) {
          unknownKnownDuration = true;
          localIssues.add(new Issue("INVALID_START_TIME", dayIndex, itemIndex, null, "活动开始时间不是有效的HH:mm，未猜测替换"));
        }
        Integer minutes = duration(item);
        if (minutes == null) {
          localIssues.add(new Issue(item.getCostTimeHours() == null ? "MISSING_DURATION" : "INVALID_DURATION",
              dayIndex, itemIndex, null, "活动时长须明确且大于0、不超过24小时，未补造或缩短时长"));
          unknownKnownDuration = true;
        } else if (item.getCostTimeHours().stripTrailingZeros().scale() > 1) {
          unknownKnownDuration = true;
          localIssues.add(new Issue("DURATION_PRECISION", dayIndex, itemIndex, null,
              "活动「" + name(item) + "」时长" + item.getCostTimeHours().toPlainString()
                  + "小时超出工作台支持的1位小数精度；原时长未改变，请确认后编辑，避免保存舍入造成重叠"));
        }
        if (minutes != null) knownDurationMinutes += minutes;
        if (start != null && minutes != null) slots.add(new Slot(item, itemIndex, start, minutes));
      }
      if (clearedGeo) corrections.add("第" + dayIndex + "天未经核实的坐标、POI、评分及封面已清除，活动内容未替换");
      if (knownDurationMinutes > 24 * 60)
        localIssues.add(new Issue("DAY_DURATION_EXCEEDED", dayIndex, null, null,
            "已知活动时长合计超过24小时，未缩短活动或假设并行安排"));
      slots.sort(Comparator.comparingInt(Slot::start).thenComparingInt(Slot::originalIndex));
      boolean arrange = autoSchedule && !userTimeLimit && !unknownKnownDuration;
      if (arrange) {
        var fixed = slots.stream().filter(Slot::locked).toList();
        schedule(slots, fixed, dayIndex, corrections, localIssues);
      } else if (autoSchedule && unknownKnownDuration) {
        warnings.add("第" + dayIndex + "天存在待确认时间区间或时长精度，未自动顺延，避免越过未知或固定安排");
      }
      // Rebuild from retained/adjusted clocks, including every failed shift and fixed anchor.
      var finalTimeline = slots.stream()
          .map(slot -> new Slot(slot.item(), slot.originalIndex(), start(slot.item()), slot.duration()))
          .sorted(Comparator.comparingInt(Slot::start).thenComparingInt(Slot::originalIndex)).toList();
      detect(finalTimeline, dayIndex, localIssues);
      day.getItems().sort(Comparator.comparing(AiDraftNormalizer::start,
          Comparator.nullsLast(Integer::compareTo)));
      var finalPositions = new IdentityHashMap<TripItem, ArrayDeque<Integer>>();
      boolean reordered = false;
      for (int n = 0; n < day.getItems().size(); n++) {
        var item = day.getItems().get(n);
        finalPositions.computeIfAbsent(item, ignored -> new ArrayDeque<>()).add(n + 1);
        if (item != null) item.setSortOrder(n + 1);
        if (item != original.get(n)) reordered = true;
      }
      int[] positions = new int[original.size() + 1];
      for (int n = 0; n < original.size(); n++) positions[n + 1] = finalPositions.get(original.get(n)).removeFirst();
      for (var issue : localIssues)
        issues.add(new Issue(issue.code(), issue.dayIndex(), remap(issue.itemIndex(), positions),
            remap(issue.relatedItemIndex(), positions), issue.message()));
      if (reordered) corrections.add("第" + dayIndex + "天活动已按明确开始时间排序；待定时刻未推断，保留在列表末尾");
    }
    if (completeCosts) {
      if (draft.getTotalBudget() == null || draft.getTotalBudget().compareTo(knownCosts) != 0)
        corrections.add("预计总费用已按全部活动费用明细重新计算，未把预算上限当支出");
      draft.setTotalBudget(knownCosts);
    } else {
      draft.setTotalBudget(null);
      warnings.add("存在未确认或无效费用，预计总费用保持待确认；已知费用不代表完整支出");
    }
    checkBudget(requirement, knownCosts, completeCosts, issues, warnings);
    warnings.add("时间检查仅依据已知开始时间与活动时长，未验证真实交通、开放时间或费用报价");
    return report(draft, corrections, issues, warnings);
  }

  private static void schedule(List<Slot> slots, List<Slot> fixed, int day,
      List<String> corrections, List<Issue> issues) {
    int cursor = 0;
    Integer previous = null;
    for (var slot : slots) {
      if (slot.locked()) continue;
      int candidate = Math.max(slot.start(), cursor);
      Integer related = candidate > slot.start() ? previous : null;
      for (var anchor : fixed) {
        if (candidate + slot.duration() <= anchor.start()) break;
        if (candidate < anchor.end() && candidate + slot.duration() > anchor.start()) {
          candidate = anchor.end();
          related = anchor.originalIndex();
        }
      }
      int end = candidate + slot.duration();
      if (end > 24 * 60) {
        issues.add(new Issue("DAY_END_OVERFLOW", day, slot.originalIndex(), related,
            interval(slot) + "若顺延需安排在" + clock(candidate) + "–" + clock(end)
                + "，超过当天24点；原开始时间及完整时长保留，需人工处理"));
      } else if (candidate != slot.start()) {
        String next = String.format(Locale.ROOT, "%02d:%02d", candidate / 60, candidate % 60);
        corrections.add("第" + day + "天活动「" + name(slot.item()) + "」开始时间由"
            + slot.item().getStartTime() + "顺延至" + next + "，时长及费用未改变");
        slot.item().setStartTime(next);
      }
      cursor = Math.max(cursor, end);
      previous = slot.originalIndex();
    }
  }

  private static void detect(List<Slot> slots, int day, List<Issue> issues) {
    for (int n = 0; n < slots.size(); n++) {
      var slot = slots.get(n);
      if (slot.end() > 24 * 60 && issues.stream().noneMatch(issue ->
          issue.code().equals("DAY_END_OVERFLOW") && Objects.equals(issue.itemIndex(), slot.originalIndex())))
        issues.add(new Issue("DAY_END_OVERFLOW", day, slot.originalIndex(), null,
            interval(slot) + "跨过当天24点，未截短时长或伪造次日安排"));
      // Check all intersecting pairs so a long activity cannot hide a fixed-anchor collision.
      for (int previous = 0; previous < n; previous++) {
        var other = slots.get(previous);
        if (slot.start() >= other.end()) continue;
        boolean fixed = slot.locked() || other.locked();
        issues.add(new Issue(fixed ? "FIXED_NODE_CONFLICT" : "TIME_OVERLAP", day,
            slot.originalIndex(), other.originalIndex(), interval(other) + "与" + interval(slot)
                + "重叠；" + (fixed ? "固定起点保持不变，需人工处理" : "需人工确认时间安排")));
      }
    }
  }

  private static String name(TripItem item) {
    return item.getItemName() == null || item.getItemName().isBlank() ? "未命名活动" : item.getItemName();
  }

  private static String interval(Slot slot) {
    return "「" + name(slot.item()) + "」（" + clock(slot.start()) + "–" + clock(slot.end()) + "）";
  }

  private static String clock(int minute) {
    if (minute == 24 * 60) return "24:00";
    int days = minute / (24 * 60);
    int withinDay = minute % (24 * 60);
    return (days == 0 ? "" : days == 1 ? "次日" : "+" + days + "日")
        + String.format(Locale.ROOT, "%02d:%02d", withinDay / 60, withinDay % 60);
  }

  private static void checkBudget(TravelRequirement r, BigDecimal knownCosts, boolean completeCosts,
      List<Issue> issues, Set<String> warnings) {
    if (r == null || r.budget() == null && r.budgetMax() == null) return;
    BigDecimal limit = r.budgetMax() != null ? r.budgetMax() : r.budget();
    if (r.currency() == null || !"CNY".equalsIgnoreCase(r.currency())) {
      warnings.add("预算币种尚不能与当前人民币费用直接比较，未自行换算汇率");
      return;
    }
    if ("PER_PERSON".equals(r.budgetScope())) {
      if (r.travelers() == null || r.travelers() < 1) {
        warnings.add("人均预算缺少确认人数，未猜测总预算");
        return;
      }
      limit = limit.multiply(BigDecimal.valueOf(r.travelers()));
    } else if (!"TOTAL".equals(r.budgetScope())) {
      warnings.add("预算口径待确认，未将人均与整体预算混用");
      return;
    }
    if (knownCosts.compareTo(limit) > 0)
      issues.add(new Issue("BUDGET_EXCEEDED", null, null, null,
          "已知活动预计合计" + knownCosts.toPlainString() + " CNY超过确认的总预算上限"
              + limit.toPlainString() + " CNY；原费用未降低或删除"));
    else if (!completeCosts) warnings.add("存在未知费用，不能确认完整预计支出符合预算");
  }

  private static boolean hasUserTimeLimit(TravelRequirement r) {
    if (r == null) return false;
    if (r.timeConstraints() != null && !r.timeConstraints().isBlank()) return true;
    var text = new ArrayList<String>();
    text.add(r.description());
    text.add(r.notes());
    if (r.mustVisit() != null) text.addAll(r.mustVisit());
    return text.stream().filter(Objects::nonNull)
        .anyMatch(value -> USER_CLOCK.matcher(value).find() && TIME_LIMIT.matcher(value).find());
  }

  private static Integer start(TripItem item) {
    if (item == null || item.getStartTime() == null || !CLOCK.matcher(item.getStartTime()).matches()) return null;
    return Integer.parseInt(item.getStartTime().substring(0, 2)) * 60
        + Integer.parseInt(item.getStartTime().substring(3, 5));
  }

  private static Integer duration(TripItem item) {
    BigDecimal hours = item.getCostTimeHours();
    if (hours == null || hours.signum() <= 0 || hours.compareTo(BigDecimal.valueOf(24)) > 0) return null;
    return hours.multiply(BigDecimal.valueOf(60)).setScale(0, RoundingMode.CEILING).intValueExact();
  }

  private static Integer remap(Integer index, int[] positions) {
    return index == null ? null : positions[index];
  }

  private static Report report(TripDetailVO draft, List<String> corrections,
      List<Issue> issues, Collection<String> warnings) {
    return new Report(draft, corrections, issues, List.copyOf(warnings));
  }
}

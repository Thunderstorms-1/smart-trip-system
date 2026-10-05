package com.smarttrip.service.quality;

import com.smarttrip.entity.*;
import com.smarttrip.service.ItineraryAnalysisService;
import com.smarttrip.service.ItineraryAnalysisService.*;
import com.smarttrip.service.quality.QualityReport.*;
import java.util.*;

/** All facts and scores originate here, never from the language model. */
public final class ItineraryQualityEngine {
  private static final List<String> KEYS = List.of("TIME", "PACE", "MEAL_REST");

  public QualityReport evaluate(TripDetailVO trip, List<Source> sources, QualityPolicy p) {
    sources = effectiveSources(trip, sources);
    var ids = trip.getDays().stream().map(TripDayVO::getId).toList();
    var basic = ItineraryAnalysisService.diagnose(trip, ids, sources);
    var days = new ArrayList<Day>();
    var findings = new ArrayList<Finding>();
    for (var day : trip.getDays()) {
      var t = TimelineMetrics.of(day, sources, p);
      var m = t.metrics();
      var local = new ArrayList<Finding>();
      var issues = basic.stream().filter(i -> Objects.equals(i.dayId(), day.getId())).toList();
      boolean conflict = issues.stream().anyMatch(i -> "ERROR".equals(i.severity()));
      var nodes =
          day.getItems().stream()
              .filter(i -> !"HOTEL".equals(i.getItemType()))
              .map(TripItem::getId)
              .toList();
      if (!t.complete())
        local.add(
            finding(
                t.coverage().total() == 0 ? "QUALITY_EMPTY_DAY" : "QUALITY_INCOMPLETE",
                day.getId(),
                nodes,
                "信息待补充：缺少有效时间、存在跨日安排，或尚未添加可评价活动",
                0,
                "COMPLETE_INFO",
                "completeNodes",
                t.coverage().complete(),
                t.coverage().total(),
                "项"));
      if (t.complete() && !conflict) {
        int active = m.occupiedActivityMinutes(), span = m.daySpanMinutes();
        if (active > p.activityWarning())
          local.add(
              finding(
                  "QUALITY_ACTIVITY_HEAVY",
                  day.getId(),
                  nodes,
                  "这一天活动时长偏多，可考虑将部分已有活动移至其他选中日程",
                  active > p.activityHigh()
                      ? p.value("activityHighPenalty")
                      : p.value("activityPenalty"),
                  "MOVE",
                  "activityMinutes",
                  active,
                  active > p.activityHigh() ? p.activityHigh() : p.activityWarning(),
                  "分钟"));
        if (span > p.spanWarning() && active > p.value("spanActivityMinimum"))
          local.add(
              finding(
                  "QUALITY_LONG_SPAN",
                  day.getId(),
                  nodes,
                  "这一天的安排跨度偏长，建议检查节奏",
                  span > p.spanHigh() && active > p.activityWarning()
                      ? p.value("spanHighPenalty")
                      : p.value("spanPenalty"),
                  "TIME",
                  "daySpanMinutes",
                  span,
                  span > p.spanHigh() && active > p.activityWarning()
                      ? p.spanHigh()
                      : p.spanWarning(),
                  "分钟"));
        if (m.scenicCount() >= p.value("scenicMinimum")
            && active > 360
            && span > 0
            && m.unallocatedMinutes() * 100 < span * p.value("gapPercent"))
          local.add(
              finding(
                  "QUALITY_DENSE_DAY",
                  day.getId(),
                  nodes,
                  "景点较多且安排密集，建议预留更多计划间隔",
                  p.value("densePenalty"),
                  "MOVE",
                  "scenicCount",
                  m.scenicCount(),
                  p.value("scenicMinimum"),
                  "项"));
        if (m.longestContinuousMinutes() > p.continuousWarning())
          local.add(
              finding(
                  "QUALITY_CONTINUOUS_ACTIVITY",
                  day.getId(),
                  nodes,
                  "连续安排偏长，建议预留用餐或休息时段",
                  m.longestContinuousMinutes() > p.continuousHigh()
                      ? p.value("continuousHighPenalty")
                      : p.value("continuousPenalty"),
                  "TIME",
                  "continuousMinutes",
                  m.longestContinuousMinutes(),
                  m.longestContinuousMinutes() > p.continuousHigh()
                      ? p.continuousHigh()
                      : p.continuousWarning(),
                  "分钟"));
        meal(day, t, p, p.lunchStart(), p.lunchEnd(), "QUALITY_LUNCH_WINDOW", "中午", local);
        meal(day, t, p, p.dinnerStart(), p.dinnerEnd(), "QUALITY_DINNER_WINDOW", "晚间", local);
        for (var gap : m.gaps()) {
          var a =
              day.getItems().stream()
                  .filter(i -> i.getId().equals(gap.from()))
                  .findFirst()
                  .orElseThrow();
          var b =
              day.getItems().stream()
                  .filter(i -> i.getId().equals(gap.to()))
                  .findFirst()
                  .orElseThrow();
          if ("TRAFFIC".equals(a.getItemType()) || "TRAFFIC".equals(b.getItemType())) continue;
          if (gap.routeMinutes() == null
              && gap.plannedMinutes() >= 0
              && gap.plannedMinutes() < p.gapReserve())
            local.add(
                finding(
                    "QUALITY_SMALL_PLANNED_GAP",
                    day.getId(),
                    List.of(gap.from(), gap.to()),
                    "计划间隔偏少，实际转场耗时尚未核实",
                    0,
                    "TIME",
                    "plannedGapMinutes",
                    gap.plannedMinutes(),
                    p.gapReserve(),
                    "分钟"));
          else if (gap.remainingMinutes() != null
              && gap.remainingMinutes() >= 0
              && gap.remainingMinutes() < p.gapReserve()) {
            var f =
                finding(
                    "QUALITY_TRANSFER_BUFFER",
                    day.getId(),
                    List.of(gap.from(), gap.to()),
                    "扣除已测路程后计划余量偏少",
                    0,
                    "TIME",
                    "remainingMinutes",
                    gap.remainingMinutes(),
                    p.gapReserve(),
                    "分钟");
            local.add(
                new Finding(
                    f.id(),
                    f.ruleId(),
                    "WARNING",
                    f.dayIds(),
                    f.itemIds(),
                    f.message(),
                    f.evidence(),
                    0,
                    List.of("ROUTE:" + day.getId()),
                    f.action()));
          }
        }
      }
      int timePenalty =
          Math.min(
                  p.value("overlapCap"),
                  (int)
                          issues.stream()
                              .filter(i -> "TIME_OVERLAP".equals(i.code()))
                              .map(Issue::id)
                              .distinct()
                              .count()
                      * p.value("overlapPenalty"))
              + Math.min(
                  p.value("orderCap"),
                  (int)
                          issues.stream()
                              .filter(i -> "ORDER_TIME".equals(i.code()))
                              .map(Issue::id)
                              .distinct()
                              .count()
                      * p.value("orderPenalty"));
      var dims = new ArrayList<Dimension>();
      dims.add(
          dimension(
              "TIME",
              t.complete() ? "READY" : "UNKNOWN",
              t.complete() ? 100 - timePenalty : null,
              p,
              issues.stream()
                  .filter(i -> Set.of("TIME_OVERLAP", "ORDER_TIME").contains(i.code()))
                  .map(Issue::id)
                  .toList()));
      for (String key : List.of("PACE", "MEAL_REST")) {
        String status =
            !t.complete() || conflict
                ? "UNKNOWN"
                : ("MEAL_REST".equals(key) && t.busy().isEmpty() ? "NOT_APPLICABLE" : "READY");
        var deductions =
            local.stream()
                .filter(f -> key.equals(dimensionKey(f.ruleId())) && f.penalty() > 0)
                .toList();
        dims.add(
            dimension(
                key,
                status,
                "READY".equals(status)
                    ? Math.max(0, 100 - deductions.stream().mapToInt(Finding::penalty).sum())
                    : null,
                p,
                deductions.stream().map(Finding::id).toList()));
      }
      String load = "UNKNOWN";
      if (t.complete() && !conflict) {
        int active = m.occupiedActivityMinutes(),
            span = m.daySpanMinutes(),
            continuous = m.longestContinuousMinutes();
        long signals = local.stream().filter(f -> f.penalty() > 0).count();
        load =
            active > p.activityHigh()
                    || (active > p.activityWarning() && continuous > p.continuousHigh())
                    || (span > p.spanHigh() && active > p.activityWarning())
                ? "OVERLOADED"
                : active > p.activityWarning() || signals >= 2
                    ? "TIGHT"
                    : active <= p.value("lightActivity")
                            && span <= p.value("lightSpan")
                            && continuous <= p.continuousWarning()
                            && signals == 0
                        ? "LIGHT"
                        : "MODERATE";
      }
      findings.addAll(local);
      days.add(
          new Day(
              day.getId(),
              day.getDayIndex() == null ? days.size() + 1 : day.getDayIndex(),
              load,
              t.coverage(),
              m,
              List.copyOf(dims),
              local.stream().map(Finding::id).toList()));
    }
    // Surface every condition of compound rules rather than blaming scenic count alone.
    var enriched = new ArrayList<Finding>();
    for (var f : findings) {
      var rows = new ArrayList<Evidence>(f.evidence());
      var day =
          days.stream().filter(d -> d.dayId().equals(f.dayIds().get(0))).findFirst().orElseThrow();
      if ("QUALITY_DENSE_DAY".equals(f.ruleId())) {
        rows.add(
            new Evidence(
                "activityMinutes",
                day.metrics().occupiedActivityMinutes(),
                p.value("spanActivityMinimum"),
                "分钟"));
        rows.add(
            new Evidence(
                "unallocatedPercent",
                Math.round(
                        day.metrics().unallocatedMinutes()
                            * 10000.0
                            / day.metrics().daySpanMinutes())
                    / 100.0,
                p.value("gapPercent"),
                "%"));
      }
      if ("QUALITY_LONG_SPAN".equals(f.ruleId()))
        rows.add(
            new Evidence(
                "activityMinutes",
                day.metrics().occupiedActivityMinutes(),
                f.penalty() == p.value("spanHighPenalty")
                    ? p.activityWarning()
                    : p.value("spanActivityMinimum"),
                "分钟"));
      enriched.add(
          new Finding(
              f.id(),
              f.ruleId(),
              f.severity(),
              f.dayIds(),
              f.itemIds(),
              f.message(),
              List.copyOf(rows),
              f.penalty(),
              f.evidenceIds(),
              f.action()));
    }
    findings = enriched;
    var dims = new ArrayList<Dimension>();
    for (String key : KEYS) {
      var rows =
          days.stream()
              .flatMap(d -> d.dimensions().stream())
              .filter(d -> key.equals(d.key()))
              .toList();
      boolean unknown = rows.isEmpty() || rows.stream().anyMatch(d -> "UNKNOWN".equals(d.status()));
      var ready = rows.stream().filter(d -> "READY".equals(d.status())).toList();
      String status = unknown ? "UNKNOWN" : ready.isEmpty() ? "NOT_APPLICABLE" : "READY";
      dims.add(
          dimension(
              key,
              status,
              "READY".equals(status)
                  ? (int) Math.round(ready.stream().mapToInt(Dimension::score).average().orElse(0))
                  : null,
              p,
              rows.stream().flatMap(d -> d.deductionIds().stream()).toList()));
    }
    String balance =
        days.isEmpty()
            ? "UNKNOWN"
            : days.size() == 1
                ? "NOT_APPLICABLE"
                : days.stream().anyMatch(d -> !"READY".equals(d.dimensions().get(1).status()))
                    ? "UNKNOWN"
                    : "READY";
    int penalty = 0;
    var balanceIds = new ArrayList<String>();
    if ("READY".equals(balance)) {
      var max =
          days.stream()
              .max(Comparator.comparingInt(d -> d.metrics().occupiedActivityMinutes()))
              .orElseThrow();
      var min =
          days.stream()
              .min(Comparator.comparingInt(d -> d.metrics().occupiedActivityMinutes()))
              .orElseThrow();
      int delta = max.metrics().occupiedActivityMinutes() - min.metrics().occupiedActivityMinutes();
      if (delta >= p.balanceWarning()
          && max.metrics().occupiedActivityMinutes() > p.activityWarning()) {
        penalty =
            delta >= p.balanceHigh() && max.metrics().occupiedActivityMinutes() > p.activityHigh()
                ? p.value("balanceHighPenalty")
                : p.value("balancePenalty");
        List<Long> involved = List.of(max.dayId(), min.dayId());
        var f =
            new Finding(
                "QUALITY_UNEVEN_DAYS:" + max.dayId() + ":" + min.dayId(),
                "QUALITY_UNEVEN_DAYS",
                "WARNING",
                involved,
                trip.getDays().stream()
                    .filter(d -> involved.contains(d.getId()))
                    .flatMap(d -> d.getItems().stream())
                    .filter(i -> !"HOTEL".equals(i.getItemType()))
                    .map(TripItem::getId)
                    .toList(),
                "不同日程的活动时长差异明显，可按旅行偏好检查是否需要分配",
                List.of(
                    new Evidence(
                        "dailyActivityDifference",
                        delta,
                        penalty == p.value("balanceHighPenalty")
                            ? p.balanceHigh()
                            : p.balanceWarning(),
                        "分钟")),
                penalty,
                List.of(),
                "MOVE");
        findings.add(f);
        balanceIds.add(f.id());
      }
    }
    dims.add(
        dimension(
            "BALANCE", balance, "READY".equals(balance) ? 100 - penalty : null, p, balanceIds));
    boolean unknown = dims.stream().anyMatch(d -> "UNKNOWN".equals(d.status()));
    int weight =
        dims.stream().filter(d -> "READY".equals(d.status())).mapToInt(Dimension::weight).sum();
    Integer total =
        unknown || weight == 0
            ? null
            : (int)
                Math.round(
                    dims.stream()
                            .filter(d -> "READY".equals(d.status()))
                            .mapToInt(d -> d.score() * d.weight())
                            .sum()
                        / (double) weight);
    var order = new HashMap<Long, Integer>();
    for (int n = 0; n < ids.size(); n++) order.put(ids.get(n), n);
    findings.sort(
        Comparator.comparingInt((Finding f) -> "WARNING".equals(f.severity()) ? 0 : 1)
            .thenComparingInt(f -> order.getOrDefault(f.dayIds().get(0), 0))
            .thenComparing(Finding::id));
    return new QualityReport(
        1,
        p.rulesVersion(),
        "DEFAULT",
        "TRIP",
        trip.getVersion(),
        ids,
        total != null
            ? "READY"
            : dims.stream().anyMatch(d -> "READY".equals(d.status())) ? "PARTIAL" : "INSUFFICIENT",
        total,
        List.copyOf(dims),
        List.copyOf(days),
        List.copyOf(findings),
        basic,
        external(trip, sources));
  }

  private static String dimensionKey(String code) {
    if (Set.of("QUALITY_ACTIVITY_HEAVY", "QUALITY_LONG_SPAN", "QUALITY_DENSE_DAY").contains(code))
      return "PACE";
    if (Set.of("QUALITY_CONTINUOUS_ACTIVITY", "QUALITY_LUNCH_WINDOW", "QUALITY_DINNER_WINDOW")
        .contains(code)) return "MEAL_REST";
    return "BALANCE";
  }

  private static Dimension dimension(
      String key, String status, Integer score, QualityPolicy p, List<String> ids) {
    return new Dimension(
        key,
        status,
        score,
        p.weight(key),
        List.copyOf(ids),
        "UNKNOWN".equals(status) ? List.of("必要时间信息不完整、跨日未确认、空日程或时间安排存在硬冲突") : List.of());
  }

  private static Finding finding(
      String code,
      Long day,
      List<Long> items,
      String message,
      int penalty,
      String action,
      String metric,
      double actual,
      double threshold,
      String unit) {
    return new Finding(
        code + ":" + day + ":" + items,
        code,
        penalty > 0 ? "WARNING" : "INFO",
        List.of(day),
        List.copyOf(items),
        message,
        List.of(new Evidence(metric, actual, threshold, unit)),
        penalty,
        List.of(),
        action);
  }

  private static void meal(
      TripDayVO day,
      TimelineMetrics.Timeline t,
      QualityPolicy p,
      int from,
      int to,
      String code,
      String label,
      List<Finding> rows) {
    if (TimelineMetrics.windowLength(t.busy(), from, to) < p.mealOccupied()) return;
    boolean food =
        t.food().stream()
            .anyMatch(
                f ->
                    Math.min(to, f.end()) - Math.max(from, f.start()) >= p.foodMinutes()
                        && t.busy().stream()
                            .noneMatch(a -> a.start() < f.end() && f.start() < a.end()));
    if (food || TimelineMetrics.longestUnallocated(t.intervals(), from, to) >= p.breakMinutes())
      return;
    var ids =
        t.busy().stream()
            .filter(i -> i.start() < to && i.end() > from)
            .map(TimelineMetrics.Interval::itemId)
            .toList();
    rows.add(
        finding(
            code,
            day.getId(),
            ids,
            label
                + "安排较连续，建议预留"
                + ("QUALITY_LUNCH_WINDOW".equals(code) ? "午餐" : "晚餐")
                + "或休息时间；没有餐饮节点时需手动添加",
            p.value("mealPenalty"),
            t.food().isEmpty() ? "MANUAL" : "TIME",
            "occupiedWindowMinutes",
            TimelineMetrics.windowLength(t.busy(), from, to),
            p.mealOccupied(),
            "分钟"));
  }

  public static List<Source> effectiveSources(TripDetailVO trip, List<Source> sources) {
    var pairs = new HashSet<String>();
    for (var d : trip.getDays())
      for (int n = 1; n < d.getItems().size(); n++) {
        var a = d.getItems().get(n - 1);
        var b = d.getItems().get(n);
        if (!"TRAFFIC".equals(a.getItemType()) && !"TRAFFIC".equals(b.getItemType()))
          pairs.add(d.getId() + ":" + a.getId() + ":" + b.getId());
      }
    return sources.stream()
        .map(
            s ->
                "ROUTE".equals(s.kind())
                    ? new Source(
                        s.id(),
                        s.dayId(),
                        s.kind(),
                        s.status(),
                        s.summary(),
                        s.retrievedAt(),
                        s.issuedAt(),
                        s.legs().stream()
                            .filter(l -> pairs.contains(s.dayId() + ":" + l.from() + ":" + l.to()))
                            .toList(),
                        s.wet())
                    : s)
        .toList();
  }

  private static External external(TripDetailVO trip, List<Source> sources) {
    int expected = 0, available = 0, weather = 0;
    for (var d : trip.getDays()) {
      for (int n = 1; n < d.getItems().size(); n++) {
        var a = d.getItems().get(n - 1);
        var b = d.getItems().get(n);
        if ("TRAFFIC".equals(a.getItemType())
            || "TRAFFIC".equals(b.getItemType())
            || "HOTEL".equals(a.getItemType())
            || "HOTEL".equals(b.getItemType())) continue;
        expected++;
        if (sources.stream()
            .anyMatch(
                s ->
                    Objects.equals(s.dayId(), d.getId())
                        && "ROUTE".equals(s.kind())
                        && Set.of("READY", "PARTIAL").contains(s.status())
                        && s.legs().stream()
                            .anyMatch(
                                l ->
                                    Objects.equals(a.getId(), l.from())
                                        && Objects.equals(b.getId(), l.to())))) available++;
      }
      if (sources.stream()
          .anyMatch(
              s ->
                  Objects.equals(s.dayId(), d.getId())
                      && "WEATHER".equals(s.kind())
                      && "READY".equals(s.status()))) weather++;
    }
    return new External(
        state(expected, available, sources, "ROUTE"),
        state(trip.getDays().size(), weather, sources, "WEATHER"));
  }

  private static ExternalState state(
      int expected, int available, List<Source> sources, String kind) {
    String status =
        expected == 0
            ? "NOT_APPLICABLE"
            : available == expected ? "READY" : available > 0 ? "PARTIAL" : "UNKNOWN";
    return new ExternalState(
        status,
        expected,
        available,
        sources.stream()
            .filter(s -> kind.equals(s.kind()) && Set.of("READY", "PARTIAL").contains(s.status()))
            .map(Source::id)
            .toList(),
        "不参与核心总分；仅覆盖有效已测日期或路段，未测部分不能判断");
  }
}

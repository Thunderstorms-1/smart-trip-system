package com.smarttrip.service.quality;

import com.smarttrip.entity.*;
import com.smarttrip.service.ItineraryAnalysisService.Source;
import java.math.RoundingMode;
import java.util.*;

/** Side-effect-free interval arithmetic; half-open local-day intervals. */
public final class TimelineMetrics {
  public record Interval(int start, int end, Long itemId, String type) {}

  public record Timeline(
      QualityReport.Coverage coverage,
      QualityReport.Metrics metrics,
      List<Interval> intervals,
      List<Interval> active,
      List<Interval> busy,
      List<Interval> food) {
    public boolean complete() {
      return coverage.total() > 0 && coverage.complete() == coverage.total();
    }
  }

  public static Integer start(TripItem item) {
    String value = item.getStartTime();
    if (value == null || !value.matches("([01][0-9]|2[0-3]):[0-5][0-9]")) return null;
    return Integer.parseInt(value.substring(0, 2)) * 60 + Integer.parseInt(value.substring(3));
  }

  public static Integer duration(TripItem item) {
    if (item.getCostTimeHours() == null || item.getCostTimeHours().signum() <= 0) return null;
    return item.getCostTimeHours()
        .multiply(java.math.BigDecimal.valueOf(60))
        .setScale(0, RoundingMode.HALF_UP)
        .intValueExact();
  }

  public static int length(List<Interval> intervals) {
    int sum = 0, start = -1, end = -1;
    for (var i : sorted(intervals)) {
      if (start < 0) {
        start = i.start();
        end = i.end();
      } else if (i.start() <= end) end = Math.max(end, i.end());
      else {
        sum += end - start;
        start = i.start();
        end = i.end();
      }
    }
    return sum + (start < 0 ? 0 : end - start);
  }

  public static List<Interval> sorted(List<Interval> input) {
    return input.stream()
        .sorted(
            Comparator.comparingInt(Interval::start)
                .thenComparing(i -> i.itemId(), Comparator.nullsLast(Long::compareTo)))
        .toList();
  }

  public static int windowLength(List<Interval> input, int from, int to) {
    return length(
        input.stream()
            .filter(i -> i.start() < to && i.end() > from)
            .map(
                i ->
                    new Interval(
                        Math.max(from, i.start()), Math.min(to, i.end()), i.itemId(), i.type()))
            .toList());
  }

  public static int longestUnallocated(List<Interval> input, int from, int to) {
    int cursor = from, best = 0;
    for (var i : sorted(input)) {
      if (i.end() <= from || i.start() >= to) continue;
      best = Math.max(best, Math.max(0, Math.min(to, i.start()) - cursor));
      cursor = Math.max(cursor, Math.min(to, i.end()));
    }
    return Math.max(best, to - cursor);
  }

  private static int continuous(List<Interval> busy, List<Interval> food, QualityPolicy p) {
    int from = -1, end = -1, best = 0;
    for (var i : sorted(busy)) {
      if (from < 0) {
        from = i.start();
        end = i.end();
        continue;
      }
      int previousEnd = end;
      boolean meal =
          food.stream()
              .anyMatch(
                  f ->
                      f.start() >= previousEnd
                          && f.end() <= i.start()
                          && f.end() - f.start() >= p.breakMinutes());
      if (i.start() - end >= p.breakMinutes() || meal) {
        best = Math.max(best, end - from);
        from = i.start();
        end = i.end();
      } else end = Math.max(end, i.end());
    }
    return Math.max(best, from < 0 ? 0 : end - from);
  }

  public static Timeline of(TripDayVO day, List<Source> sources, QualityPolicy policy) {
    var intervals = new ArrayList<Interval>();
    var missing = new ArrayList<Long>();
    var overnight = new ArrayList<Long>();
    int relevant = 0,
        complete = 0,
        scenic = 0,
        count = 0,
        planned = 0,
        foodMinutes = 0,
        traffic = 0;
    for (var item : day.getItems()) {
      String type = item.getItemType();
      if ("SCENIC".equals(type)) scenic++;
      boolean activity = "SCENIC".equals(type) || "OTHER".equals(type);
      if (activity) count++;
      Integer duration = duration(item), start = start(item);
      if (activity && duration != null) planned += duration;
      if ("FOOD".equals(type) && duration != null) foodMinutes += duration;
      if ("TRAFFIC".equals(type) && duration != null) traffic += duration;
      if ("HOTEL".equals(type)) continue;
      relevant++;
      if (duration == null || start == null) {
        missing.add(item.getId());
        continue;
      }
      if (start + duration > 1440) {
        overnight.add(item.getId());
        continue;
      }
      complete++;
      intervals.add(new Interval(start, start + duration, item.getId(), type));
    }
    var active =
        intervals.stream().filter(i -> Set.of("SCENIC", "OTHER").contains(i.type())).toList();
    var busy = intervals.stream().filter(i -> !"FOOD".equals(i.type())).toList();
    var food = intervals.stream().filter(i -> "FOOD".equals(i.type())).toList();
    var ordered = sorted(intervals);
    Integer span =
        ordered.isEmpty()
            ? null
            : ordered.stream().mapToInt(Interval::end).max().getAsInt() - ordered.get(0).start();
    var gaps = new ArrayList<QualityReport.Gap>();
    // Only itinerary adjacency can use a measured leg, never a fabricated temporal adjacency.
    var items = day.getItems();
    for (int n = 1; n < items.size(); n++) {
      var a = items.get(n - 1);
      var b = items.get(n);
      Integer st = start(a), du = duration(a), next = start(b);
      if (st == null
          || du == null
          || next == null
          || st + du > 1440
          || "HOTEL".equals(a.getItemType())
          || "HOTEL".equals(b.getItemType())) continue;
      Integer route = null;
      if (!"TRAFFIC".equals(a.getItemType()) && !"TRAFFIC".equals(b.getItemType()))
        for (var source : sources)
          if (Objects.equals(source.dayId(), day.getId())
              && "ROUTE".equals(source.kind())
              && Set.of("READY", "PARTIAL").contains(source.status()))
            for (var leg : source.legs())
              if (Objects.equals(leg.from(), a.getId())
                  && Objects.equals(leg.to(), b.getId())
                  && Double.isFinite(leg.seconds())
                  && leg.seconds() >= 0) route = (int) Math.ceil(leg.seconds() / 60);
      int gap = next - st - du;
      gaps.add(
          new QualityReport.Gap(
              a.getId(), b.getId(), gap, route, route == null ? null : gap - route));
    }
    return new Timeline(
        new QualityReport.Coverage(
            relevant,
            complete,
            List.copyOf(missing),
            List.copyOf(overnight),
            relevant == 0 ? 0 : complete * 100 / relevant),
        new QualityReport.Metrics(
            items.size(),
            scenic,
            count,
            planned,
            length(active),
            foodMinutes,
            traffic,
            span,
            continuous(busy, food, policy),
            span == null ? 0 : span - length(intervals),
            List.copyOf(gaps)),
        List.copyOf(intervals),
        active,
        busy,
        food);
  }
}

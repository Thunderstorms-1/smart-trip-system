package com.smarttrip.service.quality;

import com.smarttrip.common.BusinessException;
import com.smarttrip.entity.TripDetailVO;
import com.smarttrip.service.ItineraryAnalysisService;
import com.smarttrip.service.ItineraryAnalysisService.Source;
import java.util.*;

public record QualityComparison(
    int schemaVersion,
    QualityPolicy policy,
    QualityReport before,
    QualityReport after,
    List<String> resolved,
    List<String> remaining,
    List<String> added) {
  public static QualityComparison compute(
      TripDetailVO before, TripDetailVO after, List<Source> sources, QualityPolicy policy) {
    var engine = new ItineraryQualityEngine();
    var a = engine.evaluate(before, sources, policy);
    var b = engine.evaluate(after, sources, policy);
    var old = ids(a);
    var next = ids(b);
    return new QualityComparison(
        2,
        policy,
        a,
        b,
        old.stream().filter(i -> !next.contains(i)).toList(),
        next.stream().filter(old::contains).toList(),
        next.stream().filter(i -> !old.contains(i)).toList());
  }

  private static List<String> ids(QualityReport r) {
    return r.findings().stream().map(QualityReport.Finding::id).toList();
  }

  public void validate(TripDetailVO old, TripDetailVO next, List<Source> sources, boolean changed) {
    if (!changed) return;
    var dayMap = new HashMap<Long, QualityReport.Day>();
    before.days().forEach(d -> dayMap.put(d.dayId(), d));
    for (var d : after.days()) {
      var previous = dayMap.get(d.dayId());
      if (d.coverage().complete() < previous.coverage().complete()
          || d.coverage().total() != previous.coverage().total()) {
        // Across-day movement changes denominators legitimately; compare missing IDs globally
        // below.
        if (d.coverage().complete() < d.coverage().total()
            && previous.coverage().complete() == previous.coverage().total())
          reject("调整使日程出现新的时间信息缺失");
      }
      if ("OVERLOADED".equals(d.loadLevel())
          && !"OVERLOADED".equals(previous.loadLevel())
          && !(previous.metrics().occupiedActivityMinutes() > policy.activityHigh()))
        reject("调整新增了过载日程");
    }
    int oldComplete = before.days().stream().mapToInt(d -> d.coverage().complete()).sum(),
        newComplete = after.days().stream().mapToInt(d -> d.coverage().complete()).sum();
    if (newComplete < oldComplete) reject("调整降低了时间信息覆盖");
    boolean improved = newComplete > oldComplete;
    for (int n = 0; n < before.dimensions().size(); n++) {
      var a = before.dimensions().get(n);
      var b = after.dimensions().get(n);
      if ("READY".equals(a.status())) {
        if ("UNKNOWN".equals(b.status())) reject("调整使已有可评价维度变为未知");
        if ("READY".equals(b.status())) {
          if (b.score() < a.score()) reject("调整降低了已知质量分项");
          if (b.score() > a.score()) improved = true;
        }
      }
    }
    // An unselected incomplete day must not hide a real improvement on a complete day.
    for (String key : List.of("TIME", "PACE", "MEAL_REST")) {
      int previousScore = 0, nextScore = 0;
      for (var day : after.days()) {
        var a =
            dayMap.get(day.dayId()).dimensions().stream()
                .filter(d -> key.equals(d.key()))
                .findFirst()
                .orElseThrow();
        var b =
            day.dimensions().stream().filter(d -> key.equals(d.key())).findFirst().orElseThrow();
        if ("READY".equals(a.status()) && "UNKNOWN".equals(b.status())) reject("调整使已有可评价日程变为未知");
        if ("READY".equals(a.status()) && "READY".equals(b.status())) {
          previousScore += a.score();
          nextScore += b.score();
        }
      }
      if (nextScore < previousScore) reject("调整降低了可比较日程的已知分项");
      if (nextScore > previousScore) improved = true;
    }
    var days = old.getDays().stream().map(com.smarttrip.entity.TripDayVO::getId).toList();
    var oldErrors =
        ItineraryAnalysisService.diagnose(
                old, days, ItineraryQualityEngine.effectiveSources(old, sources))
            .stream()
            .filter(i -> "ERROR".equals(i.severity()))
            .map(ItineraryAnalysisService.Issue::id)
            .collect(java.util.stream.Collectors.toSet());
    var newErrors =
        ItineraryAnalysisService.diagnose(
                next, days, ItineraryQualityEngine.effectiveSources(next, sources))
            .stream()
            .filter(i -> "ERROR".equals(i.severity()))
            .map(ItineraryAnalysisService.Issue::id)
            .collect(java.util.stream.Collectors.toSet());
    if (!oldErrors.containsAll(newErrors)) reject("调整新增了确定性的时间冲突");
    if (newErrors.size() < oldErrors.size()) improved = true;
    if (!improved) reject("当前提案没有可验证的质量改善，请手动调整或重新选择日期");
  }

  private static void reject(String message) {
    throw new BusinessException(422, message);
  }
}

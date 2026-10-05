package com.smarttrip.service.quality;

import java.util.List;

public record QualityReport(
    int schemaVersion,
    String rulesVersion,
    String profile,
    String scope,
    Long baseVersion,
    List<Long> dayIds,
    String status,
    Integer totalScore,
    List<Dimension> dimensions,
    List<Day> days,
    List<Finding> findings,
    List<com.smarttrip.service.ItineraryAnalysisService.Issue> basicIssues,
    External external) {
  public record Dimension(
      String key,
      String status,
      Integer score,
      int weight,
      List<String> deductionIds,
      List<String> unknownReasons) {}

  public record Evidence(String metric, double actual, double threshold, String unit) {}

  public record Finding(
      String id,
      String ruleId,
      String severity,
      List<Long> dayIds,
      List<Long> itemIds,
      String message,
      List<Evidence> evidence,
      int penalty,
      List<String> evidenceIds,
      String action) {}

  public record Coverage(
      int total,
      int complete,
      List<Long> missingItemIds,
      List<Long> overnightItemIds,
      int percent) {}

  public record Gap(
      Long from, Long to, int plannedMinutes, Integer routeMinutes, Integer remainingMinutes) {}

  public record Metrics(
      int nodeCount,
      int scenicCount,
      int activityCount,
      int plannedActivityMinutes,
      int occupiedActivityMinutes,
      int scheduledFoodMinutes,
      int scheduledTrafficMinutes,
      Integer daySpanMinutes,
      int longestContinuousMinutes,
      int unallocatedMinutes,
      List<Gap> gaps) {}

  public record Day(
      Long dayId,
      int dayIndex,
      String loadLevel,
      Coverage coverage,
      Metrics metrics,
      List<Dimension> dimensions,
      List<String> findingIds) {}

  public record ExternalState(
      String status, int expected, int available, List<String> sourceIds, String reason) {}

  public record External(ExternalState route, ExternalState weather) {}
}

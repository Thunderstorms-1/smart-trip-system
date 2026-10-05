package com.smarttrip.service.quality;

/** Immutable snapshot carried with each V2 proposal. Thresholds are product heuristics. */
import java.util.Map;

public record QualityPolicy(
    String rulesVersion,
    int breakMinutes,
    int foodMinutes,
    int continuousWarning,
    int continuousHigh,
    int activityWarning,
    int activityHigh,
    int spanWarning,
    int spanHigh,
    int lunchStart,
    int lunchEnd,
    int dinnerStart,
    int dinnerEnd,
    int mealOccupied,
    int gapReserve,
    int balanceWarning,
    int balanceHigh,
    Map<String, Integer> parameters) {
  public QualityPolicy {
    parameters = Map.copyOf(parameters);
  }

  public int value(String key) {
    return parameters.get(key);
  }

  public static QualityPolicy defaults() {
    return new QualityPolicy(
        "quality-v1",
        30,
        20,
        180,
        300,
        480,
        600,
        660,
        780,
        690,
        840,
        1050,
        1200,
        90,
        15,
        240,
        360,
        Map.ofEntries(
            Map.entry("weight.TIME", 40),
            Map.entry("weight.PACE", 25),
            Map.entry("weight.MEAL_REST", 20),
            Map.entry("weight.BALANCE", 15),
            Map.entry("spanActivityMinimum", 360),
            Map.entry("scenicMinimum", 6),
            Map.entry("gapPercent", 15),
            Map.entry("lightActivity", 240),
            Map.entry("lightSpan", 360),
            Map.entry("overlapPenalty", 20),
            Map.entry("overlapCap", 60),
            Map.entry("orderPenalty", 10),
            Map.entry("orderCap", 30),
            Map.entry("activityPenalty", 15),
            Map.entry("activityHighPenalty", 30),
            Map.entry("spanPenalty", 10),
            Map.entry("spanHighPenalty", 20),
            Map.entry("densePenalty", 10),
            Map.entry("continuousPenalty", 10),
            Map.entry("continuousHighPenalty", 20),
            Map.entry("mealPenalty", 10),
            Map.entry("balancePenalty", 20),
            Map.entry("balanceHighPenalty", 35)));
  }

  public int weight(String key) {
    return parameters.getOrDefault("weight." + key, 0);
  }
}

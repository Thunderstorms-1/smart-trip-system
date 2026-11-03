package com.smarttrip.rag.knowledge;

import com.smarttrip.entity.TravelRequirement;
import java.time.LocalDate;

/** Applicability is independent of publication freshness. All ranges include both endpoints. */
public final class KnowledgeValidity {
  private KnowledgeValidity() {}
  public record Range(LocalDate from, LocalDate until) {}
  public record Assessment(String status, boolean usable, LocalDate applicableFrom, LocalDate applicableUntil) {}
  public static Range window(String from, String until) {
    if (from == null && until == null) return null;
    if (from == null || until == null || !from.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")
        || !until.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))
      throw new IllegalArgumentException("Validity requires two ISO dates");
    var range = new Range(LocalDate.parse(from), LocalDate.parse(until));
    if (range.from().isAfter(range.until())) throw new IllegalArgumentException("Validity is reversed");
    return range;
  }
  public static Range travelDates(TravelRequirement requirement) {
    if (requirement == null || requirement.startDate() == null || requirement.days() == null || requirement.days() < 1)
      return null;
    try {
      LocalDate start = LocalDate.parse(requirement.startDate());
      return new Range(start, start.plusDays((long)requirement.days() - 1));
    } catch (RuntimeException ex) { return null; }
  }
  public static Assessment assess(KnowledgeChunk chunk, Range travel) {
    Range valid;
    try { valid = window(chunk.validFrom(), chunk.validUntil()); }
    catch (RuntimeException ex) { return new Assessment("INVALID_VALIDITY", false, null, null); }
    if (valid == null) return new Assessment("UNBOUNDED", true, null, null);
    if (travel == null) return new Assessment("TRAVEL_DATE_UNKNOWN", false, null, null);
    if (travel.until().isBefore(valid.from()) || travel.from().isAfter(valid.until()))
      return new Assessment("OUTSIDE_VALIDITY", false, null, null);
    LocalDate from = travel.from().isBefore(valid.from()) ? valid.from() : travel.from();
    LocalDate until = travel.until().isAfter(valid.until()) ? valid.until() : travel.until();
    boolean full = from.equals(travel.from()) && until.equals(travel.until());
    return new Assessment(full ? "APPLICABLE" : "PARTIALLY_APPLICABLE", true, from, until);
  }
}

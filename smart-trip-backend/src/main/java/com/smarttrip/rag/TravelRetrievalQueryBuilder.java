package com.smarttrip.rag;

import com.smarttrip.config.RagProperties;
import com.smarttrip.entity.TravelRequirement;
import com.smarttrip.rag.knowledge.KnowledgeValidity;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.*;
import org.springframework.stereotype.Component;

/** Deterministic retrieval from confirmed fields; never calls a generation model. */
@Component
public class TravelRetrievalQueryBuilder {
  public record Query(String text, List<String> destinations, List<String> hubs,
                      List<String> seasons, List<String> generalCategories, List<Object> accessFilters, List<Object> filters, KnowledgeValidity.Range travelDates) {}
  private final RagProperties properties;
  public TravelRetrievalQueryBuilder(RagProperties properties) { this.properties = properties; }

  public Query build(TravelRequirement r) {
    if (r == null) throw new IllegalArgumentException("Confirmed requirement is required");
    var destinations = new LinkedHashSet<String>();
    if (r.destinations() != null) r.destinations().stream().filter(this::known).forEach(destinations::add);
    // Explicit canonical aliases only; broad provinces are deliberately not scenic scopes.
    boolean daochengScope = destinations.contains("稻城亚丁") || destinations.contains("稻城")
        || destinations.stream().anyMatch(value -> value.matches(
            "四川(?:省)?(?:的|·|\\s)?(?:稻城)?亚丁|亚丁[（(]四川(?:省)?[）)]"));
    if (daochengScope) {
      destinations.add("稻城亚丁"); destinations.add("稻城"); destinations.add("亚丁");
    }
    var hubs = new LinkedHashSet<String>();
    if (known(r.origin())) hubs.add(r.origin());
    if (destinations.contains("稻城亚丁")) { hubs.add("成都"); hubs.add("康定"); }
    var seasons = seasons(r);
    var phrases = new ArrayList<String>(destinations);
    add(phrases, r.interests()); add(phrases, r.mustVisit());
    add(phrases, r.pace()); add(phrases, r.exertion()); add(phrases, r.companions());
    add(phrases, r.transport()); add(phrases, r.timeDescription());
    add(phrases, r.timeConstraints()); add(phrases, r.notes());
    if (r.avoid() != null) r.avoid().stream().filter(this::known).forEach(x -> phrases.add("避免 " + x));
    phrases.addAll(seasons);
    String cues = String.join(" ", phrases);
    var generalCategories = new LinkedHashSet<String>();
    if (known(r.pace()) || known(r.companions()) || known(r.exertion())
        || cues.matches(".*(轻松|慢游|慢节奏|不赶路|强度|长辈|老人|午休|休息|节奏).*"))
      generalCategories.add("GENERAL");
    if (known(r.transport()) || known(r.origin())
        || cues.matches(".*(交通|换乘|接驳|火车|飞机|航班|自驾|行李|跨城).*"))
      generalCategories.add("TRANSPORT");
    if (destinations.contains("稻城亚丁") || cues.matches(".*(高海拔|高原|海拔|高反|缺氧).*"))
      generalCategories.add("SAFETY");
    if ("RELAXED".equals(r.pace())) phrases.add("轻松 慢游 休息 旅行节奏");
    if ("BALANCED".equals(r.pace())) phrases.add("均衡 适中 旅行节奏");
    if ("PACKED".equals(r.pace())) phrases.add("紧凑 行程安排 旅行节奏");
    if ("INTENSE".equals(r.pace()) || "INTENSIVE".equals(r.pace())) phrases.add("高强度 旅行节奏");
    // Budget, number of travelers, accommodation price and private free-form description stay
    // in the generation requirement; they are not eligibility filters for factual knowledge.
    var travelDates = KnowledgeValidity.travelDates(r);
    var access = accessFilters();
    var filter = new ArrayList<Object>(access);
    // Unbounded knowledge is eligible as before; annual references require a known overlapping trip.
    Object unbounded = Map.of("bool", Map.of("must_not", List.of(
        Map.of("exists", Map.of("field", "validFrom")), Map.of("exists", Map.of("field", "validUntil")))));
    if (travelDates == null) filter.add(unbounded);
    else filter.add(Map.of("bool", Map.of("should", List.of(unbounded,
        Map.of("bool", Map.of("filter", List.of(
            Map.of("range", Map.of("validFrom", Map.of("lte", travelDates.until().toString()))),
            Map.of("range", Map.of("validUntil", Map.of("gte", travelDates.from().toString()))))))),
        "minimum_should_match", 1)));
    filter.add(Map.of("exists", Map.of("field", "sourceName")));
    filter.add(Map.of("exists", Map.of("field", "sourceUrl")));
    filter.add(Map.of("bool", Map.of("should", List.of(
        Map.of("range", Map.of("sourceUpdatedAt", Map.of(
            "gte", LocalDate.now().minusDays(Math.max(1, properties.getMaxSourceAgeDays())).toString(),
            "lte", LocalDate.now().plusDays(1).toString()))),
        Map.of("bool", Map.of("filter", List.of(term("category", "CULTURE"),
            Map.of("range", Map.of("sourceUpdatedAt", Map.of("lte", LocalDate.now().plusDays(1).toString())))))),
        Map.of("bool", Map.of("must_not", List.of(Map.of("exists", Map.of("field", "sourceUpdatedAt")))))
        ), "minimum_should_match", 1)));
    var geo = new ArrayList<Object>();
    if (!destinations.isEmpty()) geo.add(terms("destinations", destinations));
    if (!hubs.isEmpty()) geo.add(Map.of("bool", Map.of("filter", List.of(
        terms("destinations", hubs), term("category", "TRANSPORT")))));
    if (!generalCategories.isEmpty()) geo.add(Map.of("bool", Map.of("filter",
        List.of(term("destinations", "GENERAL"), terms("category", generalCategories)))));
    if (geo.isEmpty()) geo.add(Map.of("match_none", Map.of()));
    filter.add(Map.of("bool", Map.of("should", geo, "minimum_should_match", 1)));
    if (!seasons.isEmpty()) {
      var allowed = new LinkedHashSet<>(seasons); allowed.add("ALL");
      filter.add(terms("applicableSeasons", allowed));
    }
    return new Query(String.join(" ", phrases), List.copyOf(destinations), List.copyOf(hubs),
        seasons, List.copyOf(generalCategories), List.copyOf(access), List.copyOf(filter), travelDates);
  }

  /** Shared public-corpus scope for retrieval and authenticated read-only diagnostics. */
  public List<Object> accessFilters() {
    var access = new ArrayList<Object>();
    access.add(term("visibility", "PUBLIC"));
    access.add(term("authorized", true));
    access.add(Map.of("bool", Map.of("must_not", List.of(Map.of("exists", Map.of("field", "ownerId"))))));
    if (!properties.isIncludeTestKnowledge()) access.add(term("testOnly", false));
    access.add(term("embeddingModel", properties.getEmbeddingModel()));
    access.add(term("embeddingVersion", properties.getEmbeddingVersion()));
    return List.copyOf(access);
  }

  private List<String> seasons(TravelRequirement r) {
    var seasons = new LinkedHashSet<String>();
    if (known(r.startDate())) {
      try { seasons.add(season(LocalDate.parse(r.startDate()).getMonthValue())); }
      catch (java.time.DateTimeException ignored) { /* preserve uncertainty */ }
    }
    var dates = KnowledgeValidity.travelDates(r);
    if (dates != null) {
      LocalDate month = dates.from().withDayOfMonth(1);
      for (int i = 0; i < 12 && !month.isAfter(dates.until()); i++, month = month.plusMonths(1))
        seasons.add(season(month.getMonthValue()));
    }
    String time = r.timeDescription();
    if (known(time)) {
      if (time.matches(".*(春|SPRING).*")) seasons.add("SPRING");
      if (time.matches(".*(夏|SUMMER).*")) seasons.add("SUMMER");
      if (time.matches(".*(秋|AUTUMN|FALL).*")) seasons.add("AUTUMN");
      if (time.matches(".*(冬|WINTER).*")) seasons.add("WINTER");
      var matcher = Pattern.compile("(?<![0-9])(1[0-2]|[1-9])\\s*月").matcher(time);
      while (matcher.find()) seasons.add(season(Integer.parseInt(matcher.group(1))));
    }
    return List.copyOf(seasons);
  }
  private String season(int m) { return m >= 3 && m <= 5 ? "SPRING" : m >= 6 && m <= 8
      ? "SUMMER" : m >= 9 && m <= 11 ? "AUTUMN" : "WINTER"; }
  private boolean known(String s) { return s != null && !s.isBlank() && !"UNKNOWN".equalsIgnoreCase(s); }
  private void add(List<String> out, String s) { if (known(s)) out.add(s); }
  private void add(List<String> out, List<String> values) { if (values != null) values.forEach(x -> add(out, x)); }
  private static Object term(String key, Object value) { return Map.of("term", Map.of(key, value)); }
  private static Object terms(String key, Collection<String> values) { return Map.of("terms", Map.of(key, values)); }
}

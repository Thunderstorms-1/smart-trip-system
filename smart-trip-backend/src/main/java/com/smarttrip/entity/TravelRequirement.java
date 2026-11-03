package com.smarttrip.entity;

import java.math.BigDecimal;
import java.util.List;

/** User-confirmed planning input; null and UNKNOWN deliberately retain uncertainty. */
public record TravelRequirement(
    String description,
    String origin,
    List<String> destinations,
    Integer days,
    String startDate,
    String timeDescription,
    Boolean includesTravelDays,
    Integer travelers,
    String companions,
    BigDecimal budget,
    BigDecimal budgetMax,
    String budgetScope,
    String currency,
    List<String> budgetIncludes,
    List<String> interests,
    String pace,
    String exertion,
    String transport,
    String accommodation,
    List<String> mustVisit,
    List<String> avoid,
    String timeConstraints,
    String notes) {}

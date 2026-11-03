package com.smarttrip.rag.knowledge;

import java.util.List;

/** Operator-supplied manifest metadata. Paths are local and resolved strictly beneath the manifest directory. */
public record KnowledgeDocument(String documentId, String title, String path,
    List<String> destinations, List<String> regions, String category, List<String> applicableSeasons,
    String sourceName, String sourceUrl, String sourceUpdatedAt, String visibility, Long ownerId,
    String documentVersion, boolean authorized, boolean testOnly,
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String validFrom,
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String validUntil) {
  public KnowledgeDocument(String documentId, String title, String path, List<String> destinations,
      List<String> regions, String category, List<String> applicableSeasons, String sourceName,
      String sourceUrl, String sourceUpdatedAt, String visibility, Long ownerId,
      String documentVersion, boolean authorized, boolean testOnly) {
    this(documentId, title, path, destinations, regions, category, applicableSeasons, sourceName,
        sourceUrl, sourceUpdatedAt, visibility, ownerId, documentVersion, authorized, testOnly, null, null);
  }
}
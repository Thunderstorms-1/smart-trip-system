package com.smarttrip.rag.knowledge;

import java.util.List;

public record KnowledgeChunk(
    String documentId, String chunkId, String title, String content,
    List<String> destinations, List<String> regions, String category,
    List<String> applicableSeasons, String sourceName, String sourceUrl,
    String sourceUpdatedAt, String ingestedAt, String visibility, Long ownerId,
    String documentVersion, String contentHash, String embeddingModel,
    String embeddingVersion, boolean authorized, boolean testOnly,
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String validFrom,
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String validUntil) {
  public KnowledgeChunk(String documentId, String chunkId, String title, String content,
      List<String> destinations, List<String> regions, String category, List<String> applicableSeasons,
      String sourceName, String sourceUrl, String sourceUpdatedAt, String ingestedAt, String visibility,
      Long ownerId, String documentVersion, String contentHash, String embeddingModel,
      String embeddingVersion, boolean authorized, boolean testOnly) {
    this(documentId, chunkId, title, content, destinations, regions, category, applicableSeasons,
        sourceName, sourceUrl, sourceUpdatedAt, ingestedAt, visibility, ownerId, documentVersion,
        contentHash, embeddingModel, embeddingVersion, authorized, testOnly, null, null);
  }
}
package com.smarttrip.rag;

import java.util.*;

/** Free-tier hybrid fusion in Java; branch ranks are one-based and deduplicated. */
public final class ReciprocalRankFusion {
  private ReciprocalRankFusion() {}
  public static List<RetrievalHit> fuse(List<RetrievalHit> bm25, List<RetrievalHit> vector, int k) {
    int constant = Math.max(1, k);
    var merged = new LinkedHashMap<String, RetrievalHit>();
    for (var hit : bm25) merged.putIfAbsent(hit.id(), hit);
    for (var hit : vector) merged.merge(hit.id(), hit, (a, b) ->
        new RetrievalHit(a.chunk(), a.bm25Rank(), b.vectorRank(), a.bm25Score(),
            b.vectorScore(), 0, null));
    var out = new ArrayList<RetrievalHit>();
    for (var hit : merged.values()) {
      double score = (hit.bm25Rank() == null ? 0 : 1.0 / (constant + hit.bm25Rank()))
          + (hit.vectorRank() == null ? 0 : 1.0 / (constant + hit.vectorRank()));
      out.add(new RetrievalHit(hit.chunk(), hit.bm25Rank(), hit.vectorRank(),
          hit.bm25Score(), hit.vectorScore(), score, null));
    }
    out.sort(Comparator.comparingDouble(RetrievalHit::rrfScore).reversed().thenComparing(RetrievalHit::id));
    return List.copyOf(out);
  }
}

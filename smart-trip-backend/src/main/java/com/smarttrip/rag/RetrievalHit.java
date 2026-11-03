package com.smarttrip.rag;

import com.smarttrip.rag.knowledge.KnowledgeChunk;

/** Scores are genuine branch/model scores; null means that scorer did not rank the chunk. */
public record RetrievalHit(KnowledgeChunk chunk, Integer bm25Rank, Integer vectorRank,
    Double bm25Score, Double vectorScore, double rrfScore, Double rerankScore) {
  public String id() { return chunk.chunkId(); }
  public RetrievalHit reranked(double score) {
    return new RetrievalHit(chunk, bm25Rank, vectorRank, bm25Score, vectorScore, rrfScore, score);
  }
}

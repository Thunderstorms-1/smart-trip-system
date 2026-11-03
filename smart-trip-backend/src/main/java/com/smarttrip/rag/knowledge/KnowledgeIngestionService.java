package com.smarttrip.rag.knowledge;

import com.smarttrip.config.RagProperties;
import com.smarttrip.rag.knowledge.KnowledgeDocumentReader.PreparedDocument;
import java.nio.file.Path;
import java.util.*;
import org.springframework.stereotype.Service;

/** Offline-only mutation service. No controller, scheduler or startup scan invokes it. */
@Service
public class KnowledgeIngestionService {
  private final KnowledgeDocumentReader reader;
  private final KnowledgeMetadataService metadata;
  private final KnowledgeIndexService index;
  private final ElasticsearchKnowledgeClient client;
  private final OllamaKnowledgeEmbedding embedding;
  private final RagProperties config;
  public record ImportResult(String status, int documentCount, int chunkCount, String physicalIndex, String importId) {}
  private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(KnowledgeIngestionService.class);
  public KnowledgeIngestionService(KnowledgeDocumentReader reader, KnowledgeMetadataService metadata, KnowledgeIndexService index,
      ElasticsearchKnowledgeClient client, OllamaKnowledgeEmbedding embedding, RagProperties config) {
    this.reader=reader; this.metadata=metadata; this.index=index; this.client=client; this.embedding=embedding; this.config=config;
  }
  public List<PreparedDocument> inspect(Path manifest) { return reader.readManifest(manifest); }
  public ImportResult importManifest(Path manifest, boolean rebuild) {
    List<PreparedDocument> documents=reader.readManifest(manifest);
    return mutate(rebuild ? "REBUILD":"IMPORT",documents,List.of(),rebuild);
  }
  public ImportResult deleteDocument(String documentId) {
    if(documentId==null || !documentId.matches("[A-Za-z0-9_-]{1,120}")) throw new RagException("DOCUMENT_METADATA_INVALID","文档ID无效");
    return mutate("DELETE",List.of(),List.of(documentId),false);
  }
  public ImportResult initialize() { return mutate("INIT",List.of(),List.of(),false); }
  private ImportResult mutate(String operation,List<PreparedDocument> requested,List<String> deleted,boolean rebuild) {
    String hash=KnowledgeDocumentReader.sha256(operation+requested.stream().map(d->d.document().documentId()+":"+d.contentHash()).toList()+deleted);
    String run=metadata.beginRun(operation,hash);
    final String[] staging={null};
    final List<String> previous=new ArrayList<>();
    final boolean[] publicationAttempted={false};
    try {
      ImportResult result = metadata.exclusively(client.indexName(),connection->{
        previous.addAll(index.currentIndices());
        if(!previous.isEmpty() && !rebuild) client.validateIndex(0);
        if("INIT".equals(operation) && !previous.isEmpty()) {
          metadata.completeRun(connection,run,0,index.count(previous.get(0)),previous.get(0),"UNCHANGED");
          return new ImportResult("UNCHANGED",0,index.count(previous.get(0)),previous.get(0),run);
        }
        List<PreparedDocument> changed=new ArrayList<>();
        for(PreparedDocument document:requested)
          if(rebuild || previous.isEmpty() || !metadata.unchanged(connection,document,embedding.model(),embedding.version())) changed.add(document);
        if(changed.isEmpty() && deleted.isEmpty() && !previous.isEmpty() && !rebuild) {
          int count=index.count(previous.get(0));
          metadata.completeRun(connection,run,requested.size(),count,previous.get(0),"UNCHANGED");
          return new ImportResult("UNCHANGED",requested.size(),count,previous.get(0),run);
        }
        if("DELETE".equals(operation) && previous.isEmpty()) {
          metadata.markDeleted(connection,deleted); metadata.completeRun(connection,run,0,0,null,"UNCHANGED");
          return new ImportResult("UNCHANGED",0,0,null,run);
        }
        // Only a real model response determines dimensions; no assumed BGE-M3 dimension.
        float[] probe = changed.isEmpty() && !previous.isEmpty() ? null
            : embedding.embed(changed.isEmpty()?"旅行知识索引维度确认":changed.get(0).chunks().get(0).content());
        // Revocation/deletion uses the already verified mapping and never waits for Ollama availability.
        int dimensions=probe==null?client.mappedDimensions():probe.length;
        if(!previous.isEmpty() && !rebuild) client.validateIndex(dimensions);
        staging[0]=index.createStaging(dimensions);
        Set<String> replaced=new LinkedHashSet<>(deleted);
        changed.forEach(d->replaced.add(d.document().documentId()));
        int expected=rebuild?0:index.copyUnchanged(previous,staging[0],replaced);
        boolean first=true;
        for(PreparedDocument document:changed) {
          for(KnowledgeChunk chunk:document.chunks()) {
            float[] vector=first?probe:embedding.embed(chunk.content()); first=false;
            index.putChunk(staging[0],chunk,vector,dimensions); expected++;
          }
          metadata.save(connection,document,staging[0],embedding.model(),embedding.version());
        }
        index.refresh(staging[0]);
        int actual=index.count(staging[0]);
        if(actual!=expected) throw new RagException("INDEX_WRITE_FAILED","新索引片段数量校验失败，保留旧索引");
        if(rebuild) {
          // Remove rows absent from a full replacement corpus, then reactivate the supplied documents.
          metadata.markAllDeleted(connection);
          for(PreparedDocument document:changed) metadata.save(connection,document,staging[0],embedding.model(),embedding.version());
        }
        metadata.markDeleted(connection,deleted); metadata.setActiveIndex(connection,staging[0]);
        metadata.completeRun(connection,run,requested.size(),actual,staging[0],"SUCCESS");
        publicationAttempted[0]=true;
        index.publish(previous,staging[0]);
        return new ImportResult("SUCCESS",requested.size(),actual,staging[0],run);
      }, () -> {
        // The metadata transaction still holds its MySQL lock while ES publication is reversed.
        if (publicationAttempted[0]) index.restore(previous, staging[0]);
        if (staging[0] != null) try { index.discard(staging[0]); } catch (RuntimeException ignored) { }
      });
      LOG.info("rag_ingest importId={} operation={} documentCount={} chunkCount={} status={}",run,operation,result.documentCount(),result.chunkCount(),result.status());
      return result;
    } catch (RuntimeException error) {
      String code=error instanceof RagException failure?failure.code():"INGESTION_FAILED";
      metadata.failRun(run,code);
      LOG.warn("rag_ingest importId={} operation={} status=FAILED reason={}",run,operation,code);
      if("INDEX_RECOVERY_REQUIRED".equals(code)) throw new RagException(code,"知识发布状态需人工核对；旧索引与新快照均已保留");
      if(error instanceof RagException failure) throw failure;
      throw new RagException(code,"知识导入失败，未替换旧知识快照");
    }
  }
}
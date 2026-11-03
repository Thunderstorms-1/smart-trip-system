package com.smarttrip.rag.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smarttrip.rag.knowledge.KnowledgeDocumentReader.PreparedDocument;
import java.sql.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeMetadataService {
  private final DataSource dataSource;
  private final ObjectMapper json = new ObjectMapper();
  public KnowledgeMetadataService(DataSource dataSource) { this.dataSource = dataSource; }
  @FunctionalInterface public interface Work<T> { T run(Connection connection) throws Exception; }
  /** MySQL advisory lock covers the complete ES publication and metadata transaction across CLI processes. */
  public <T> T exclusively(String indexAlias, Work<T> work, Runnable compensate) {
    String lockName = "smarttrip-rag:" + KnowledgeDocumentReader.sha256(indexAlias).substring(0, 40);
    T committedResult = null;
    boolean committed = false;
    try (Connection connection = dataSource.getConnection()) {
      try (PreparedStatement lock = connection.prepareStatement("SELECT GET_LOCK(?, 5)")) {
        lock.setString(1, lockName);
        try (ResultSet result = lock.executeQuery()) {
          if (!result.next() || result.getInt(1) != 1) throw new RagException("INGESTION_BUSY", "另一知识管理任务正在运行");
        }
      }
      boolean commitAttempted = false;
      try {
        connection.setAutoCommit(false);
        T result = work.run(connection);
        commitAttempted = true;
        connection.commit();
        committedResult = result;
        committed = true;
        return result;
      } catch (Exception error) {
        try { connection.rollback(); } catch (SQLException ignored) { }
        // A lost COMMIT response cannot tell us whether MySQL committed. Reversing ES now
        // could leave committed metadata pointing at a deleted snapshot. Retain both snapshots.
        if (commitAttempted)
          throw new RagException("INDEX_RECOVERY_REQUIRED", "元数据提交结果未确认；知识快照均已保留，请人工核对");
        // Compensate before releasing the cross-process lock; a later importer must never be reverted.
        try { compensate.run(); } catch (RuntimeException recovery) {
          throw new RagException("INDEX_RECOVERY_REQUIRED", "知识发布状态需人工核对；旧索引与新快照均已保留");
        }
        if (error instanceof RagException failure) throw failure;
        throw new RagException("METADATA_UNAVAILABLE", "知识管理元数据事务未完成");
      } finally {
        try (PreparedStatement release = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
          release.setString(1, lockName); release.execute();
        } catch (SQLException ignored) { }
      }
    } catch (RagException e) { throw e; }
    catch (SQLException e) {
      // A connection-close failure must not turn a confirmed publication and COMMIT into FAILED.
      if (committed) return committedResult;
      throw new RagException("METADATA_UNAVAILABLE", "知识管理数据库不可用");
    }
  }
  public String beginRun(String operation, String manifestHash) {
    String run = UUID.randomUUID().toString();
    try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(
        "INSERT INTO rag_knowledge_import(import_id,operation,manifest_hash,status,started_at) VALUES(?,?,?,'RUNNING',NOW(3))")) {
      statement.setString(1, run); statement.setString(2, operation); statement.setString(3, manifestHash); statement.executeUpdate();
      return run;
    } catch (SQLException e) { throw new RagException("METADATA_UNAVAILABLE", "无法记录知识导入任务，请先执行数据库迁移"); }
  }
  public boolean unchanged(Connection connection, PreparedDocument doc, String model, String version) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(
        "SELECT content_hash,embedding_model,embedding_version,status FROM rag_knowledge_document WHERE document_id=?")) {
      statement.setString(1, doc.document().documentId());
      try (ResultSet result = statement.executeQuery()) {
        return result.next() && doc.contentHash().equals(result.getString(1)) && model.equals(result.getString(2))
            && version.equals(result.getString(3)) && "ACTIVE".equals(result.getString(4));
      }
    }
  }
  public void save(Connection connection, PreparedDocument doc, String physicalIndex, String model, String version) throws Exception {
    KnowledgeDocument metadata = doc.document();
    try (PreparedStatement statement = connection.prepareStatement("""
        INSERT INTO rag_knowledge_document(document_id,document_version,title,original_path,source_name,source_url,
        source_updated_at,metadata_json,content_hash,embedding_model,embedding_version,chunk_count,status,active_index,ingested_at,updated_at)
        VALUES(?,?,?,?,?,?,?,?,?,?,?,?,'ACTIVE',?,NOW(3),NOW(3))
        ON DUPLICATE KEY UPDATE document_version=VALUES(document_version),title=VALUES(title),original_path=VALUES(original_path),
        source_name=VALUES(source_name),source_url=VALUES(source_url),source_updated_at=VALUES(source_updated_at),
        metadata_json=VALUES(metadata_json),content_hash=VALUES(content_hash),embedding_model=VALUES(embedding_model),
        embedding_version=VALUES(embedding_version),chunk_count=VALUES(chunk_count),status='ACTIVE',
        active_index=VALUES(active_index),ingested_at=NOW(3),updated_at=NOW(3)
        """)) {
      int i=1;
      statement.setString(i++, metadata.documentId()); statement.setString(i++, metadata.documentVersion());
      statement.setString(i++, metadata.title()); statement.setString(i++, metadata.path());
      statement.setString(i++, metadata.sourceName()); statement.setString(i++, metadata.sourceUrl());
      statement.setString(i++, metadata.sourceUpdatedAt()); statement.setString(i++, json.writeValueAsString(metadata));
      statement.setString(i++, doc.contentHash()); statement.setString(i++, model); statement.setString(i++, version);
      statement.setInt(i++, doc.chunks().size()); statement.setString(i, physicalIndex); statement.executeUpdate();
    }
  }
  public void setActiveIndex(Connection connection, String index) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("UPDATE rag_knowledge_document SET active_index=?,updated_at=NOW(3) WHERE status='ACTIVE'")) {
      statement.setString(1,index); statement.executeUpdate();
    }
  }
  public void markDeleted(Connection connection, Collection<String> ids) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("UPDATE rag_knowledge_document SET status='DELETED',active_index=NULL,updated_at=NOW(3) WHERE document_id=?")) {
      for (String id:ids) { statement.setString(1,id); statement.addBatch(); } statement.executeBatch();
    }
  }
  public void markAllDeleted(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.executeUpdate("UPDATE rag_knowledge_document SET status='DELETED',active_index=NULL,updated_at=NOW(3) WHERE status='ACTIVE'");
    }
  }
  public void completeRun(Connection connection, String run, int docs, int chunks, String index, String status) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("UPDATE rag_knowledge_import SET status=?,document_count=?,chunk_count=?,physical_index=?,finished_at=NOW(3) WHERE import_id=?")) {
      statement.setString(1,status); statement.setInt(2,docs); statement.setInt(3,chunks); statement.setString(4,index); statement.setString(5,run); statement.executeUpdate();
    }
  }
  public void failRun(String run, String code) {
    try (Connection connection=dataSource.getConnection(); PreparedStatement statement=connection.prepareStatement(
        "UPDATE rag_knowledge_import SET status='FAILED',error_code=?,finished_at=NOW(3) WHERE import_id=?")) {
      statement.setString(1,code); statement.setString(2,run); statement.executeUpdate();
    } catch (SQLException ignored) { /* Original failure remains authoritative; no supplier or document data are logged. */ }
  }
}
package com.smarttrip.rag;

import com.smarttrip.rag.knowledge.*;
import com.smarttrip.rag.knowledge.KnowledgeDocumentReader.PreparedDocument;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import static org.junit.jupiter.api.Assertions.*;

/** Real MySQL SQL/locking checks; opt-in, and writes only to the explicitly disposable smart_trip_test schema. */
@EnabledIfEnvironmentVariable(named="SMARTTRIP_RAG_REAL_MYSQL_URL",matches="jdbc:mysql:.*")
class RagKnowledgeMysqlTests {
  private DataSource source;
  private KnowledgeMetadataService metadata;
  private final Set<String> documentIds=new LinkedHashSet<>();
  private final Set<String> importIds=new LinkedHashSet<>();
  private final String alias="fixture-"+UUID.randomUUID().toString().replace("-","");

  @BeforeEach void initializeDisposableSchemaOnly() throws Exception {
    String url=System.getenv("SMARTTRIP_RAG_REAL_MYSQL_URL");
    String user=System.getenv().getOrDefault("SMARTTRIP_RAG_REAL_MYSQL_USER","root");
    String password=System.getenv().getOrDefault("SMARTTRIP_RAG_REAL_MYSQL_PASSWORD","");
    DriverManagerDataSource actual=new DriverManagerDataSource(url,user,password);
    // Guard every connection before executing any DDL/DML, including cleanup and injected-failure connections.
    source=new AbstractDataSource() {
      public Connection getConnection() throws SQLException {
        Connection connection=actual.getConnection();
        if(!"smart_trip_test".equals(connection.getCatalog())) {
          connection.close(); throw new SQLException("Refusing RAG test outside disposable smart_trip_test schema");
        }
        return connection;
      }
      public Connection getConnection(String ignoredUser,String ignoredPassword) throws SQLException {
        throw new SQLFeatureNotSupportedException("Explicit test credentials only");
      }
    };
    try(Connection connection=source.getConnection()) {
      assertEquals("smart_trip_test",connection.getCatalog());
    }
    // V10 is idempotent and independent. Existing V1-V10/Flyway state and all business tables are left untouched.
    new ResourceDatabasePopulator(new ClassPathResource("db/migration/V10__rag_knowledge_metadata.sql")).execute(source);
    metadata=new KnowledgeMetadataService(source);
  }

  @AfterEach void removeOnlyRowsCreatedByThisTest() throws Exception {
    if(source==null) return;
    try(Connection connection=source.getConnection()) {
      try(PreparedStatement statement=connection.prepareStatement("DELETE FROM rag_knowledge_document WHERE document_id=?")) {
        for(String id:documentIds) { statement.setString(1,id); statement.addBatch(); } statement.executeBatch();
      }
      try(PreparedStatement statement=connection.prepareStatement("DELETE FROM rag_knowledge_import WHERE import_id=?")) {
        for(String id:importIds) { statement.setString(1,id); statement.addBatch(); } statement.executeBatch();
      }
    }
  }

  private PreparedDocument prepared(String id,String title,String version) {
    documentIds.add(id);
    KnowledgeDocument doc=new KnowledgeDocument(id,title,"fixture.md",List.of("苏州"),List.of("江苏"),"CULTURE",List.of("ALL"),
        "Synthetic MySQL fixture","https://example.org/mysql-test",null,"PUBLIC",null,version,true,true);
    String hash=KnowledgeDocumentReader.sha256(id+"|"+title+"|"+version);
    KnowledgeChunk chunk=new KnowledgeChunk(id,id+"-chunk",title,"Synthetic fixture content",doc.destinations(),doc.regions(),doc.category(),
        doc.applicableSeasons(),doc.sourceName(),doc.sourceUrl(),null,"2026-10-28T00:00:00Z","PUBLIC",null,version,hash,"fixture-vector","v1",true,true);
    return new PreparedDocument(doc,hash,List.of(chunk));
  }
  private String id() { return "mysql-fixture-"+UUID.randomUUID().toString().replace("-",""); }
  private String run(String operation) {
    String run=metadata.beginRun(operation,KnowledgeDocumentReader.sha256(alias)); importIds.add(run); return run;
  }
  private String scalar(String sql,String value) throws SQLException {
    try(Connection connection=source.getConnection();PreparedStatement statement=connection.prepareStatement(sql)) {
      statement.setString(1,value);
      try(ResultSet result=statement.executeQuery()) { return result.next()?result.getString(1):null; }
    }
  }
  private String lockName() { return "smarttrip-rag:"+KnowledgeDocumentReader.sha256(alias).substring(0,40); }
  private int tryLock(Connection connection) throws SQLException {
    try(PreparedStatement statement=connection.prepareStatement("SELECT GET_LOCK(?,0)")) {
      statement.setString(1,lockName()); try(ResultSet result=statement.executeQuery()) { assertTrue(result.next()); return result.getInt(1); }
    }
  }
  private void release(Connection connection) throws SQLException {
    try(PreparedStatement statement=connection.prepareStatement("SELECT RELEASE_LOCK(?)")) { statement.setString(1,lockName()); statement.execute(); }
  }

  @Test void actualSaveIdempotenceMetadataUpsertDeletionAndImportAudit() throws Exception {
    String documentId=id(), importId=run("IMPORT");
    PreparedDocument first=prepared(documentId,"Original title","1"), changed=prepared(documentId,"Updated source metadata","2");
    assertEquals("RUNNING",scalar("SELECT status FROM rag_knowledge_import WHERE import_id=?",importId));
    metadata.exclusively(alias,connection->{
      assertFalse(metadata.unchanged(connection,first,"fixture-vector","v1"));
      metadata.save(connection,first,"fixture-index-1","fixture-vector","v1");
      assertTrue(metadata.unchanged(connection,first,"fixture-vector","v1"));
      assertFalse(metadata.unchanged(connection,changed,"fixture-vector","v1"));
      assertFalse(metadata.unchanged(connection,first,"fixture-vector","v2"));
      metadata.save(connection,changed,"fixture-index-2","fixture-vector","v1");
      assertTrue(metadata.unchanged(connection,changed,"fixture-vector","v1"));
      metadata.completeRun(connection,importId,1,1,"fixture-index-2","SUCCESS");
      return null;
    },()->fail("Successful transaction must not compensate"));
    assertEquals("1",scalar("SELECT COUNT(*) FROM rag_knowledge_document WHERE document_id=?",documentId));
    assertEquals("Updated source metadata",scalar("SELECT title FROM rag_knowledge_document WHERE document_id=?",documentId));
    assertEquals("2",scalar("SELECT document_version FROM rag_knowledge_document WHERE document_id=?",documentId));
    assertEquals("fixture-index-2",scalar("SELECT active_index FROM rag_knowledge_document WHERE document_id=?",documentId));
    assertEquals("SUCCESS",scalar("SELECT status FROM rag_knowledge_import WHERE import_id=?",importId));
    assertEquals("1",scalar("SELECT chunk_count FROM rag_knowledge_import WHERE import_id=?",importId));
    metadata.exclusively(alias,connection->{
      metadata.markDeleted(connection,List.of(documentId));
      assertFalse(metadata.unchanged(connection,changed,"fixture-vector","v1")); return null;
    },()->fail("Successful deletion must not compensate"));
    assertEquals("DELETED",scalar("SELECT status FROM rag_knowledge_document WHERE document_id=?",documentId));
    assertNull(scalar("SELECT active_index FROM rag_knowledge_document WHERE document_id=?",documentId));
    metadata.exclusively(alias,connection->{metadata.save(connection,changed,"fixture-index-3","fixture-vector","v1");return null;},()->fail("Unexpected compensation"));
    assertEquals("ACTIVE",scalar("SELECT status FROM rag_knowledge_document WHERE document_id=?",documentId));
  }

  @Test void realAdvisoryLockExcludesOtherConnectionsAndReleasesAfterCommit() throws Exception {
    try(Connection contender=source.getConnection()) {
      metadata.exclusively(alias,connection->{assertEquals(0,tryLock(contender));return null;},()->fail("Unexpected compensation"));
      assertEquals(1,tryLock(contender)); release(contender);
    }
  }

  @Test void transactionFailureRollsBackDocumentAndAuditThenCompensatesWhileStillLocked() throws Exception {
    String documentId=id(),importId=run("IMPORT"); PreparedDocument doc=prepared(documentId,"Rollback fixture","1");
    AtomicBoolean compensated=new AtomicBoolean();
    try(Connection contender=source.getConnection()) {
      RagException failure=assertThrows(RagException.class,()->metadata.exclusively(alias,connection->{
        metadata.save(connection,doc,"unpublished-index","fixture-vector","v1");
        metadata.completeRun(connection,importId,1,1,"unpublished-index","SUCCESS");
        throw new RagException("INDEX_WRITE_FAILED","Injected publication failure");
      },()->{try {assertEquals(0,tryLock(contender));compensated.set(true);}catch(SQLException e){throw new IllegalStateException(e);}}));
      assertEquals("INDEX_WRITE_FAILED",failure.code()); assertTrue(compensated.get());
      assertEquals(1,tryLock(contender)); release(contender);
    }
    assertNull(scalar("SELECT document_id FROM rag_knowledge_document WHERE document_id=?",documentId));
    assertEquals("RUNNING",scalar("SELECT status FROM rag_knowledge_import WHERE import_id=?",importId));
    metadata.failRun(importId,"INDEX_WRITE_FAILED");
    assertEquals("FAILED",scalar("SELECT status FROM rag_knowledge_import WHERE import_id=?",importId));
    assertEquals("INDEX_WRITE_FAILED",scalar("SELECT error_code FROM rag_knowledge_import WHERE import_id=?",importId));
  }

  @Test void injectedCommitFailureRollsBackSqlButKeepsPublishedSnapshotsForReconciliation() throws Exception {
    String documentId=id(); PreparedDocument doc=prepared(documentId,"Commit failure fixture","1");
    DataSource failCommit=new AbstractDataSource() {
      public Connection getConnection() throws SQLException {
        Connection actual=source.getConnection();
        return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
          if(method.getName().equals("commit")) throw new SQLException("Injected pre-commit failure; database remains real");
          try {return method.invoke(actual,args);}catch(InvocationTargetException error){throw error.getCause();}
        });
      }
      public Connection getConnection(String user,String password) throws SQLException {throw new SQLFeatureNotSupportedException();}
    };
    AtomicBoolean compensated=new AtomicBoolean();
    try(Connection contender=source.getConnection()) {
      RagException failure=assertThrows(RagException.class,()->new KnowledgeMetadataService(failCommit).exclusively(alias,connection->{
        metadata.save(connection,doc,"published-fixture-index","fixture-vector","v1");return null;
      },()->{try{assertEquals(0,tryLock(contender));compensated.set(true);}catch(SQLException e){throw new IllegalStateException(e);}}));
      // The production service cannot distinguish this injected pre-commit failure from a lost COMMIT response.
      assertEquals("INDEX_RECOVERY_REQUIRED",failure.code());assertFalse(compensated.get());
      assertEquals(1,tryLock(contender));release(contender);
    }
    assertNull(scalar("SELECT document_id FROM rag_knowledge_document WHERE document_id=?",documentId));
  }
}
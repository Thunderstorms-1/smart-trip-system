package com.smarttrip.rag;

import com.smarttrip.rag.knowledge.*;
import java.sql.*;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class RagKnowledgeMetadataTests {
  @Test void ambiguousCommitKeepsPublishedSnapshotsUntilManualReconciliation() throws Exception {
    DataSource source=mock(DataSource.class); Connection connection=mock(Connection.class);
    PreparedStatement lock=mock(PreparedStatement.class), release=mock(PreparedStatement.class);
    ResultSet result=mock(ResultSet.class); when(source.getConnection()).thenReturn(connection);
    when(connection.prepareStatement(startsWith("SELECT GET_LOCK"))).thenReturn(lock);
    when(connection.prepareStatement(startsWith("SELECT RELEASE_LOCK"))).thenReturn(release);
    when(lock.executeQuery()).thenReturn(result); when(result.next()).thenReturn(true); when(result.getInt(1)).thenReturn(1);
    List<String> steps=new ArrayList<>();
    doAnswer(call->{steps.add("commit");throw new SQLException("fixture failure");}).when(connection).commit();
    doAnswer(call->{steps.add("rollback");return null;}).when(connection).rollback();
    when(release.execute()).thenAnswer(call->{steps.add("release");return true;});
    RagException failure=assertThrows(RagException.class,()->new KnowledgeMetadataService(source).exclusively("alias",c->{steps.add("publish");return "done";},()->steps.add("restore")));
    assertEquals("INDEX_RECOVERY_REQUIRED",failure.code());
    assertEquals(List.of("publish","commit","rollback","release"),steps);
  }
  @Test void confirmedCommitRemainsSuccessfulWhenConnectionCloseFails() throws Exception {
    DataSource source=mock(DataSource.class); Connection connection=mock(Connection.class);
    PreparedStatement lock=mock(PreparedStatement.class), release=mock(PreparedStatement.class);
    ResultSet result=mock(ResultSet.class); when(source.getConnection()).thenReturn(connection);
    when(connection.prepareStatement(startsWith("SELECT GET_LOCK"))).thenReturn(lock);
    when(connection.prepareStatement(startsWith("SELECT RELEASE_LOCK"))).thenReturn(release);
    when(lock.executeQuery()).thenReturn(result); when(result.next()).thenReturn(true); when(result.getInt(1)).thenReturn(1);
    doThrow(new SQLException("fixture close failure")).when(connection).close();
    Runnable compensate=mock(Runnable.class);
    assertEquals("done",new KnowledgeMetadataService(source).exclusively("alias",c->"done",compensate));
    verify(connection).commit(); verify(connection,never()).rollback(); verify(compensate,never()).run();
    verify(release).execute();
  }
  @Test void workFailureRestoresAliasBeforeReleasingCrossProcessLock() throws Exception {
    DataSource source=mock(DataSource.class); Connection connection=mock(Connection.class);
    PreparedStatement lock=mock(PreparedStatement.class), release=mock(PreparedStatement.class);
    ResultSet result=mock(ResultSet.class); when(source.getConnection()).thenReturn(connection);
    when(connection.prepareStatement(startsWith("SELECT GET_LOCK"))).thenReturn(lock);
    when(connection.prepareStatement(startsWith("SELECT RELEASE_LOCK"))).thenReturn(release);
    when(lock.executeQuery()).thenReturn(result); when(result.next()).thenReturn(true); when(result.getInt(1)).thenReturn(1);
    List<String> steps=new ArrayList<>();
    doAnswer(call->{steps.add("rollback");return null;}).when(connection).rollback();
    when(release.execute()).thenAnswer(call->{steps.add("release");return true;});
    RagException failure=assertThrows(RagException.class,()->new KnowledgeMetadataService(source).exclusively("alias",c->{
      steps.add("work"); throw new RagException("INDEX_WRITE_FAILED","fixture");
    },()->steps.add("restore")));
    assertEquals("INDEX_WRITE_FAILED",failure.code());
    assertEquals(List.of("work","rollback","restore","release"),steps);
    verify(connection,never()).commit();
  }
  @Test void failedCompensationProducesExplicitRecoveryCodeAndStillReleasesLock() throws Exception {
    DataSource source=mock(DataSource.class); Connection connection=mock(Connection.class);
    PreparedStatement lock=mock(PreparedStatement.class), release=mock(PreparedStatement.class);
    ResultSet result=mock(ResultSet.class); when(source.getConnection()).thenReturn(connection);
    when(connection.prepareStatement(startsWith("SELECT GET_LOCK"))).thenReturn(lock);
    when(connection.prepareStatement(startsWith("SELECT RELEASE_LOCK"))).thenReturn(release);
    when(lock.executeQuery()).thenReturn(result); when(result.next()).thenReturn(true); when(result.getInt(1)).thenReturn(1);
    RagException failure=assertThrows(RagException.class,()->new KnowledgeMetadataService(source).exclusively("alias",c->{throw new RagException("INDEX_WRITE_FAILED","fixture");},()->{throw new RagException("ES_UNAVAILABLE","fixture");}));
    assertEquals("INDEX_RECOVERY_REQUIRED",failure.code()); verify(release).execute();
  }
}
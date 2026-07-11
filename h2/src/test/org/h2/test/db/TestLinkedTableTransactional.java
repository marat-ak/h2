/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.h2.table.TableLinkTransaction;
import org.h2.test.TestBase;
import org.h2.test.TestDb;

/**
 * Tests for the OSaaS fork extensions to linked tables: transactional
 * (AUTOCOMMIT OFF) linked tables and batched DML.
 *
 * The "remote" database is a second in-memory H2 database.
 */
public class TestLinkedTableTransactional extends TestDb {

    private static final String REMOTE_URL = "jdbc:h2:mem:ltRemoteTx";

    /**
     * Run just this test.
     *
     * @param a ignored
     */
    public static void main(String... a) throws Exception {
        TestBase.createCaller().init().testFromMain();
    }

    @Override
    public void test() throws SQLException {
        testAutoCommitOffDdlRoundTrip();
        testAutoCommitOffTransactional();
        testAutoCommitOffCommitPropagation();
        testAutoCommitOffSessionClose();
        testLinkedTableTransactionalDefault();
        testAutoCommitOffErrorPath();
        testBatchAccumulation();
        testBatchOrderingMixedShapes();
        testBatchDdlAndValidation();
        testBatchStatementEndFlushAndDefault();
    }

    /**
     * PLAN 2.2: a multi-row DML statement flushes its batch at statement end
     * (correct update counts, ADR-4) even when the batch size is not reached;
     * SET LINKED_TABLE_BATCH_SIZE provides the default BATCH for new
     * transactional tables.
     */
    private void testBatchStatementEndFlushAndDefault() throws SQLException {
        try (Connection remoteKeep = DriverManager.getConnection("jdbc:h2:mem:ltRemoteFlush")) {
            Statement remoteStat = remoteKeep.createStatement();
            remoteStat.execute("CREATE TABLE TEST(ID INT PRIMARY KEY, NAME VARCHAR)");
            try (Connection local = DriverManager.getConnection("jdbc:h2:mem:ltLocalFlush")) {
                Statement stat = local.createStatement();
                // default batch size via SET
                stat.execute("SET LINKED_TABLE_BATCH_SIZE 50");
                stat.execute("CREATE LINKED TABLE LT('', 'jdbc:h2:mem:ltRemoteFlush', '', '', 'TEST') " +
                        "AUTOCOMMIT OFF");
                assertTrue(getLinkedTableSql(stat).contains("AUTOCOMMIT OFF BATCH 50"));
                local.setAutoCommit(false);
                long before = TableLinkTransaction.EXECUTE_BATCH_CALLS.get();
                // 3 rows in one statement, far below the batch size of 50
                int count = stat.executeUpdate("INSERT INTO LT VALUES(1, 'a'), (2, 'b'), (3, 'c')");
                assertEquals(3, count);
                // statement end flushed the batch exactly once
                assertEquals(1, (int) (TableLinkTransaction.EXECUTE_BATCH_CALLS.get() - before));
                // still not committed remotely
                assertEquals(0, countRemote(remoteStat, "TEST"));
                local.commit();
                assertEquals(3, countRemote(remoteStat, "TEST"));
                local.setAutoCommit(true);
                stat.execute("DROP TABLE LT");
                // explicit BATCH overrides the SET default
                stat.execute("CREATE LINKED TABLE LT('', 'jdbc:h2:mem:ltRemoteFlush', '', '', 'TEST') " +
                        "AUTOCOMMIT OFF BATCH 7");
                assertTrue(getLinkedTableSql(stat).contains("BATCH 7"));
                stat.execute("DROP TABLE LT");
                // SET 0 disables batching for new tables
                stat.execute("SET LINKED_TABLE_BATCH_SIZE 0");
                stat.execute("CREATE LINKED TABLE LT('', 'jdbc:h2:mem:ltRemoteFlush', '', '', 'TEST') " +
                        "AUTOCOMMIT OFF");
                String sql = getLinkedTableSql(stat);
                assertTrue(sql, !sql.contains("BATCH"));
                stat.execute("DROP TABLE LT");
            }
            remoteStat.execute("DROP TABLE TEST");
        }
    }

    /**
     * DESIGN.md Feature-1 test #3 (PLAN 2.1/2.2): one 12-row INSERT with
     * BATCH 5 produces ceil(12/5)=3 executeBatch round-trips (2 on size, 1 at
     * statement end); rows appear remotely only after commit; the local
     * session sees its own batched rows (read-your-writes, DESIGN test #4).
     */
    private void testBatchAccumulation() throws SQLException {
        try (Connection remoteKeep = DriverManager.getConnection("jdbc:h2:mem:ltRemoteBatch")) {
            Statement remoteStat = remoteKeep.createStatement();
            remoteStat.execute("CREATE TABLE TEST(ID INT PRIMARY KEY, NAME VARCHAR)");
            try (Connection local = DriverManager.getConnection("jdbc:h2:mem:ltLocalBatch")) {
                Statement stat = local.createStatement();
                stat.execute("CREATE LINKED TABLE LT('', 'jdbc:h2:mem:ltRemoteBatch', '', '', 'TEST') " +
                        "AUTOCOMMIT OFF BATCH 5");
                local.setAutoCommit(false);
                long before = TableLinkTransaction.EXECUTE_BATCH_CALLS.get();
                // 12 rows in ONE statement with BATCH 5:
                // 2 flushes on size (5+5) + 1 at statement end (2 remaining)
                int count = stat.executeUpdate(
                        "INSERT INTO LT SELECT X, 'n' || X FROM SYSTEM_RANGE(1, 12)");
                assertEquals(12, count);
                assertEquals(3, (int) (TableLinkTransaction.EXECUTE_BATCH_CALLS.get() - before));
                // nothing committed remotely yet
                assertEquals(0, countRemote(remoteStat, "TEST"));
                // read-your-writes: the local session sees all 12 rows
                try (ResultSet rs = stat.executeQuery("SELECT COUNT(*) FROM LT")) {
                    rs.next();
                    assertEquals(12, rs.getInt(1));
                }
                // the read did not need another flush
                assertEquals(3, (int) (TableLinkTransaction.EXECUTE_BATCH_CALLS.get() - before));
                assertEquals(0, countRemote(remoteStat, "TEST"));
                local.commit();
                assertEquals(12, countRemote(remoteStat, "TEST"));
                // rollback discards pending batched rows
                stat.execute("INSERT INTO LT VALUES(100, 'x')");
                local.rollback();
                local.commit();
                assertEquals(12, countRemote(remoteStat, "TEST"));
                local.setAutoCommit(true);
                stat.execute("DROP TABLE LT");
            }
            remoteStat.execute("DROP TABLE TEST");
        }
    }

    /**
     * DESIGN.md Feature-1 test #5 (PLAN 2.1): mixed-shape DML in one
     * transaction keeps its order - a different SQL string flushes the
     * pending batch first.
     */
    private void testBatchOrderingMixedShapes() throws SQLException {
        try (Connection remoteKeep = DriverManager.getConnection("jdbc:h2:mem:ltRemoteOrder")) {
            Statement remoteStat = remoteKeep.createStatement();
            remoteStat.execute("CREATE TABLE TEST(ID INT PRIMARY KEY, NAME VARCHAR)");
            try (Connection local = DriverManager.getConnection("jdbc:h2:mem:ltLocalOrder")) {
                Statement stat = local.createStatement();
                stat.execute("CREATE LINKED TABLE LT('', 'jdbc:h2:mem:ltRemoteOrder', '', '', 'TEST') " +
                        "AUTOCOMMIT OFF BATCH 100");
                local.setAutoCommit(false);
                stat.execute("INSERT INTO LT VALUES(1, 'a')");
                // UPDATE on a linked table runs as DELETE + INSERT: both are
                // different shapes and must not overtake the first INSERT
                stat.execute("UPDATE LT SET NAME='b' WHERE ID=1");
                stat.execute("INSERT INTO LT VALUES(2, 'c')");
                stat.execute("DELETE FROM LT WHERE ID=2");
                stat.execute("INSERT INTO LT VALUES(3, 'd')");
                local.commit();
                try (ResultSet rs = remoteStat.executeQuery("SELECT ID, NAME FROM TEST ORDER BY ID")) {
                    assertTrue(rs.next());
                    assertEquals(1, rs.getInt(1));
                    assertEquals("b", rs.getString(2));
                    assertTrue(rs.next());
                    assertEquals(3, rs.getInt(1));
                    assertEquals("d", rs.getString(2));
                    assertFalse(rs.next());
                }
                local.setAutoCommit(true);
                stat.execute("DROP TABLE LT");
            }
            remoteStat.execute("DROP TABLE TEST");
        }
    }

    /**
     * PLAN 2.1: BATCH n round-trips through DDL; BATCH with an autocommit
     * (non-transactional) table is rejected.
     */
    private void testBatchDdlAndValidation() throws SQLException {
        try (Connection remoteKeep = DriverManager.getConnection("jdbc:h2:mem:ltRemoteBddl")) {
            remoteKeep.createStatement().execute("CREATE TABLE TEST(ID INT)");
            try (Connection local = DriverManager.getConnection("jdbc:h2:mem:ltLocalBddl")) {
                Statement stat = local.createStatement();
                stat.execute("CREATE LINKED TABLE LT('', 'jdbc:h2:mem:ltRemoteBddl', '', '', 'TEST') " +
                        "AUTOCOMMIT OFF BATCH 500");
                String sql = getLinkedTableSql(stat);
                assertTrue(sql, sql.contains("AUTOCOMMIT OFF BATCH 500"));
                stat.execute("DROP TABLE LT");
                // BATCH without AUTOCOMMIT OFF is rejected
                assertThrows(SQLException.class, () -> stat.execute(
                        "CREATE LINKED TABLE LT('', 'jdbc:h2:mem:ltRemoteBddl', '', '', 'TEST') BATCH 500"));
                // BATCH 1 and BATCH 0 mean "no batching" and are accepted
                stat.execute("CREATE LINKED TABLE LT('', 'jdbc:h2:mem:ltRemoteBddl', '', '', 'TEST') " +
                        "AUTOCOMMIT OFF BATCH 1");
                sql = getLinkedTableSql(stat);
                assertTrue(sql, !sql.contains("BATCH"));
                stat.execute("DROP TABLE LT");
            }
        }
    }

    /**
     * DESIGN.md Feature-1 test #6 (PLAN 1.5): a remote constraint violation
     * surfaces as an SQLException, the transaction stays open (rollback still
     * works), and the enlisted connection remains usable afterwards.
     */
    private void testAutoCommitOffErrorPath() throws SQLException {
        try (Connection remoteKeep = DriverManager.getConnection("jdbc:h2:mem:ltRemoteErr")) {
            Statement remoteStat = remoteKeep.createStatement();
            remoteStat.execute("CREATE TABLE TEST(ID INT PRIMARY KEY)");
            try (Connection local = DriverManager.getConnection("jdbc:h2:mem:ltLocalErr")) {
                Statement stat = local.createStatement();
                stat.execute("CREATE LINKED TABLE LT('', 'jdbc:h2:mem:ltRemoteErr', '', '', 'TEST') " +
                        "AUTOCOMMIT OFF");
                local.setAutoCommit(false);
                stat.execute("INSERT INTO LT VALUES(1)");
                // duplicate key on the remote side surfaces as SQLException
                assertThrows(SQLException.class, () -> stat.execute("INSERT INTO LT VALUES(1)"));
                // the transaction is still open and the connection usable
                stat.execute("INSERT INTO LT VALUES(2)");
                local.commit();
                assertEquals(2, countRemote(remoteStat, "TEST"));
                // rollback after an error also works
                stat.execute("INSERT INTO LT VALUES(3)");
                assertThrows(SQLException.class, () -> stat.execute("INSERT INTO LT VALUES(3)"));
                local.rollback();
                assertEquals(2, countRemote(remoteStat, "TEST"));
                // and the connection is still usable for the next transaction
                stat.execute("INSERT INTO LT VALUES(4)");
                local.commit();
                assertEquals(3, countRemote(remoteStat, "TEST"));
                local.setAutoCommit(true);
                stat.execute("DROP TABLE LT");
            }
            remoteStat.execute("DROP TABLE TEST");
        }
    }

    /**
     * PLAN 1.4: SET LINKED_TABLE_TRANSACTIONAL TRUE and the URL setting
     * change the default for CREATE LINKED TABLE without an AUTOCOMMIT
     * option; an explicit AUTOCOMMIT ON still overrides the default.
     */
    private void testLinkedTableTransactionalDefault() throws SQLException {
        try (Connection remoteKeep = DriverManager.getConnection("jdbc:h2:mem:ltRemoteDef")) {
            Statement remoteStat = remoteKeep.createStatement();
            remoteStat.execute("CREATE TABLE TEST(ID INT)");
            // via SET
            try (Connection local = DriverManager.getConnection("jdbc:h2:mem:ltLocalDef")) {
                Statement stat = local.createStatement();
                stat.execute("SET LINKED_TABLE_TRANSACTIONAL TRUE");
                stat.execute("CREATE LINKED TABLE LT('', 'jdbc:h2:mem:ltRemoteDef', '', '', 'TEST')");
                assertTrue(getLinkedTableSql(stat).contains("AUTOCOMMIT OFF"));
                local.setAutoCommit(false);
                stat.execute("INSERT INTO LT VALUES(1)");
                assertEquals(0, countRemote(remoteStat, "TEST"));
                local.rollback();
                assertEquals(0, countRemote(remoteStat, "TEST"));
                local.setAutoCommit(true);
                stat.execute("DROP TABLE LT");
                // explicit AUTOCOMMIT ON overrides the global default
                stat.execute("CREATE LINKED TABLE LT('', 'jdbc:h2:mem:ltRemoteDef', '', '', 'TEST') " +
                        "AUTOCOMMIT ON");
                String sql = getLinkedTableSql(stat);
                assertTrue(sql, !sql.contains("AUTOCOMMIT"));
                stat.execute("DROP TABLE LT");
                // back to FALSE: new tables are non-transactional again
                stat.execute("SET LINKED_TABLE_TRANSACTIONAL FALSE");
                stat.execute("CREATE LINKED TABLE LT('', 'jdbc:h2:mem:ltRemoteDef', '', '', 'TEST')");
                sql = getLinkedTableSql(stat);
                assertTrue(sql, !sql.contains("AUTOCOMMIT"));
                stat.execute("DROP TABLE LT");
            }
            // via URL setting
            try (Connection local = DriverManager.getConnection(
                    "jdbc:h2:mem:ltLocalDefUrl;LINKED_TABLE_TRANSACTIONAL=TRUE")) {
                Statement stat = local.createStatement();
                stat.execute("CREATE LINKED TABLE LT('', 'jdbc:h2:mem:ltRemoteDef', '', '', 'TEST')");
                assertTrue(getLinkedTableSql(stat).contains("AUTOCOMMIT OFF"));
                stat.execute("DROP TABLE LT");
            }
            remoteStat.execute("DROP TABLE TEST");
        }
    }

    /**
     * PLAN 1.3: local commit propagates to the remote transaction
     * (flush - remote commit - local commit, ADR-3); after a commit the same
     * enlisted connection keeps working for the next transaction; local
     * autocommit sessions commit remotely at statement end.
     */
    private void testAutoCommitOffCommitPropagation() throws SQLException {
        try (Connection remoteKeep = DriverManager.getConnection("jdbc:h2:mem:ltRemoteCommit")) {
            Statement remoteStat = remoteKeep.createStatement();
            remoteStat.execute("CREATE TABLE TEST(ID INT PRIMARY KEY, NAME VARCHAR)");
            try (Connection local = DriverManager.getConnection("jdbc:h2:mem:ltLocalCommit")) {
                Statement stat = local.createStatement();
                stat.execute("CREATE LINKED TABLE LT('', 'jdbc:h2:mem:ltRemoteCommit', '', '', 'TEST') " +
                        "AUTOCOMMIT OFF");
                local.setAutoCommit(false);
                stat.execute("INSERT INTO LT VALUES(1, 'a')");
                stat.execute("INSERT INTO LT VALUES(2, 'b')");
                assertEquals(0, countRemote(remoteStat, "TEST"));
                local.commit();
                assertEquals(2, countRemote(remoteStat, "TEST"));
                // the enlisted connection survives the commit: next tx works
                stat.execute("INSERT INTO LT VALUES(3, 'c')");
                assertEquals(2, countRemote(remoteStat, "TEST"));
                local.rollback();
                assertEquals(2, countRemote(remoteStat, "TEST"));
                // local autocommit: remote commit at statement end
                local.setAutoCommit(true);
                stat.execute("INSERT INTO LT VALUES(4, 'd')");
                assertEquals(3, countRemote(remoteStat, "TEST"));
                stat.execute("DROP TABLE LT");
            }
            remoteStat.execute("DROP TABLE TEST");
        }
    }

    /**
     * PLAN 1.3: closing the local session with an open transaction rolls the
     * remote transaction back and closes the remote connection.
     */
    private void testAutoCommitOffSessionClose() throws SQLException {
        try (Connection remoteKeep = DriverManager.getConnection("jdbc:h2:mem:ltRemoteClose")) {
            Statement remoteStat = remoteKeep.createStatement();
            remoteStat.execute("CREATE TABLE TEST(ID INT PRIMARY KEY)");
            Connection local = DriverManager.getConnection(
                    "jdbc:h2:mem:ltLocalClose;DB_CLOSE_DELAY=-1");
            try {
                Statement stat = local.createStatement();
                stat.execute("CREATE LINKED TABLE LT('', 'jdbc:h2:mem:ltRemoteClose', '', '', 'TEST') " +
                        "AUTOCOMMIT OFF");
                local.setAutoCommit(false);
                stat.execute("INSERT INTO LT VALUES(1)");
                // session closes with the transaction open
                local.close();
                assertEquals(0, countRemote(remoteStat, "TEST"));
                // a new session on the same database gets a fresh connection
                local = DriverManager.getConnection("jdbc:h2:mem:ltLocalClose;DB_CLOSE_DELAY=-1");
                stat = local.createStatement();
                stat.execute("INSERT INTO LT VALUES(2)");
                assertEquals(1, countRemote(remoteStat, "TEST"));
                stat.execute("DROP TABLE LT");
            } finally {
                try (Statement shutdown = local.createStatement()) {
                    shutdown.execute("SHUTDOWN");
                } catch (SQLException e) {
                    // already closed
                }
            }
            remoteStat.execute("DROP TABLE TEST");
        }
    }

    /**
     * PLAN 1.1 (ADR-10): the AUTOCOMMIT OFF option must survive DDL
     * round-trips - both in SCRIPT output and across a database reopen
     * (linked tables are re-created from their meta SQL on startup).
     */
    private void testAutoCommitOffDdlRoundTrip() throws SQLException {
        if (!config.memory && !config.networked) {
            deleteDb("ltTxDdl");
            try (Connection remoteKeep = DriverManager.getConnection("jdbc:h2:mem:ltRemoteDdl")) {
                remoteKeep.createStatement().execute("CREATE TABLE TEST(ID INT)");
                Connection conn = getConnection("ltTxDdl");
                Statement stat = conn.createStatement();
                stat.execute("CREATE LINKED TABLE LT('', 'jdbc:h2:mem:ltRemoteDdl', '', '', 'TEST') " +
                        "AUTOCOMMIT OFF");
                assertTrue(getLinkedTableSql(stat).contains("AUTOCOMMIT OFF"));
                conn.close();
                // reopen: the linked table is re-created from meta SQL
                conn = getConnection("ltTxDdl");
                stat = conn.createStatement();
                assertTrue(getLinkedTableSql(stat).contains("AUTOCOMMIT OFF"));
                stat.execute("DROP TABLE LT");
                conn.close();
            }
            deleteDb("ltTxDdl");
        }
        try (Connection remoteKeep = DriverManager.getConnection("jdbc:h2:mem:ltRemoteDdl2")) {
            remoteKeep.createStatement().execute("CREATE TABLE TEST(ID INT)");
            try (Connection conn = DriverManager.getConnection("jdbc:h2:mem:ltLocalDdl2")) {
                Statement stat = conn.createStatement();
                // default (no option): no AUTOCOMMIT OFF in DDL
                stat.execute("CREATE LINKED TABLE LT('', 'jdbc:h2:mem:ltRemoteDdl2', '', '', 'TEST')");
                String sql = getLinkedTableSql(stat);
                assertTrue(sql, !sql.contains("AUTOCOMMIT"));
                stat.execute("DROP TABLE LT");
                // explicit AUTOCOMMIT ON: same as default
                stat.execute("CREATE LINKED TABLE LT('', 'jdbc:h2:mem:ltRemoteDdl2', '', '', 'TEST') " +
                        "AUTOCOMMIT ON");
                sql = getLinkedTableSql(stat);
                assertTrue(sql, !sql.contains("AUTOCOMMIT"));
                stat.execute("DROP TABLE LT");
            }
        }
    }

    private static String getLinkedTableSql(Statement stat) throws SQLException {
        try (ResultSet rs = stat.executeQuery("SCRIPT NODATA")) {
            while (rs.next()) {
                String s = rs.getString(1);
                if (s.contains("LINKED TABLE")) {
                    return s;
                }
            }
        }
        throw new AssertionError("no LINKED TABLE found in SCRIPT output");
    }

    /**
     * DESIGN.md Feature-1 test #1/#2 (ADR-10).
     *
     * Upstream 2.2.224 baseline (task 0.3): AUTOCOMMIT OFF was a no-op - all
     * 3 rows were committed remotely per row (visible mid-transaction) and
     * survived a local rollback.
     *
     * Since task 1.2 the table uses a dedicated per-session remote connection
     * with real autoCommit=false: nothing is visible to an independent remote
     * connection until local commit, and rows inserted in a rolled-back local
     * transaction never appear remotely. Task 1.3 adds commit propagation
     * (see testAutoCommitOffCommitPropagation).
     */
    private void testAutoCommitOffTransactional() throws SQLException {
        // keeps the named in-memory database alive for the whole test
        try (Connection remoteKeep = DriverManager.getConnection(REMOTE_URL)) {
            Statement remoteStat = remoteKeep.createStatement();
            remoteStat.execute("CREATE TABLE TEST(ID INT PRIMARY KEY, NAME VARCHAR)");
            try (Connection local = DriverManager.getConnection("jdbc:h2:mem:ltLocalTx")) {
                Statement stat = local.createStatement();
                stat.execute("CREATE LINKED TABLE LT('', '" + REMOTE_URL
                        + "', '', '', 'TEST') AUTOCOMMIT OFF");
                local.setAutoCommit(false);
                stat.execute("INSERT INTO LT VALUES(1, 'a')");
                stat.execute("INSERT INTO LT VALUES(2, 'b')");
                stat.execute("INSERT INTO LT VALUES(3, 'c')");
                // read-your-writes: the local session sees its own
                // uncommitted rows through the linked table
                try (ResultSet rs = stat.executeQuery("SELECT COUNT(*) FROM LT")) {
                    rs.next();
                    assertEquals(3, rs.getInt(1));
                }
                // independent remote connection: nothing committed remotely
                // while the local tx is still open (was 3 upstream)
                assertEquals(0, countRemote(remoteStat));
                local.rollback();
                // local rollback leaves no remote effects (was 3 upstream)
                assertEquals(0, countRemote(remoteStat));
                local.setAutoCommit(true);
                stat.execute("DROP TABLE LT");
            }
            remoteStat.execute("DROP TABLE TEST");
        }
    }

    private static int countRemote(Statement remoteStat) throws SQLException {
        return countRemote(remoteStat, "TEST");
    }

    private static int countRemote(Statement remoteStat, String table) throws SQLException {
        try (ResultSet rs = remoteStat.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getInt(1);
        }
    }

}

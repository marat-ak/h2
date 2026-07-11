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
        try (ResultSet rs = remoteStat.executeQuery("SELECT COUNT(*) FROM TEST")) {
            rs.next();
            return rs.getInt(1);
        }
    }

}

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
        testAutoCommitOffTransactional();
    }

    /**
     * DESIGN.md Feature-1 test #1/#2 (ADR-10).
     *
     * Baseline (upstream 2.2.224): AUTOCOMMIT OFF is parsed but is a no-op.
     * Rows inserted into a linked table inside an open local transaction are
     * committed remotely per row - they are visible to an independent remote
     * connection before the local commit, and a local rollback does not
     * remove them.
     *
     * Target (Phase 1): with AUTOCOMMIT OFF the remote connection is enlisted
     * in the local transaction - nothing is visible remotely until local
     * commit, and local rollback removes all remote effects.
     *
     * This method asserts the CURRENT baseline behavior; Phase 1 flips the
     * expectations marked with "FLIP".
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
                // independent remote connection: how many rows are already
                // committed remotely while the local tx is still open?
                // FLIP in Phase 1: expected becomes 0
                assertEquals(3, countRemote(remoteStat));
                local.rollback();
                // local rollback must remove remote rows once transactional
                // FLIP in Phase 1: expected becomes 0
                assertEquals(3, countRemote(remoteStat));
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

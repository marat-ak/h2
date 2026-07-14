/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.db;

import java.io.StringReader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.h2.api.ErrorCode;
import org.h2.test.TestBase;
import org.h2.test.TestDb;
import org.h2.tools.RunScript;
import org.h2.util.GroovyScriptMarkers;

/**
 * Tests for the OSaaS fork EXECUTE GROOVY statement. Groovy is a test-scope
 * dependency; in production it stays optional.
 */
public class TestExecuteGroovy extends TestDb {

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
        testScalarScript();
        testAdminRequired();
        testKillSwitch();
        testCursorLoopDml();
        testVarsPersistAcrossBlocks();
        testMarkerRewrite();
        testMarkerRunScript();
        testGroovyOverTransactionalLinkedRollback();
    }

    /**
     * DESIGN.md Feature-2 test #1: a trivial scalar script runs without
     * error, in both $$...$$ and string literal forms.
     */
    private void testScalarScript() throws SQLException {
        try (Connection conn = DriverManager.getConnection("jdbc:h2:mem:groovyScalar")) {
            Statement stat = conn.createStatement();
            stat.execute("EXECUTE GROOVY $$ 1 + 1 $$");
            stat.execute("EXECUTE GROOVY ' 2 + 2 '");
            // the compiled script is cached: same source again is fine
            stat.execute("EXECUTE GROOVY $$ 1 + 1 $$");
        }
    }

    /**
     * DESIGN.md Feature-2 test #5: a non-admin user is denied.
     */
    private void testAdminRequired() throws SQLException {
        try (Connection conn = DriverManager.getConnection("jdbc:h2:mem:groovyAdmin", "sa", "sa")) {
            Statement stat = conn.createStatement();
            stat.execute("CREATE USER PLAIN PASSWORD 'plain'");
            try (Connection conn2 = DriverManager.getConnection("jdbc:h2:mem:groovyAdmin", "PLAIN", "plain")) {
                Statement stat2 = conn2.createStatement();
                try {
                    stat2.execute("EXECUTE GROOVY $$ 1 + 1 $$");
                    fail("non-admin user must not run EXECUTE GROOVY");
                } catch (SQLException e) {
                    assertEquals(ErrorCode.ADMIN_RIGHTS_REQUIRED, e.getErrorCode());
                }
            }
        }
    }

    /**
     * ADR-6: SET GROOVY_BLOCKS FALSE disables the statement; TRUE re-enables
     * it. The setting is persisted (visible in INFORMATION_SCHEMA.SETTINGS).
     */
    private void testKillSwitch() throws SQLException {
        try (Connection conn = DriverManager.getConnection("jdbc:h2:mem:groovyKill")) {
            Statement stat = conn.createStatement();
            stat.execute("SET GROOVY_BLOCKS FALSE");
            try {
                stat.execute("EXECUTE GROOVY $$ 1 + 1 $$");
                fail("EXECUTE GROOVY must be disabled by SET GROOVY_BLOCKS FALSE");
            } catch (SQLException e) {
                assertEquals(ErrorCode.FEATURE_NOT_SUPPORTED_1, e.getErrorCode());
            }
            try (ResultSet rs = stat.executeQuery("SELECT SETTING_VALUE FROM INFORMATION_SCHEMA.SETTINGS " +
                    "WHERE SETTING_NAME = 'GROOVY_BLOCKS'")) {
                assertTrue(rs.next());
                assertEquals("0", rs.getString(1));
            }
            stat.execute("SET GROOVY_BLOCKS TRUE");
            stat.execute("EXECUTE GROOVY $$ 1 + 1 $$");
        }
    }

    /**
     * DESIGN.md Feature-2 test #2: a cursor loop with conditional DML over a
     * normal table, executed through the {@code sql} binding, participates in
     * the local transaction and modifies the expected rows.
     */
    private void testCursorLoopDml() throws SQLException {
        try (Connection conn = DriverManager.getConnection("jdbc:h2:mem:groovyLoop")) {
            Statement stat = conn.createStatement();
            stat.execute("CREATE TABLE orders(id INT PRIMARY KEY, status VARCHAR)");
            for (int i = 1; i <= 6; i++) {
                stat.execute("INSERT INTO orders VALUES(" + i + ", 'NEW')");
            }
            stat.execute("EXECUTE GROOVY $$\n"
                    + "sql.eachRow('SELECT id FROM orders WHERE status = ?', ['NEW']) { row ->\n"
                    + "    if (row.id % 2 == 0) {\n"
                    + "        sql.executeUpdate('UPDATE orders SET status = ? WHERE id = ?', ['DONE', row.id])\n"
                    + "    }\n"
                    + "}\n$$");
            try (ResultSet rs = stat.executeQuery(
                    "SELECT COUNT(*) FROM orders WHERE status = 'DONE'")) {
                assertTrue(rs.next());
                assertEquals(3, rs.getInt(1));
            }
        }
    }

    /**
     * DESIGN.md Feature-2 test #4: the {@code vars} map survives across two
     * blocks in the same session and is isolated between sessions.
     */
    private void testVarsPersistAcrossBlocks() throws SQLException {
        try (Connection conn = DriverManager.getConnection("jdbc:h2:mem:groovyVars")) {
            Statement stat = conn.createStatement();
            stat.execute("EXECUTE GROOVY $$ vars['counter'] = 41 $$");
            stat.execute("EXECUTE GROOVY $$ vars['counter'] = vars['counter'] + 1 $$");
            stat.execute("EXECUTE GROOVY $$ vars['_result'] = [['n': vars['counter']]] $$");
            try (ResultSet rs = stat.executeQuery("CALL 1")) {
                assertTrue(rs.next());
            }
            // a second connection has its own vars: reading 'counter' is null
            try (Connection conn2 = DriverManager.getConnection("jdbc:h2:mem:groovyVars")) {
                Statement stat2 = conn2.createStatement();
                stat2.execute("EXECUTE GROOVY $$ assert vars['counter'] == null $$");
            }
        }
    }

    /**
     * ADR-7: the marker rewrite turns a {@code <<groovy start>>..end}} block
     * into a single EXECUTE GROOVY statement and leaves surrounding SQL alone.
     */
    private void testMarkerRewrite() {
        String in = "CREATE TABLE t(id INT);\n"
                + "<<groovy start>>\n"
                + "sql.execute('INSERT INTO t VALUES(1);')\n"
                + "sql.execute('INSERT INTO t VALUES(2);')\n"
                + "<<groovy end>>\n"
                + "SELECT * FROM t;\n";
        String out = GroovyScriptMarkers.rewrite(in);
        assertTrue(out.contains("EXECUTE GROOVY $$"));
        assertTrue(out.contains("INSERT INTO t VALUES(2);"));
        assertTrue(out.contains("CREATE TABLE t(id INT);"));
        assertTrue(out.contains("SELECT * FROM t;"));
        // a script without markers is returned unchanged
        assertEquals("SELECT 1;", GroovyScriptMarkers.rewrite("SELECT 1;"));
    }

    /**
     * DESIGN.md Feature-2 test #7: a hand-written script with a marker block
     * executes end-to-end through RunScript, semicolons inside the block and
     * all.
     */
    private void testMarkerRunScript() throws SQLException {
        try (Connection conn = DriverManager.getConnection("jdbc:h2:mem:groovyMarkerScript")) {
            String script = "CREATE TABLE t(id INT PRIMARY KEY, note VARCHAR);\n"
                    + "<<groovy start>>\n"
                    + "(1..3).each { i ->\n"
                    + "    sql.executeUpdate('INSERT INTO t VALUES(?, ?)', [i, \"row ${i}\".toString()])\n"
                    + "}\n"
                    + "<<GROOVY END>>\n";
            RunScript.execute(conn, new StringReader(script));
            Statement stat = conn.createStatement();
            try (ResultSet rs = stat.executeQuery("SELECT COUNT(*) FROM t")) {
                assertTrue(rs.next());
                assertEquals(3, rs.getInt(1));
            }
        }
    }

    /**
     * DESIGN.md Feature-2 test #3: DML issued from a Groovy block against a
     * transactional linked table is rolled back with the local transaction.
     * The "remote" database is a second embedded H2 database.
     */
    private void testGroovyOverTransactionalLinkedRollback() throws SQLException {
        try (Connection remote = DriverManager.getConnection("jdbc:h2:mem:groovyRemote;DB_CLOSE_DELAY=-1")) {
            Statement rstat = remote.createStatement();
            rstat.execute("CREATE TABLE remote_t(id INT PRIMARY KEY)");
            try (Connection conn = DriverManager.getConnection("jdbc:h2:mem:groovyLocal")) {
                Statement stat = conn.createStatement();
                stat.execute("CREATE LINKED TABLE lt('', 'jdbc:h2:mem:groovyRemote;DB_CLOSE_DELAY=-1', "
                        + "'', '', 'REMOTE_T') AUTOCOMMIT OFF");
                conn.setAutoCommit(false);
                stat.execute("EXECUTE GROOVY $$\n"
                        + "(1..5).each { i -> sql.executeUpdate('INSERT INTO lt VALUES(?)', [i]) }\n$$");
                // nothing visible on the remote side before local commit
                try (ResultSet rs = rstat.executeQuery("SELECT COUNT(*) FROM remote_t")) {
                    assertTrue(rs.next());
                    assertEquals(0, rs.getInt(1));
                }
                conn.rollback();
                // rollback propagates: remote still empty
                try (ResultSet rs = rstat.executeQuery("SELECT COUNT(*) FROM remote_t")) {
                    assertTrue(rs.next());
                    assertEquals(0, rs.getInt(1));
                }
                // now commit a batch and confirm it lands remotely
                stat.execute("EXECUTE GROOVY $$\n"
                        + "(1..5).each { i -> sql.executeUpdate('INSERT INTO lt VALUES(?)', [i]) }\n$$");
                conn.commit();
                try (ResultSet rs = rstat.executeQuery("SELECT COUNT(*) FROM remote_t")) {
                    assertTrue(rs.next());
                    assertEquals(5, rs.getInt(1));
                }
            }
        }
    }

}

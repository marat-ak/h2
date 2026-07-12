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

import org.h2.api.ErrorCode;
import org.h2.test.TestBase;
import org.h2.test.TestDb;

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

}

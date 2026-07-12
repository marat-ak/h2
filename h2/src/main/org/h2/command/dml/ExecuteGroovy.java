/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.command.dml;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.h2.command.CommandInterface;
import org.h2.command.Prepared;
import org.h2.engine.SessionLocal;
import org.h2.jdbc.JdbcConnection;
import org.h2.message.DbException;
import org.h2.message.Trace;
import org.h2.result.ResultInterface;
import org.h2.util.SourceCompiler;
import org.h2.util.StringUtils;
import org.h2.util.Utils;

/**
 * This class represents the statement EXECUTE GROOVY (OSaaS fork).
 *
 * The Groovy source is compiled through the same optional-dependency
 * reflection path as CREATE ALIAS ... AS $$//groovy ...$$ and executed as a
 * script with the following binding:
 * <ul>
 * <li>{@code sql} - a groovy.sql.Sql over the session's internal connection,
 * so all DML participates in the current local transaction (and therefore in
 * transactional linked tables);</li>
 * <li>{@code conn} - the raw java.sql.Connection of the session;</li>
 * <li>{@code vars} - a session-scoped Map&lt;String,Object&gt; surviving
 * across blocks in the same session;</li>
 * <li>{@code log} - a logger writing to the H2 trace; the accumulated text is
 * stored in {@code vars['_log']} after the block completes.</li>
 * </ul>
 * If the script's return value is a ResultSet or a List, it is stored in
 * {@code vars['_result']} (ADR-13). The statement itself returns update
 * count 0. Admin rights are required (same trust model as CREATE ALIAS with
 * source code, ADR-6); SET GROOVY_BLOCKS FALSE disables the statement.
 */
public class ExecuteGroovy extends Prepared {

    /**
     * Compiled scripts cached by source hash (bounded, cleared when large).
     */
    private static final ConcurrentHashMap<String, Class<?>> SCRIPT_CACHE = new ConcurrentHashMap<>();

    private static final int MAX_CACHED_SCRIPTS = 256;

    private final String source;

    public ExecuteGroovy(SessionLocal session, String source) {
        super(session);
        this.source = source;
    }

    @Override
    public long update() {
        session.getUser().checkAdmin();
        if (!getDatabase().isGroovyBlocksEnabled()) {
            throw DbException.getUnsupportedException(
                    "EXECUTE GROOVY (disabled by SET GROOVY_BLOCKS FALSE)");
        }
        Class<?> clazz = compile(source);
        try {
            Object binding = Utils.newInstance("groovy.lang.Binding");
            JdbcConnection conn = session.createConnection(false);
            setVariable(binding, "conn", conn);
            HashMap<String, Object> vars = session.getGroovyVars();
            setVariable(binding, "vars", vars);
            StringBuilder logBuffer = new StringBuilder();
            setVariable(binding, "log", new ScriptLog(session.getTrace(), logBuffer));
            try {
                setVariable(binding, "sql", Utils.newInstance("groovy.sql.Sql", conn));
            } catch (Exception e) {
                // groovy-sql is not on the classpath: the 'sql' binding is
                // simply absent; 'conn' still works
                session.getTrace().debug("groovy.sql.Sql not available: {0}", e.toString());
            }
            Object script = Utils.callStaticMethod(
                    "org.codehaus.groovy.runtime.InvokerHelper.createScript", clazz, binding);
            Object result = Utils.callMethod(script, "run");
            if (logBuffer.length() > 0) {
                vars.put("_log", logBuffer.toString());
            }
            if (result instanceof ResultSet || result instanceof List<?> || result instanceof Map<?, ?>) {
                vars.put("_result", result);
            }
        } catch (DbException e) {
            throw e;
        } catch (Exception e) {
            throw DbException.convert(e);
        }
        return 0;
    }

    private static void setVariable(Object binding, String name, Object value) throws Exception {
        Utils.callMethod(binding, "setVariable", name, value);
    }

    private static Class<?> compile(String source) {
        String key;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            key = StringUtils.convertBytesToHex(md.digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw DbException.convert(e);
        }
        Class<?> clazz = SCRIPT_CACHE.get(key);
        if (clazz == null) {
            try {
                clazz = SourceCompiler.compileGroovyScript(source, "H2GroovyBlock_" + key);
            } catch (RuntimeException e) {
                String message = e.getMessage();
                if (message != null && message.contains("no Groovy jar")) {
                    throw DbException.getUnsupportedException(
                            "EXECUTE GROOVY requires the Groovy jar on the classpath");
                }
                throw DbException.convert(e);
            }
            if (SCRIPT_CACHE.size() >= MAX_CACHED_SCRIPTS) {
                SCRIPT_CACHE.clear();
            }
            SCRIPT_CACHE.put(key, clazz);
        }
        return clazz;
    }

    @Override
    public boolean isTransactional() {
        return true;
    }

    @Override
    public int getType() {
        return CommandInterface.EXECUTE_GROOVY;
    }

    @Override
    public ResultInterface queryMeta() {
        return null;
    }

    /**
     * The 'log' binding: writes to the H2 trace and accumulates the text for
     * vars['_log']. Public so that Groovy scripts can call it.
     */
    public static final class ScriptLog {

        private final Trace trace;
        private final StringBuilder buffer;

        ScriptLog(Trace trace, StringBuilder buffer) {
            this.trace = trace;
            this.buffer = buffer;
        }

        /**
         * Log an informational message.
         *
         * @param message the message
         */
        public void info(Object message) {
            String s = String.valueOf(message);
            trace.info(s);
            buffer.append(s).append('\n');
        }

        /**
         * Log a debug message.
         *
         * @param message the message
         */
        public void debug(Object message) {
            String s = String.valueOf(message);
            trace.debug(s);
            buffer.append(s).append('\n');
        }

        /**
         * Log an error message.
         *
         * @param message the message
         */
        public void error(Object message) {
            String s = String.valueOf(message);
            trace.error(null, s);
            buffer.append(s).append('\n');
        }

        /**
         * Alias for {@link #info(Object)} so that scripts can use
         * {@code log 'text'} via call().
         *
         * @param message the message
         */
        public void call(Object message) {
            info(message);
        }
    }

}

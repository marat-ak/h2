/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.table;

import java.sql.BatchUpdateException;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.h2.engine.SessionLocal;
import org.h2.jdbc.JdbcConnection;
import org.h2.message.DbException;
import org.h2.util.JdbcUtils;
import org.h2.value.Value;

/**
 * Per-session state of a transactional (AUTOCOMMIT OFF) linked table: a
 * dedicated, non-shared remote connection with real autoCommit=false, plus a
 * cache of prepared statements belonging to that connection.
 *
 * The owning session enlists this object and drives {@link #commit()} /
 * {@link #rollback()} from its own commit/rollback, and {@link #close()} when
 * the session ends (OSaaS fork, ADR-8/ADR-10).
 */
public final class TableLinkTransaction {

    private final TableLink table;
    private final SessionLocal session;
    private final TableLinkConnection conn;
    private final HashMap<String, PreparedStatement> preparedMap = new HashMap<>();
    private boolean closed;

    /**
     * Pending JDBC batch (BATCH n option): one reused PreparedStatement for
     * the current SQL shape; a different SQL string flushes first, preserving
     * statement ordering.
     */
    private PreparedStatement batchPrep;
    private String batchSql;
    private int batchCount;

    /**
     * Total number of executeBatch() round-trips (test instrumentation).
     */
    public static final AtomicLong EXECUTE_BATCH_CALLS = new AtomicLong();

    TableLinkTransaction(TableLink table, SessionLocal session, TableLinkConnection conn) {
        this.table = table;
        this.session = session;
        this.conn = conn;
    }

    /**
     * Execute a SQL statement on this transaction's remote connection. Unlike
     * the non-transactional path there is no automatic reconnect: a reconnect
     * would silently discard the open remote transaction.
     *
     * @param sql the SQL statement
     * @param params the parameters or null
     * @param reusePrepared if the prepared statement can be re-used immediately
     * @return the prepared statement, or null if it is re-used
     */
    PreparedStatement execute(String sql, ArrayList<Value> params, boolean reusePrepared) {
        synchronized (conn) {
            try {
                // any direct execution (including reads of this table) first
                // flushes the pending batch: read-your-writes + ordering
                flushBatch();
                PreparedStatement prep = preparedMap.remove(sql);
                if (prep == null) {
                    prep = conn.getConnection().prepareStatement(sql);
                    int fetchSize = table.getFetchSize();
                    if (fetchSize != 0) {
                        prep.setFetchSize(fetchSize);
                    }
                }
                table.traceExecute(sql, params);
                if (params != null) {
                    JdbcConnection ownConnection = session.createConnection(false);
                    for (int i = 0, size = params.size(); i < size; i++) {
                        JdbcUtils.set(prep, i + 1, params.get(i), ownConnection);
                    }
                }
                prep.execute();
                if (reusePrepared) {
                    preparedMap.put(sql, prep);
                    return null;
                }
                return prep;
            } catch (SQLException e) {
                throw DbException.convert(e);
            }
        }
    }

    /**
     * Return a prepared statement to this transaction's statement cache.
     *
     * @param prep the prepared statement
     * @param sql the SQL statement
     */
    void reusePreparedStatement(PreparedStatement prep, String sql) {
        synchronized (conn) {
            preparedMap.put(sql, prep);
        }
    }

    /**
     * Add a DML row to the pending batch. A change of SQL shape flushes the
     * previous batch first (ordering); reaching the table's BATCH size
     * flushes too.
     *
     * @param sql the DML statement
     * @param params the parameters or null
     */
    void addBatch(String sql, ArrayList<Value> params) {
        synchronized (conn) {
            try {
                if (batchPrep != null && !sql.equals(batchSql)) {
                    flushBatch();
                }
                if (batchPrep == null) {
                    batchPrep = preparedMap.remove(sql);
                    if (batchPrep == null) {
                        batchPrep = conn.getConnection().prepareStatement(sql);
                    }
                    batchSql = sql;
                }
                table.traceExecute(sql, params);
                if (params != null) {
                    JdbcConnection ownConnection = session.createConnection(false);
                    for (int i = 0, size = params.size(); i < size; i++) {
                        JdbcUtils.set(batchPrep, i + 1, params.get(i), ownConnection);
                    }
                }
                batchPrep.addBatch();
                if (++batchCount >= table.getBatchSize()) {
                    flushBatch();
                }
            } catch (SQLException e) {
                throw TableLink.wrapException(sql, e);
            }
        }
    }

    /**
     * Flush any pending batched DML to the remote database.
     */
    public void flush() {
        synchronized (conn) {
            try {
                flushBatch();
            } catch (SQLException e) {
                throw DbException.convert(e);
            }
        }
    }

    /**
     * Discard any pending (not yet flushed) batched DML, e.g. when the local
     * statement or transaction is rolled back.
     */
    public void discardBatch() {
        synchronized (conn) {
            if (batchPrep != null) {
                PreparedStatement prep = batchPrep;
                String sql = batchSql;
                batchPrep = null;
                batchSql = null;
                batchCount = 0;
                try {
                    prep.clearBatch();
                    preparedMap.put(sql, prep);
                } catch (SQLException e) {
                    JdbcUtils.closeSilently(prep);
                }
            }
        }
    }

    /**
     * Execute the pending batch, if any. Must be called while synchronized on
     * the connection. A BatchUpdateException is mapped to a DbException
     * carrying the remote error; the transaction stays open so the caller
     * can roll back.
     */
    private void flushBatch() throws SQLException {
        if (batchPrep == null) {
            return;
        }
        PreparedStatement prep = batchPrep;
        String sql = batchSql;
        batchPrep = null;
        batchSql = null;
        batchCount = 0;
        try {
            EXECUTE_BATCH_CALLS.incrementAndGet();
            prep.executeBatch();
            preparedMap.put(sql, prep);
        } catch (BatchUpdateException e) {
            // drop the statement - its batch state is undefined; the
            // connection itself stays usable
            JdbcUtils.closeSilently(prep);
            SQLException cause = e.getNextException() != null ? e.getNextException() : e;
            throw TableLink.wrapException(sql, cause);
        } catch (SQLException e) {
            JdbcUtils.closeSilently(prep);
            throw TableLink.wrapException(sql, e);
        }
    }

    /**
     * Flush pending work and commit the remote transaction.
     */
    public void commit() {
        flush();
        synchronized (conn) {
            try {
                conn.getConnection().commit();
            } catch (SQLException e) {
                throw DbException.convert(e);
            }
        }
    }

    /**
     * Discard pending work and roll back the remote transaction.
     */
    public void rollback() {
        discardBatch();
        synchronized (conn) {
            try {
                conn.getConnection().rollback();
            } catch (SQLException e) {
                throw DbException.convert(e);
            }
        }
    }

    /**
     * Close the remote connection (implicitly rolling back any open remote
     * transaction) and unregister from the owning table and session.
     */
    public void close() {
        if (!closed) {
            closed = true;
            table.removeTransaction(session);
            session.removeLinkedTransaction(this);
            conn.close(true);
        }
    }

}

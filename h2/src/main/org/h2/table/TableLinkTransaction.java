/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.table;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;

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
     * Flush any pending batched DML to the remote database. No-op until
     * batching is implemented (Phase 2).
     */
    public void flush() {
        // batching added in Phase 2
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
        // pending batches are discarded here in Phase 2
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

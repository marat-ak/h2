# H2 Fork Design — Transactional/Batched Linked Tables + Embedded Groovy Scripting

Date: 2026-07-10
Status: Approved baseline (see DECISIONS.md for open items and chosen defaults)
Base: h2database tag `version-2.2.224` (matches OSaaSIntegrationsH2 dependency)
Fork: https://github.com/marat-ak/h2 branch `osaas`

## Motivation

OSaaS uses H2 with `CREATE LINKED TABLE` over a custom Oracle Fusion JDBC driver
(OSaaSJDBC). Two problems:

1. **Per-row remote commit.** DML against a linked table commits on the remote
   side after every row, even when the local H2 session has autocommit off.
   The local transaction never propagates to the linked JDBC connection, and
   rows are sent one `PreparedStatement.executeUpdate()` at a time.
2. **No procedural scripting.** SQL scripts cannot express loops, conditions,
   or variables (cursor over a SELECT + conditional DML). Users need a
   human-friendly way to write procedural logic inside a script without
   creating stored procedures.

## Feature 1: Transactional + batched linked-table DML

### Current behavior (verify in source during Phase 0)

- `org.h2.table.TableLink` — linked table implementation; obtains a
  `org.h2.util.TableLinkConnection`, which is **shared across sessions** keyed
  by driver/url/user when `DbSettings.shareLinkedConnections` is true (default).
- `org.h2.index.LinkedIndex` — `add()` / `remove()` / (update path) build SQL
  and call `execute()` per row, immediately, on the shared connection.
- The remote connection is left in `autoCommit=true`. H2 session
  commit/rollback (`org.h2.engine.SessionLocal`) never touches linked
  connections.

Result: every row is a remote round-trip and a remote commit; local rollback
does not undo remote changes.

### Target behavior

New per-table options on `CREATE LINKED TABLE` plus global defaults:

> **ADR-10 update:** the transactional option is spelled `AUTOCOMMIT OFF` —
> reusing (and repairing) the option upstream 2.2.224 already parses but
> ignores. Wherever this document says `TRANSACTIONAL`, read `AUTOCOMMIT OFF`.

```sql
CREATE LINKED TABLE T('', 'jdbc:...', 'user', 'pw', 'REMOTE_TABLE')
    AUTOCOMMIT OFF         -- enlist remote connection in local tx (ADR-10)
    BATCH 500;             -- batch DML rows, flush every 500
-- global defaults (db settings, also settable in the JDBC URL):
SET LINKED_TABLE_TRANSACTIONAL TRUE;
SET LINKED_TABLE_BATCH_SIZE 500;
```

Semantics:

- **TRANSACTIONAL**: the linked table gets a **per-session, non-shared** remote
  connection with `autoCommit=false`. The session tracks enlisted linked
  connections. On local commit → flush pending batches → remote
  `commit()` (remote-first ordering) → local commit. On local rollback →
  discard pending batches → remote `rollback()`.
  Sharing (`shareLinkedConnections`) is bypassed for transactional tables —
  transactions cannot share a connection across sessions.
- **BATCH n**: `LinkedIndex.add()` accumulates rows via `addBatch()` on a
  reused PreparedStatement and flushes with `executeBatch()` when:
  - n rows accumulated, or
  - the current DML statement finishes (default; keeps JDBC update counts
    correct), or
  - any read touches the same linked table (read-your-writes), or
  - commit / explicit flush.
  UPDATE/DELETE with same SQL shape reuse the same batch; a different SQL
  string forces a flush first (ordering preserved).
- Failure in `executeBatch()` → map `BatchUpdateException` to an H2
  `DbException` carrying the remote message; transaction stays open so the
  user can roll back.
- Non-transactional tables keep exact current behavior. All changes are
  opt-in; defaults preserve upstream semantics (important for merging
  upstream later and for unrelated users of the fork).

### Out of scope (v1, documented)

- Two-phase commit / XA between local and remote (best-effort ordering only;
  remote-commit-then-local means a crash between the two can leave remote
  committed and local rolled back — acceptable for the OSaaS integration use
  case, documented in DECISIONS.md ADR-3).
- Savepoints propagation to remote.
- Cross-statement batching (deferred update counts) — possible Phase-2 flag
  `BATCH DEFERRED`.

### Touched code (verified against 2.2.224 sources, Phase 0 recon 2026-07-10)

| Area | File | Change |
|---|---|---|
| Parser | `org.h2.command.Parser.parseCreateLinkedTable` (~L8872) | `AUTOCOMMIT ON\|OFF` ALREADY parsed (~L8908, ADR-10); add only `BATCH n` |
| DDL | `org.h2.command.ddl.CreateLinkedTable` | `setAutoCommit` exists; add batch size carry-through |
| Table | `org.h2.table.TableLink` | `autocommit` field exists but ineffective; per-session connection mode, `getCreateSQL()` already round-trips `AUTOCOMMIT OFF` (L421) |
| Index | `org.h2.index.LinkedIndex` | batch accumulation in `add()`/`remove()`/`update()`, flush triggers, shaped-statement reuse |
| Conn | `org.h2.table.TableLinkConnection` (NOT `org.h2.util` as first drafted) | `setAutoCommit()` (L151) only sets a field today — repair to drive real `Connection.setAutoCommit`; non-shared variant + commit/rollback passthrough |
| Session | `org.h2.engine.SessionLocal` | hooks in `commit(boolean ddl)` (L678), `rollback()` (L797); cleanup in `close()` (L882). NB: `commit(true)` is also re-entered from `analyzeTables()` and `close()` — remote hooks must be idempotent/no-op when nothing enlisted |
| Sharing | `org.h2.engine.Database.getLinkConnection` (L2187) + `DbSettings.shareLinkedConnections` (SHARE_LINKED_CONNECTIONS, default true) | bypass sharing when AUTOCOMMIT OFF (ADR-8) |
| Settings | `org.h2.engine.DbSettings` / `org.h2.command.dml.SetTypes` | LINKED_TABLE_TRANSACTIONAL, LINKED_TABLE_BATCH_SIZE |

Feature-2 recon:

- `EXECUTE` parser branch at `Parser.java` L695: `EXECUTE IMMEDIATE <expr>` →
  `org.h2.command.ddl.ExecuteImmediate` is the template for `EXECUTE GROOVY`
  (GROOVY branch must be read before IMMEDIATE/Postgre fallbacks).
- `org.h2.util.SourceCompiler.isGroovySource()` requires source starting with
  `//groovy` or `@groovy` — the Groovy command must prepend `//groovy\n` (or
  call the Groovy path directly) before compiling.
- `$$...$$` dollar-quoted literals are lexed as plain string tokens, so
  `EXECUTE GROOVY $$...$$` needs only `readString()`.
- Groovy loaded via reflection in `SourceCompiler.GroovyCompiler`; missing jar
  surfaces as stored `INIT_FAIL_EXCEPTION` ("Compile fail: no Groovy jar in
  the classpath").

### Tests

Use H2's own test harness (`org.h2.test`) + plain JUnit where simpler. The
"remote" database is a second embedded H2 db — no external infra needed.

1. Baseline (Phase 0): prove current per-row commit (insert 3 rows in open tx,
   crash/rollback local, remote has 3 rows). This test flips expectation once
   the fix lands.
2. Transactional: no rows visible remotely (from a separate connection) until
   local commit; local rollback → 0 remote rows.
3. Batch: insert 1000 rows → remote receives ≤ ceil(1000/n) executeBatch calls
   (count via a wrapping JDBC driver or H2 trace); update counts correct.
4. Read-your-writes: INSERT then SELECT same linked table in one tx sees rows.
5. Mixed-shape DML ordering: INSERT, UPDATE, INSERT preserve order.
6. Error path: remote constraint violation surfaces as SQLException; rollback
   works; connection still usable.
7. Regression: existing linked-table tests in upstream suite stay green
   (`org.h2.test.db.TestLinkedTable`).

## Feature 2: Embedded Groovy blocks in SQL scripts

### Options considered

- **A. PL/SQL-like grammar in H2 parser** (DECLARE/BEGIN/FOR/IF/LOOP).
  Months of work in the ~10k-line hand-written `Parser.java`, permanent merge
  burden vs upstream, and users learn yet another dialect. Rejected for v1;
  possible later as syntax sugar compiled down to option B.
- **B. Embedded Groovy blocks (CHOSEN).** H2 already compiles Groovy:
  `org.h2.util.SourceCompiler` detects `//groovy` sources for
  `CREATE ALIAS ... AS $$ ... $$`, loading Groovy by reflection (optional
  dependency, no hard jar dep). Groovy gives variables, if/for/each, closures,
  and `groovy.sql.Sql` for cursor loops — the most human-friendly option:

  ```sql
  EXECUTE GROOVY $$
      sql.eachRow('SELECT id, status FROM orders WHERE status = ?', ['NEW']) { row ->
          if (row.id % 2 == 0) {
              sql.executeUpdate('UPDATE orders SET status = ? WHERE id = ?', ['PROCESSED', row.id])
          }
      }
  $$;
  ```

- **C. Marker preprocessing only** (`<<groovy start>> ... <<groovy end>>`
  handled by RunScript/Shell, no parser change). Zero parser risk but does not
  work for scripts sent over plain JDBC. Kept as an **additional** convenience
  layer on top of B.

### Design (option B + C)

1. New statement `EXECUTE GROOVY $$ <source> $$` (also accept single-quoted
   string). Parser change is tiny: extend the existing `EXECUTE` branch; the
   `$$...$$` dollar-quoted literal is already lexed by H2.
2. Execution: compile via `SourceCompiler` Groovy path (cache compiled class
   by source hash). Run with a binding:
   - `sql` — `groovy.sql.Sql` over the **session's internal connection**
     (same mechanism triggers use), so all DML participates in the current
     local transaction and composes with Feature 1.
   - `conn` — the raw `java.sql.Connection` for JDBC-level work.
   - `vars` — session-scoped `Map<String,Object>` surviving across blocks in
     the same session (lets multiple blocks share state).
   - `log` — appends to H2 trace + collected into the statement result.
3. Result: if the script's return value is a `ResultSet` or `List<Map>`, it is
   exposed as a query result; otherwise the statement returns an update count
   of 0 and `log` output is available via `vars['_log']`.
4. Security: requires admin rights, same as `CREATE ALIAS` with source code.
   Blocked when `ALLOW_LITERALS`-style hardening or a new
   `SET GROOVY_BLOCKS FALSE` (default TRUE for embedded, FALSE for server
   mode? → ADR-6 default: allowed for admin only, any mode).
5. Marker preprocessing (option C layer): `org.h2.tools.RunScript` and
   `org.h2.tools.Shell` recognize lines `<<groovy>>` / `<<groovy start>>` and
   `<<groovy end>>` (case-insensitive) and rewrite the enclosed text to
   `EXECUTE GROOVY $$...$$` before execution — the exact marker style the
   user asked for, working in hand-written .sql files.
6. Groovy jar is provided by the application classpath (OSaaSIntegrationsH2
   adds `org.apache.groovy:groovy:4.x` + `groovy-sql`). H2 itself keeps it
   optional; `EXECUTE GROOVY` without Groovy on classpath → clear error
   message.

### Tests

1. Scalar script: `EXECUTE GROOVY $$ 1 + 1 $$` runs, no error.
2. Cursor loop + conditional DML over a normal table; verify rows.
3. Same over a **linked** table with TRANSACTIONAL — rollback removes remote
   effects (integration of both features).
4. `vars` persists across two blocks in one session, isolated between sessions.
5. Non-admin user → access denied.
6. Missing Groovy jar → clean error, not ClassNotFound stack.
7. Marker preprocessing in RunScript: .sql file with `<<groovy start>>` block
   executes end-to-end.

## Compatibility & versioning

- Fork version: `2.2.224-osaas.1` (pom `<version>`), so OSaaSIntegrationsH2
  can pin it explicitly; drop-in replacement otherwise.
- All new behavior opt-in; upstream test suite must stay green.
- Keep changes in focused commits per phase to ease future upstream rebase.

## Build

Maven module lives under `h2/` in the repo (`h2/pom.xml`). Java 17 OK.
`mvn -f h2/pom.xml -DskipTests package` for the jar;
targeted tests via `mvn test -Dtest=...` or H2's `org.h2.test.TestAll`.

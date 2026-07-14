# Progress Tracking

## Resume protocol (for any agent/session picking this up)

1. Read `docs/osaas/DESIGN.md`, `DECISIONS.md`, `PLAN.md`, then this file.
2. `git log --oneline -15` — cross-check last entry here vs actual commits.
   If a commit exists without a log entry below, reconstruct the entry first.
3. Check working tree (`git status`) — finish or stash any half-done task
   before starting the next.
4. Continue from the first unchecked PLAN.md item.
5. After finishing a task: check it off in PLAN.md, append a log entry below,
   commit code + PLAN.md + PROGRESS.md together.

## Status

Current phase: 3 (Groovy blocks)
Next task: 3.3 marker preprocessing (RunScript+Shell)

How to run a single H2 test class (no surefire):
`cd h2 && ./mvnw -q test-compile && ./mvnw -q dependency:build-classpath -Dmdep.outputFile=target/cp.txt`
then `java -cp "target/classes;target/test-classes;$(cat target/cp.txt)" org.h2.test.db.TestLinkedTable`
(test main is silent on success; throws AssertionError on failure).

## Log

| Date (UTC) | Task | Result | Commit |
|---|---|---|---|
| 2026-07-10 | setup | Fork created from version-2.2.224, planning docs added | (this commit) |
| 2026-07-10 | 0.1 | Build green: `./mvnw -DskipTests package` exit 0, `h2/target/h2-2.2.224.jar` produced, Temurin 17.0.12 | ca110285c |
| 2026-07-10 | 0.2 | Recon: DESIGN touched-code table corrected (TableLinkConnection in org.h2.table; AUTOCOMMIT OFF already parsed per ADR-10; EXECUTE branch Parser.java:695; SourceCompiler needs //groovy prefix) | cd02cd5a4 |
| 2026-07-10 | 0.3 | Baseline test TestLinkedTableTransactional green: AUTOCOMMIT OFF is a no-op upstream — 3 rows visible remotely mid-tx and after local rollback (expectations marked FLIP for Phase 1). Registered in TestAll. | 162d2a1bf |
| 2026-07-10 | 0.4 | Upstream org.h2.test.db.TestLinkedTable baseline: GREEN (default config, class main runner) | 64793e204 |
| 2026-07-10 | 1.1 | AUTOCOMMIT OFF DDL round-trip verified by new test (SCRIPT output + file-db reopen through meta SQL; default/ON emit no AUTOCOMMIT clause). No product code change needed (ADR-10). TestLinkedTableTransactional green. | 92cea0448 |
| 2026-07-10 | 1.2 | New TableLinkTransaction: per-session non-shared remote conn, real autoCommit=false; TableLink.execute/reusePreparedStatement route via it; SessionLocal register/remove plumbing; connect() no longer calls no-op setAutoCommit. Test flipped: mid-tx + post-rollback remote count now 0 (was 3); read-your-writes SELECT sees 3. TestLinkedTable green. | 8ba49c5be |
| 2026-07-11 | 1.3 | SessionLocal hooks: commit(ddl) commits enlisted remotes first (ADR-3); rollback() rolls remotes back collecting errors so local rollback always completes; close() closes enlisted conns. Tests: commit propagation, conn reuse across txs, autocommit statement-end commit, session-close rollback+cleanup. TestLinkedTable + TestTransaction green. | d50929fba |
| 2026-07-11 | 1.4 | SET LINKED_TABLE_TRANSACTIONAL TRUE|FALSE (SetTypes+Set, admin, persisted) drives CreateLinkedTable default when no AUTOCOMMIT option; URL param executes as SET at session open (ADR-11 — DbSettings+SetTypes double registration is dead in both paths). Tests: SET/URL default, explicit ON override, FALSE reset. TestLinkedTable green. | 4c30dda66 |
| 2026-07-11 | 1.5 | Phase-1 gate green: DESIGN #2 (visibility/rollback, tasks 1.2-1.3 tests), new #6 error-path test (duplicate PK -> SQLException, tx stays open, rollback works, conn reusable), #7 TestLinkedTable + TestTransaction green. | 4dad1fe82 |
| 2026-07-11 | 2.1 | Batch machinery: TableLinkTransaction.addBatch/flushBatch/discardBatch (shape-keyed stmt reuse, flush on shape change/size/any read; BatchUpdateException -> wrapException with getNextException); LinkedIndex add/remove/update route via TableLink.executeDml; BATCH n parser+DDL round-trip; BATCH>1 without AUTOCOMMIT OFF rejected (ADR-12). Tests: 12 rows/BATCH 5 -> 3 executeBatch calls, read-your-writes, mixed-shape ordering, rollback discards pending. | e5bff6fab |
| 2026-07-11 | 2.2 | Statement-end flush in SessionLocal.endStatement (ADR-4: correct update counts); rollbackTo discards pending batch of failed statement; SET LINKED_TABLE_BATCH_SIZE n (SetTypes, admin, persisted) default for new transactional tables, explicit BATCH overrides, 0 disables. 2.1 accumulation test reworked to one multi-row INSERT (2 size flushes + 1 statement-end). All gates green. | 2952f8176 |
| 2026-07-11 | 2.3 | Batch error mapping test: duplicate key inside a batch surfaces as 90111 ERROR_ACCESSING_LINKED_TABLE_2 with remote message (23505) preserved; tx stays open, rollback undoes flushed rows, connection reusable (mapping code landed in 2.1). | 911933311 |
| 2026-07-11 | 2.4 | Phase-2 gate green: DESIGN #3 (3 executeBatch calls for 12 rows/BATCH 5, update counts 12/3 correct), #4 (read-your-writes without extra flush), #5 (mixed-shape ordering); TestLinkedTableTransactional + TestLinkedTable + TestTransaction green. | d0800ed97 |
| 2026-07-12 | 3.1 | EXECUTE GROOVY $$...$$ / '...' statement: Parser branch before IMMEDIATE; org.h2.command.dml.ExecuteGroovy compiles via new SourceCompiler.compileGroovyScript (reflection, groovy optional), SHA-256 source-hash class cache, admin check, SET GROOVY_BLOCKS kill switch (SetTypes, persisted). Binding conn/vars/log/sql already wired (tests in 3.2). groovy+groovy-sql added test-scope to pom. Missing-jar probe: clean 50100 "requires the Groovy jar". TestExecuteGroovy (scalar/admin/kill-switch) + TestLinkedTable green. | 6fc7e395d |
| 2026-07-14 | 3.2 | Bindings (sql/conn/vars/log) confirmed already delivered by 3.1; no code change. Verified by new binding tests folded into 3.4. | (folded into 3.1) |
| 2026-07-14 | 3.3 | org.h2.util.GroovyScriptMarkers: rewrites `<<groovy start>>`..`<<groovy end>>` (case-insensitive, own-line; `<<groovy>>` alias) into `EXECUTE GROOVY $$..$$;` before the statement splitter. Wired into RunScript (both reader paths) and Shell (line-collect loop). Body must not contain `$$`. TestLinkedTable green (no regression on RunScript path). | (this commit) |

## Open issues / parked

(none yet)

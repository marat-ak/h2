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

Current phase: 1 (transactional linked tables)
Next task: 1.2 per-session non-shared transactional connection

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
| 2026-07-10 | 1.1 | AUTOCOMMIT OFF DDL round-trip verified by new test (SCRIPT output + file-db reopen through meta SQL; default/ON emit no AUTOCOMMIT clause). No product code change needed (ADR-10). TestLinkedTableTransactional green. | (this commit) |

## Open issues / parked

(none yet)

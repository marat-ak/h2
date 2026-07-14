# Implementation Plan

Repo: https://github.com/marat-ak/h2 branch `osaas`, based on `version-2.2.224`.
Read DESIGN.md and DECISIONS.md first. Update PROGRESS.md after EVERY task.
One commit per task (code + doc updates together). Never batch multiple tasks
into one commit — resumability depends on commit granularity.

## Phase 0 — Bootstrap & baseline (no behavior change)

- [x] 0.1 Build green: `mvn -f h2/pom.xml -DskipTests package` succeeds on Java 17.
- [x] 0.2 Source recon: verify/correct the "Touched code" table in DESIGN.md
      against actual 2.2.224 sources (TableLink, LinkedIndex,
      TableLinkConnection, SessionLocal, Parser.parseCreateLinkedTable,
      DbSettings, SourceCompiler groovy path). Record corrections in DESIGN.md.
- [x] 0.3 Baseline test proving per-row remote commit + no rollback
      propagation (two embedded dbs). Committed failing-expectation-flipped
      form described in DESIGN.md Tests #1.
- [x] 0.4 Run upstream linked-table tests (TestLinkedTable) — record baseline
      result in PROGRESS.md.

## Phase 1 — Transactional linked tables

- [x] 1.1 Parser + CreateLinkedTable: accept `TRANSACTIONAL` option;
      TableLink stores it; `getCreateSQL()` round-trips it; DDL test.
      (ADR-10: option spelled AUTOCOMMIT OFF; upstream already parses/stores/
      round-trips it — task delivered as verification + DDL round-trip test.)
- [x] 1.2 Per-session non-shared remote connection with autoCommit=false for
      transactional tables (bypass shareLinkedConnections, ADR-8).
- [x] 1.3 SessionLocal enlistment: commit → flush → remote commit → local
      commit; rollback → discard → remote rollback. Close/cleanup on session
      close, including error paths.
- [x] 1.4 Global default `SET LINKED_TABLE_TRANSACTIONAL TRUE` + URL setting.
      (ADR-11: SetTypes-based; URL param executes as SET at session open.)
- [x] 1.5 Tests: DESIGN Tests #2, #6, #7 green.

## Phase 2 — Batched DML

- [x] 2.1 LinkedIndex batch accumulation (addBatch/executeBatch), statement
      reuse keyed by SQL shape, flush on shape change (ordering).
- [x] 2.2 Flush triggers: batch size, statement end, read of same table
      (read-your-writes), commit. `BATCH n` option + `SET
      LINKED_TABLE_BATCH_SIZE n` default.
- [x] 2.3 Error mapping: BatchUpdateException → DbException with remote
      message; connection usable after failure.
- [x] 2.4 Tests: DESIGN Tests #3, #4, #5 green; update counts verified.

## Phase 3 — Groovy blocks

- [x] 3.1 `EXECUTE GROOVY $$...$$` statement: parser branch + command class
      compiling via SourceCompiler (cache by source hash), admin check,
      `SET GROOVY_BLOCKS` kill switch (ADR-6).
- [x] 3.2 Binding: `sql`, `conn`, `vars` (session-scoped map), `log`.
      Result-set passthrough for ResultSet/List<Map> returns. (delivered in 3.1)
- [x] 3.3 Marker preprocessing in RunScript + Shell:
      `<<groovy start>> ... <<groovy end>>` → EXECUTE GROOVY (ADR-7).
- [x] 3.4 Tests: DESIGN Feature-2 tests #1–#7, incl. combined
      groovy-over-transactional-linked-table rollback test.

## Phase 4 — Release & integration

- [ ] 4.1 Version `2.2.224-osaas.1` in h2/pom.xml; build final jar.
- [ ] 4.2 Fork README section: features, syntax, semantics, ADR-3 caveat.
- [ ] 4.3 Full upstream test suite run; record deltas (goal: zero).
- [ ] 4.4 Smoke doc for OSaaSIntegrationsH2: dependency swap + groovy jars.

## Verification gates

- After each phase: all phase tests + TestLinkedTable green; commit hash
  recorded in PROGRESS.md.
- No task is "done" without a test or an explicit note why untestable.

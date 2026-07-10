# Remote Development Task — H2 Fork for OSaaS

Copy-paste this as the prompt when launching a cloud/remote Claude Code agent
on https://github.com/marat-ak/h2 (branch `osaas`).

---

You are working in the `osaas` branch of a fork of H2 database
(base: upstream tag version-2.2.224). All project documentation lives in
`docs/osaas/`:

- `DESIGN.md` — full technical design (read first)
- `DECISIONS.md` — ADR log; add ADRs for any non-trivial choice you make
- `PLAN.md` — phased task list with checkboxes
- `PROGRESS.md` — log + resume protocol

Follow the resume protocol in PROGRESS.md: find the first unchecked task in
PLAN.md and execute tasks strictly in order. After every task, check it off,
append a PROGRESS.md log row (date, task, result, commit hash), and make ONE
commit containing the code and the doc updates together. Small, per-task
commits are mandatory — the run must be resumable from any interruption point.

Goals (details in DESIGN.md):

1. Linked tables (`CREATE LINKED TABLE`): add opt-in `TRANSACTIONAL` mode
   (remote connection enlisted in the local H2 transaction — no more
   commit-per-row, rollback propagates) and `BATCH n` (JDBC batching with
   flush on batch-size/statement-end/read-your-writes/commit).
2. New `EXECUTE GROOVY $$ ... $$` statement executing Groovy in the current
   session/transaction (binding: sql, conn, vars, log), plus
   `<<groovy start>> / <<groovy end>>` marker preprocessing in RunScript and
   Shell.

Hard constraints:

- Defaults must preserve upstream behavior exactly; everything is opt-in.
- Upstream test suite (at minimum org.h2.test.db.TestLinkedTable, plus the
  full suite in Phase 4) must stay green.
- Java 17, Maven build under `h2/pom.xml`.
- Test-first where practical: each phase's tests are specified in DESIGN.md.
- Push to origin after each completed task so work is never lost.

If you finish all phases, stop after Phase 4 and summarize deltas vs upstream.
If blocked (e.g., a design assumption in DESIGN.md is wrong), correct the doc,
record an ADR, and continue — do not silently deviate.
---

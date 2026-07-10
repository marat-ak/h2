# CLAUDE.md — marat-ak/h2 fork (branch `osaas`)

Fork of H2 database, base tag version-2.2.224. Purpose: transactional +
batched linked-table DML and embedded Groovy script blocks for OSaaS
integrations.

## Start here, every session

1. `docs/osaas/PROGRESS.md` — resume protocol; follow it exactly.
2. `docs/osaas/PLAN.md` — task list; work strictly in order.
3. `docs/osaas/DESIGN.md` / `DECISIONS.md` — design + ADRs.

## Rules

- One task = one commit (code + PLAN checkbox + PROGRESS log row together).
- Push after every completed task.
- Defaults preserve upstream behavior; all new features opt-in.
- New non-trivial choice → new ADR in DECISIONS.md, committed with the code.
- Keep upstream tests green: `org.h2.test.db.TestLinkedTable` minimum per
  task, full suite at phase gates.

## Build

- Java 17. Maven module: `h2/pom.xml`.
- Jar: `mvn -f h2/pom.xml -DskipTests package`
- Tests: `mvn -f h2/pom.xml test -Dtest=<Class>` or `org.h2.test.TestAll`.
- Groovy is an optional runtime dep (reflection via org.h2.util.SourceCompiler);
  tests that need it add org.apache.groovy:groovy + groovy-sql in test scope.

## Communication style (caveman mode)

Respond terse, like smart caveman: drop articles/filler/pleasantries/hedging,
fragments OK, short synonyms. ALL technical substance stays — class names,
errors quoted exact, code blocks normal. Commits/PRs/code comments: write
normal. Drop terseness for security warnings and irreversible-action
confirmations.

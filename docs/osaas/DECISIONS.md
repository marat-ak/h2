# Decisions (ADR log)

Format: one ADR per decision. Status: accepted | proposed | superseded.
Remote agent: when you make a non-trivial choice not covered here, ADD AN ADR
and commit it with the code that implements it.

## ADR-1: Base fork on tag `version-2.2.224` — accepted
OSaaSIntegrationsH2 pins h2 2.2.224. Drop-in replacement beats chasing 2.3.x.
Rebase to 2.3.x is a possible later phase, aided by opt-in-only changes.

## ADR-2: All new behavior opt-in, defaults = upstream — accepted
Keeps upstream test suite green, eases future rebases, and cannot break
non-OSaaS uses of the fork.

## ADR-3: Best-effort transaction ordering, no XA — accepted
On local commit: flush batches → remote commit → local commit. A crash
between remote and local commit can leave remote committed while local rolled
back. Acceptable for OSaaS integration flows (idempotent loads); XA/2PC out of
scope. Document in fork README.

## ADR-4: Batch flush at statement end by default — accepted
Batching *within* one DML statement gives correct JDBC update counts and
removes per-row round-trips/commits. Cross-statement deferred batching (wrong
counts until flush) only behind a future `BATCH DEFERRED` option if needed.

## ADR-5: Groovy blocks instead of PL/SQL grammar — accepted
`Parser.java` is a ~10k-line hand-written parser; a PL/SQL subset is months of
work and a permanent merge burden. H2's `SourceCompiler` already knows Groovy.
Groovy closures over `groovy.sql.Sql` cover loop/if/variables ask directly.
PL/SQL-flavored sugar can compile down to this later if ever wanted.

## ADR-6: Groovy blocks require admin, allowed in all modes — accepted
Same trust model as `CREATE ALIAS ... AS $$source$$` (arbitrary code, admin
only). Additional kill switch `SET GROOVY_BLOCKS FALSE` persisted in db
settings.

## ADR-7: Two entry syntaxes — accepted
`EXECUTE GROOVY $$...$$` (parser-level, works over any JDBC client) plus
`<<groovy start>> ... <<groovy end>>` marker preprocessing in
RunScript/Shell (works in hand-written .sql files, the user's requested
style).

## ADR-8: Transactional linked tables never share connections — accepted
`shareLinkedConnections` bypassed when TRANSACTIONAL: a shared remote
connection cannot hold per-session transactions. Non-transactional linked
tables keep sharing as today.

## ADR-9: Fork version `2.2.224-osaas.N` — accepted
Monotonic N per released build. Published to local repo / GitHub packages as
needed by OSaaSIntegrationsH2.

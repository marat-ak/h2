# Using this fork in OSaaSIntegrationsH2

This fork ships as `com.h2database:h2:2.2.224-osaas.1` — a drop-in replacement
for the stock `2.2.224` jar plus the two OSaaS features (transactional/batched
linked tables and `EXECUTE GROOVY`). See the repo root `README.md` and
`docs/osaas/DESIGN.md` for behavior.

## 1. Build and install the fork jar

From the fork repo:

```sh
cd h2
./mvnw -DskipTests package
# produces h2/target/h2-2.2.224-osaas.1.jar
./mvnw -DskipTests install      # installs it into your local ~/.m2 repository
```

`install` publishes `com.h2database:h2:2.2.224-osaas.1` to the local Maven
repo so downstream modules can resolve it. For a shared team build, deploy it
to your internal Maven repository / GitHub Packages instead.

## 2. Point OSaaSIntegrationsH2 at it

In `OSaaSIntegrationsH2/pom.xml` change the H2 version:

```xml
<dependency>
    <groupId>com.h2database</groupId>
    <artifactId>h2</artifactId>
    <version>2.2.224-osaas.1</version>   <!-- was 2.2.224 -->
</dependency>
```

Nothing else changes — the group/artifact are identical, so it is a straight
version bump.

## 3. Add Groovy (only if you use EXECUTE GROOVY)

Groovy is an optional runtime dependency of the fork, loaded by reflection.
`EXECUTE GROOVY` (and the `<<groovy start>>`/`<<groovy end>>` markers) need it
on the classpath at runtime; the transactional/batched linked-table features do
**not**.

```xml
<dependency>
    <groupId>org.apache.groovy</groupId>
    <artifactId>groovy</artifactId>
    <version>4.0.21</version>
</dependency>
<dependency>
    <groupId>org.apache.groovy</groupId>
    <artifactId>groovy-sql</artifactId>   <!-- required for the `sql` binding -->
    <version>4.0.21</version>
</dependency>
```

Without these jars, `EXECUTE GROOVY` fails with a clear
`Feature not supported: EXECUTE GROOVY requires the Groovy jar on the
classpath` and the rest of the engine is unaffected.

## 4. Turn the features on

Per linked table:

```sql
CREATE LINKED TABLE T('', 'jdbc:oracle:...', 'user', 'pw', 'REMOTE_TABLE')
    AUTOCOMMIT OFF BATCH 500;
```

Or set database/session defaults (e.g. at connection open) so existing
`CREATE LINKED TABLE` statements become transactional without edits:

```sql
SET LINKED_TABLE_TRANSACTIONAL TRUE;
SET LINKED_TABLE_BATCH_SIZE 500;
```

These can also go in the JDBC URL as `SET` init statements the way OSaaS
already runs session setup.

## 5. Smoke check after the swap

1. `CREATE LINKED TABLE ... AUTOCOMMIT OFF`, insert several rows with local
   autocommit off, and confirm from a separate remote connection that nothing
   is visible until the local `COMMIT`, and that a local `ROLLBACK` leaves the
   remote table unchanged.
2. Insert a large batch and confirm remote round-trips drop (trace / remote
   session count) versus the row count.
3. If using Groovy: run a trivial `EXECUTE GROOVY $$ 1 + 1 $$` as an admin
   user to confirm the jar is wired, then a cursor-loop block against a real
   table.

## Caveats

- Best-effort transaction ordering (remote commit then local commit), not XA —
  a crash between the two can leave the remote committed while the local
  rolls back. Acceptable for idempotent OSaaS loads; see
  [ADR-3](DECISIONS.md).
- `EXECUTE GROOVY` runs arbitrary code and requires admin rights; keep it
  disabled (`SET GROOVY_BLOCKS FALSE`) on connections that should not run it.
- This is a fork off `2.2.224`; a later rebase onto a newer H2 is possible
  because all changes are opt-in and isolated (see DECISIONS.md).

[![CI](h2/src/docsrc/images/h2-logo-2.png)](https://github.com/h2database/h2database/actions?query=workflow%3ACI)
# Welcome to H2, the Java SQL database.

> ## OSaaS fork (`2.2.224-osaas.1`)
>
> This is a fork of H2 `2.2.224` for Gnimsys OSaaS. It adds two opt-in
> features; with defaults unchanged the engine behaves exactly like upstream.
> Full design and decision records live in [`docs/osaas/`](docs/osaas/).
>
> ### 1. Transactional and batched linked tables
>
> Upstream `CREATE LINKED TABLE ... AUTOCOMMIT OFF` parsed the option but never
> applied it — every remote INSERT/UPDATE/DELETE committed on the remote side
> one row at a time, and a local rollback did not undo remote changes. This
> fork makes the option real:
>
> ```sql
> CREATE LINKED TABLE T('', 'jdbc:...', 'user', 'pw', 'REMOTE_TABLE')
>     AUTOCOMMIT OFF        -- enlist the remote connection in the local tx
>     BATCH 500;            -- JDBC-batch remote DML, flush every 500 rows
> -- session/database defaults:
> SET LINKED_TABLE_TRANSACTIONAL TRUE;   -- AUTOCOMMIT OFF for new linked tables
> SET LINKED_TABLE_BATCH_SIZE 500;       -- default BATCH size
> ```
>
> * **`AUTOCOMMIT OFF`** gives the linked table a dedicated, non-shared remote
>   connection with `autoCommit=false`. Local `COMMIT` flushes pending batches
>   then commits the remote connection; local `ROLLBACK` rolls the remote back.
> * **`BATCH n`** accumulates remote DML with `addBatch`/`executeBatch`, flushing
>   on batch size, statement end, a read of the same table (read-your-writes),
>   or commit. Remote batch errors surface as an H2 `SQLException` with the
>   remote message; the transaction stays open so you can roll back.
> * Ordering is best-effort (remote commit then local commit), not XA — see
>   [ADR-3](docs/osaas/DECISIONS.md). Everything is opt-in; without these
>   options linked tables behave exactly as in upstream 2.2.224.
>
> ### 2. Embedded Groovy script blocks
>
> Run procedural logic (loops, conditionals, variables, cursor loops) inside a
> SQL script without stored procedures:
>
> ```sql
> EXECUTE GROOVY $$
>     sql.eachRow('SELECT id, status FROM orders WHERE status = ?', ['NEW']) { row ->
>         if (row.id % 2 == 0) {
>             sql.executeUpdate('UPDATE orders SET status = ? WHERE id = ?', ['DONE', row.id])
>         }
>     }
> $$;
> ```
>
> The block runs in the current session and transaction (so it composes with
> transactional linked tables above). Bindings: `sql` (a `groovy.sql.Sql` over
> the session connection), `conn` (the raw `Connection`), `vars` (a
> session-scoped map shared across blocks), and `log`. Admin rights are
> required — the same trust model as `CREATE ALIAS ... AS $$source$$` — and the
> statement can be disabled with `SET GROOVY_BLOCKS FALSE`.
>
> Hand-written `.sql` files run through `RunScript` or the `Shell` tool can use
> markers instead of the statement form:
>
> ```sql
> <<groovy start>>
>     (1..10).each { i -> sql.executeUpdate('INSERT INTO t VALUES(?)', [i]) }
> <<groovy end>>
> ```
>
> Groovy is an **optional** dependency, loaded by reflection. Add
> `org.apache.groovy:groovy` (and `groovy-sql` for the `sql` binding) to your
> classpath; without them `EXECUTE GROOVY` fails with a clear message and the
> rest of the engine is unaffected. See
> [`docs/osaas/OSAAS_INTEGRATION.md`](docs/osaas/OSAAS_INTEGRATION.md) for
> wiring this fork into OSaaSIntegrationsH2.


## The main features of H2 are:

* Very fast, open source, JDBC API
* Embedded and server modes; disk-based or in-memory databases
* Transaction support, multi-version concurrency
* Browser based Console application
* Encrypted databases
* Fulltext search
* Pure Java with small footprint: around 2.5 MB jar file size
* ODBC driver

More information: https://h2database.com

## Downloads

[Download latest version](https://h2database.com/html/download.html) or add to `pom.xml`:

```XML
<dependency>
    <groupId>com.h2database</groupId>
    <artifactId>h2</artifactId>
    <version>2.2.224</version>
</dependency>
```

## Documentation

* [Tutorial](https://h2database.com/html/tutorial.html)
* [SQL commands](https://h2database.com/html/commands.html)
* [Functions](https://h2database.com/html/functions.html), [aggregate functions](https://h2database.com/html/functions-aggregate.html), [window functions](https://h2database.com/html/functions-window.html)
* [Data types](https://h2database.com/html/datatypes.html)

## Support

* [Issue tracker](https://github.com/h2database/h2database/issues) for bug reports and feature requests
* [Mailing list / forum](https://groups.google.com/g/h2-database) for questions about H2
* ['h2' tag on Stack Overflow](https://stackoverflow.com/questions/tagged/h2) for other questions (Hibernate with H2 etc.)

# Limitations

This file lists what the bundle does **not** do, and why. It is updated with every release.

## Current state (0.1.0-SNAPSHOT)

The service renders the same ANSI SQL as NiFi's built-in generic dialect. That means, right now:

- `SELECT` paging uses `LIMIT ... OFFSET`, which **Informix rejects**. `GenerateTableFetch` with a
  partition size and `QueryDatabaseTable` with a fetch limit will fail until `SKIP`/`FIRST` support
  lands.
- `UPSERT` and `INSERT_IGNORE` are not supported; `PutDatabaseRecord` refuses those statement types
  with this dialect selected.
- `CREATE TABLE` / `ALTER TABLE` emit JDBC type names (`VARCHAR`, `TIMESTAMP`, ...) and the
  non-Informix `ADD COLUMNS (...)` form. `UpdateDatabaseTable` will fail.

In other words: at this stage the bundle proves the NAR loads. Do not use it for real flows yet.

## Permanent limitations (by design)

- **No JDBC driver bundled.** IBM's driver is proprietary; add it to `DBCPConnectionPool` yourself.
- **No CDC / replication.** Only incremental extraction and writes through the existing processors.
- **NiFi 1.x is not supported.** The `DatabaseDialectService` extension point only exists in 2.x.
- **Non-logged databases** (`CREATE DATABASE` without `WITH LOG`) have no transactions. Processors
  that rely on rollback (`PutDatabaseRecord` batches) cannot recover partially applied batches there.
- **`DELIMIDENT`.** Double-quoted identifiers only work when the Informix session has `DELIMIDENT`
  set; otherwise `"name"` is a string literal. Identifier quoting will therefore be off by default and
  opt-in through a service property.

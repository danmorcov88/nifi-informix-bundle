# Limitations

This file lists what the bundle does **not** do, and why. It is updated with every release.

## Current state (0.1.0-SNAPSHOT)

`SELECT`, `CREATE TABLE` and `ALTER TABLE` are Informix-specific. `UPSERT` and `INSERT_IGNORE` are
not supported yet; `PutDatabaseRecord` refuses those statement types with this dialect selected
(plain `INSERT`, `UPDATE` and `DELETE` do not go through the dialect and work as usual).

## CREATE TABLE / ALTER TABLE (`UpdateDatabaseTable`)

NiFi hands the dialect only a JDBC type code per column — no length, precision or scale — so every
column gets a general-purpose Informix type:

| JDBC type | Informix column type | Why |
|---|---|---|
| `BIT`, `BOOLEAN` | `BOOLEAN` | |
| `TINYINT`, `SMALLINT` | `SMALLINT` | Informix has no 1-byte integer |
| `INTEGER` | `INTEGER` | |
| `BIGINT` | `BIGINT` | |
| `REAL` | `SMALLFLOAT` | |
| `FLOAT`, `DOUBLE` | `FLOAT` | Informix `FLOAT` is double precision |
| `DECIMAL`, `NUMERIC` | `DECIMAL(32,10)` | maximum precision; an explicit scale behaves the same in ANSI and non-ANSI databases |
| `CHAR`, `NCHAR`, `VARCHAR`, `NVARCHAR`, `LONGVARCHAR`, `LONGNVARCHAR`, `OTHER`, `SQLXML` | `LVARCHAR` (2048 bytes) | same approach as the built-in adapters (one wide string type); `VARCHAR` is capped at 255 |
| string types **in the primary key** | `VARCHAR(255)` | `LVARCHAR` exceeds the index key size (error -550) |
| `CLOB`, `NCLOB` | `CLOB` | needs an sbspace on the server |
| `BINARY`, `VARBINARY`, `LONGVARBINARY` | `BYTE` | simple large object, no sbspace needed |
| `BLOB` | `BLOB` | needs an sbspace on the server |
| `DATE` | `DATE` | |
| `TIME` | `DATETIME HOUR TO SECOND` | |
| `TIMESTAMP`, `TIMESTAMP_WITH_TIMEZONE` | `DATETIME YEAR TO FRACTION(5)` | time zone is not stored |
| anything else (`ARRAY`, `STRUCT`, ...) | the JDBC type name | will fail on Informix; create such tables yourself |

Consequences to be aware of:

- **Row size limit.** Informix rows are limited to 32767 bytes and `LVARCHAR` counts in full, so a
  table with more than 15 string columns cannot be auto-created. Create it yourself with narrower
  types; `UpdateDatabaseTable` then only adds missing columns.
- **Composite keys with several string columns** may still exceed the index key size on servers with
  2 KB pages.
- **`ALTER TABLE ... ADD`** never emits `NOT NULL`: Informix rejects adding a `NOT NULL` column without
  a default to a table that has rows.
- Primary key columns are always created `NOT NULL`, as Informix requires.

## SELECT / paging

- `SKIP`/`FIRST` is used only when the processor pages by row count (no index column). When
  `GenerateTableFetch` pages by column value, the dialect emits a range on that column instead
  (`col >= offset AND col < offset + limit`), like the built-in adapters.
- `SKIP 0` is omitted; `FIRST` is emitted whenever a limit is present. Informix rejects `FIRST 0`,
  and so will this dialect's output — NiFi never requests a zero limit.
- Informix versions before 11.10 do not allow `SKIP`/`FIRST` inside subqueries. The dialect never
  places them in a subquery; a *Custom Query* supplied by you is wrapped as a derived table and the
  paging goes on the outer statement.

## Permanent limitations (by design)

- **No JDBC driver bundled.** IBM's driver is proprietary; add it to `DBCPConnectionPool` yourself.
- **No CDC / replication.** Only incremental extraction and writes through the existing processors.
- **NiFi 1.x is not supported.** The `DatabaseDialectService` extension point only exists in 2.x.
- **Non-logged databases** (`CREATE DATABASE` without `WITH LOG`) have no transactions. Processors
  that rely on rollback (`PutDatabaseRecord` batches) cannot recover partially applied batches there.
- **`DELIMIDENT`.** Double-quoted identifiers only work when the Informix session has `DELIMIDENT`
  set; otherwise `"name"` is a string literal. Identifier quoting is therefore **off by default** and
  opt-in through the service's *Quote Identifiers* property. Turn it on only with `DELIMIDENT=Y` in
  the JDBC URL (`jdbc:informix-sqli://host:port/db:INFORMIXSERVER=srv;DELIMIDENT=Y`) or in the
  server environment. When on, the dialect quotes table and column names it emits (including
  each segment of a dotted `schema.table` name) and leaves already-quoted names alone; it cannot
  quote names inside WHERE / ORDER BY text you supply, nor a `database@server:table` reference.

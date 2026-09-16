# Limitations

This file lists what the bundle does **not** do, and why. It is updated with every release.

## Current state (0.1.0 / main)

All five statement types NiFi asks a dialect for are implemented with Informix syntax: `SELECT`,
`CREATE`, `ALTER`, `UPSERT`, `INSERT_IGNORE`. Plain `INSERT`, `UPDATE` and `DELETE` in
`PutDatabaseRecord` do not go through the dialect and work as they always did.

## UPSERT / INSERT_IGNORE (`PutDatabaseRecord`)

Both are rendered as a `MERGE` with a one-row source built from the record values:

```sql
MERGE INTO orders t
USING (SELECT CAST(? AS INTEGER) AS id, CAST(? AS LVARCHAR(32739)) AS label FROM sysmaster:sysdual) n
ON (t.id = n.id)
WHEN MATCHED THEN UPDATE SET label = n.label
WHEN NOT MATCHED THEN INSERT (id, label) VALUES (n.id, n.label)
```

`INSERT_IGNORE` is the same statement without the `WHEN MATCHED` branch.

- **Requires Informix 11.70 or later** for `MERGE` and `sysmaster:sysdual`.
- **The `CAST` is mandatory.** Informix rejects an untyped `?` in the source projection (-201).
  Parameters are cast to the type the dialect maps from the column's JDBC type.
- **Strings longer than *Upsert String Length* are silently truncated.** String values are cast to
  `LVARCHAR` of that length (default 2048) and Informix truncates without an error. On Informix 15
  you can raise the property up to 32739. On **Informix 14.10 leave it at 2048**: any explicit
  length in a `MERGE` source fails there with error -499 "rowsize exceeds the allowable limit",
  even for short values — a server quirk of `MERGE`, since the same cast works in `INSERT` and
  `SELECT`. Plain `INSERT` through `PutDatabaseRecord` is not affected by this limit.
- **Large-object columns cannot be upserted.** `TEXT`, `BYTE`, `CLOB` and `BLOB` values cannot be
  passed through a `MERGE` source (error -617 "A blob data type must be supplied within this
  context"). Use `INSERT` or `UPDATE` for such tables, or leave those columns out of the record.
- **Update Keys are required.** Without a primary key or *Update Keys* the dialect refuses to build
  the statement (`IllegalArgumentException`), like the Oracle dialect.
- If every column is part of the key there is nothing to update, so the `WHEN MATCHED` branch is
  omitted and the statement behaves like `INSERT_IGNORE`.
- Each record value is bound exactly once, in column order — the contract `PutDatabaseRecord`
  relies on.

Verified with the IBM JDBC driver 15.0.1.4 on Informix 14.10 and 15.0.1: batched upsert on a composite key,
upsert on a `SERIAL` key, `DECIMAL`/`MONEY`/floating `DECIMAL`, `DATETIME` with fewer qualifiers
than the cast (`YEAR TO DAY`), `BOOLEAN`, `INTERVAL` from a string, `NULL` in every column, and
20 000-character strings into `LVARCHAR(30000)` (Informix 15, *Upsert String Length* = 32739).

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
- **Values longer than the column are silently truncated.** Informix does not raise an error when a
  string exceeds an `LVARCHAR` column's length; the auto-created columns hold 2048 bytes.
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
  server environment. Note that without `DELIMIDENT` the driver reports a **space** as its identifier
  quote string, so `PutDatabaseRecord`'s own *Quote Column Identifiers* option is a no-op there. When on, the dialect quotes table and column names it emits (including
  each segment of a dotted `schema.table` name) and leaves already-quoted names alone; it cannot
  quote names inside WHERE / ORDER BY text you supply, nor a `database@server:table` reference.
- **No provenance transit URI with the IBM driver.** `DatabaseMetaData.getURL()` returns `null`
  in the IBM JDBC driver (verified with 15.0.1.4). `QueryDatabaseTable*` and
  `PutDatabaseRecord` pass that value to NiFi's provenance reporter, so `nifi-app.log` shows
  `ERROR ... StandardProvenanceReporter Failed to generate Provenance Event ... Transit URI is not
  set` for every batch. Data flow, state and the SQL are unaffected; only the RECEIVE/SEND
  provenance events are missing. This happens with every dialect, including the generic one, and
  cannot be fixed from a dialect service.

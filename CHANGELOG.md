# Changelog

All notable changes to this project are documented in this file.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [0.1.0] - 2026-09-16

First release. Apache NiFi 2.12.0, Java 21, Informix 14.10 and 15.0.1.

### Added
- Maven multi-module skeleton targeting Apache NiFi 2.12.0 and Java 21.
- `InformixDatabaseDialectService` controller service, registered and loadable as a NAR.
- Unit tests with `nifi-mock`; GitHub Actions build on every push.
- `SELECT` statements use Informix `SKIP n FIRST m` paging (placed directly after `SELECT`,
  `SKIP 0` omitted). Paging by index column emits `col >= offset AND col < offset + limit`,
  matching NiFi's built-in adapters.
- *Quote Identifiers* property (default `false`, because Informix needs `DELIMIDENT=Y` for delimited
  identifiers). When enabled, table and column names are double-quoted; already-quoted names and
  dotted `schema.table` names are handled.
- `CREATE TABLE` / `ALTER TABLE ADD (...)` with Informix column types (`LVARCHAR`, `DECIMAL(32,10)`,
  `DATETIME YEAR TO FRACTION(5)`, `BYTE`, ...), table-level `PRIMARY KEY (...)` so composite keys
  work, and `VARCHAR(255)` for string key columns to stay within the index key size.
- `UPSERT` and `INSERT_IGNORE` as `MERGE INTO ... USING (SELECT CAST(? AS ...) ... FROM sysmaster:sysdual)`,
  binding every record value once in column order as `PutDatabaseRecord` expects.
- *Upsert String Length* property (default 2048): length of the `LVARCHAR` cast for string values
  in `MERGE` statements. Informix 14.10 accepts only the default; Informix 15 accepts up to 32739.
- Integration tests with Testcontainers (`mvn verify -Pintegration-tests`) against the IBM Informix
  developer image, run nightly in CI for 14.10.FC9W1DE and 15.0.1.0.3.
- Docker demo (`demo/run.sh`): Informix + NiFi 2.12.0 with a seeded database and an importable flow
  definition that copies rows between two Informix tables with `QueryDatabaseTableRecord` and
  `PutDatabaseRecord` UPSERT.

# Changelog

All notable changes to this project are documented in this file.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Added
- Maven multi-module skeleton targeting Apache NiFi 2.12.0 and Java 21.
- `InformixDatabaseDialectService` controller service, registered and loadable as a NAR.
  `ALTER` and `CREATE` still render ANSI SQL; Informix type mapping follows in later releases.
- Unit tests with `nifi-mock`; GitHub Actions build on every push.
- `SELECT` statements use Informix `SKIP n FIRST m` paging (placed directly after `SELECT`,
  `SKIP 0` omitted). Paging by index column emits `col >= offset AND col < offset + limit`,
  matching NiFi's built-in adapters.
- *Quote Identifiers* property (default `false`, because Informix needs `DELIMIDENT=Y` for delimited
  identifiers). When enabled, table and column names are double-quoted; already-quoted names and
  dotted `schema.table` names are handled.

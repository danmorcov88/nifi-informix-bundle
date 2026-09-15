# Changelog

All notable changes to this project are documented in this file.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Added
- Maven multi-module skeleton targeting Apache NiFi 2.12.0 and Java 21.
- `InformixDatabaseDialectService` controller service, registered and loadable as a NAR.
  Renders ANSI SQL for `ALTER`, `CREATE` and `SELECT` (identical to the standard NiFi dialect);
  Informix-specific syntax follows in later releases.
- Unit tests with `nifi-mock`; GitHub Actions build on every push.

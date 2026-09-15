# Compatibility

What has actually been tested, not what should work.

## NiFi

| NiFi | Status | How verified |
|---|---|---|
| 2.12.0 | supported | NAR loads and the service enables in `apache/nifi:2.12.0`; unit tests compile against the 2.12.0 API |
| other 2.x | untested | the `DatabaseDialectService` API appeared in 2.x and may change between minor lines |
| 1.x | not supported | no `DatabaseDialectService` extension point |

## Informix

| Informix | Status | How verified |
|---|---|---|
| 15.0.1 (developer edition image `icr.io/informix/informix-developer-database:latest`) | SELECT verified | every `SELECT` shape the dialect generates was executed with `dbaccess` and returned the expected rows; identifier quoting checked with and without `DELIMIDENT` |
| 14.10 | untested | target version; syntax used (`SKIP`/`FIRST`, `MERGE`, `sysmaster:sysdual`) is documented for 11.70+ |
| 12.10 | untested | target version; same syntax applies |

## JDBC driver

| Driver | Status |
|---|---|
| `com.ibm.informix:jdbc` 15.x / 4.50.x | not yet exercised — the dialect only generates SQL; driver-level behaviour is covered by the integration tests planned in Stage D |

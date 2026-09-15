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
| 15.0.1 (developer edition image `icr.io/informix/informix-developer-database:latest`) | all statement types verified by hand | every `SELECT`, `CREATE`, `ALTER` and `MERGE` shape the dialect generates was executed (`dbaccess` and JDBC) and returned the expected rows; identifier quoting checked with and without `DELIMIDENT` |
| 14.10 | untested | target version; syntax used (`SKIP`/`FIRST`, `MERGE`, `sysmaster:sysdual`) is documented for 11.70+ |
| 12.10 | untested | target version; same syntax applies |

## JDBC driver

| Driver | Status |
|---|---|
| `com.ibm.informix:jdbc` 15.0.1.4 | `MERGE` upsert/insert-ignore executed as a batched `PreparedStatement`; metadata type reporting checked (`LVARCHAR` → `LONGVARCHAR`, `SERIAL` → `INTEGER`, `TEXT`/`CLOB` → `CLOB`, `BYTE`/`BLOB` → `BLOB`, `INTERVAL` → `CHAR`) |
| `com.ibm.informix:jdbc` 4.50.x | untested |

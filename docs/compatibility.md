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
| 15.0.1.0.3 (developer edition image) | supported | integration tests: `SELECT` paging, `CREATE`/`ALTER`, batched `MERGE` upsert and insert-ignore, quoting with `DELIMIDENT`, 20 000-char strings with *Upsert String Length* = 32739 |
| 14.10.FC9W1DE (developer edition image) | supported | same integration tests; explicit *Upsert String Length* other than 2048 is rejected by the server in `MERGE` (-499), so keep the default |
| 12.10 | untested | no developer image available; syntax used (`SKIP`/`FIRST`, `MERGE`, `sysmaster:sysdual`) is documented for 11.70+ |

## JDBC driver

| Driver | Status |
|---|---|
| `com.ibm.informix:jdbc` 15.0.1.4 | used by the integration tests against both server versions; metadata type reporting checked (`LVARCHAR` → `LONGVARCHAR`, `SERIAL` → `INTEGER`, `TEXT`/`CLOB` → `CLOB`, `BYTE`/`BLOB` → `BLOB`, `INTERVAL` → `CHAR`) |
| `com.ibm.informix:jdbc` 4.50.x | untested |

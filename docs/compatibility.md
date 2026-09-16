# Compatibility

What has actually been tested, not what should work.

## NiFi

| NiFi | Status | How verified |
|---|---|---|
| 2.12.0 | supported, primary | integration tests, Docker demo, unit tests; NAR loads and enables in `apache/nifi:2.12.0` |
| 2.2.0 – 2.11.0 | supported | [compatibility workflow](../.github/workflows/compatibility.yml), weekly and on every release tag: the code is compiled and unit-tested against each version's API, and the NAR is loaded into each `apache/nifi:<version>` image where the service is created and enabled through the REST API |
| 2.0.0 – 2.1.0 | not supported | the `DatabaseDialectService` API first shipped in 2.2.0 |
| 1.x | not supported | no `DatabaseDialectService` extension point |

One NAR serves every supported version. The NAR is built against the 2.12.0 API and declares
`nifi-standard-services-api-nar:2.12.0` as its parent; on an older NiFi the framework picks the
parent NAR version that is installed and logs a `WARN` from `NarClassLoaders` ("unable to locate
exact NAR dependency ... Only found one possible match ... Continuing") — that warning is expected
and harmless. Building with `-Dnifi.version=<your version>` produces a NAR without the warning.

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

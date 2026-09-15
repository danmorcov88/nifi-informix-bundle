# nifi-informix-bundle

IBM Informix support for Apache NiFi 2.x database processors.

Apache NiFi generates SQL through a `DatabaseDialectService`. Dialects exist for Generic, Oracle,
MSSQL, MySQL, PostgreSQL and Phoenix — but not for IBM Informix. Informix users are forced onto the
generic dialect, which produces SQL that Informix rejects for paging (`LIMIT ... OFFSET`) and has no
upsert at all.

This bundle provides `InformixDatabaseDialectService`, a controller service that plugs into the
standard processors:

- `QueryDatabaseTable` / `QueryDatabaseTableRecord`
- `GenerateTableFetch`
- `PutDatabaseRecord`
- `UpdateDatabaseTable`

No new processors. No bundled JDBC driver. Just correct Informix SQL.

## Status

**Early development.** The service loads in NiFi 2.12.0 and generates Informix `SELECT` statements
(`SKIP`/`FIRST` paging), `CREATE TABLE` / `ALTER TABLE` with Informix column types, and
`UPSERT` / `INSERT_IGNORE` as `MERGE`. Every statement type is exercised by integration tests
against real Informix 14.10 and 15.0.1 servers. The Docker demo is next — see [CHANGELOG.md](CHANGELOG.md).

| Capability | Status |
|---|---|
| Service loads and enables in NiFi 2.12.0 (verified on `apache/nifi:2.12.0`) | done |
| `SELECT` with `SKIP` / `FIRST` paging | done — verified on Informix 14.10 and 15.0.1 |
| Configurable identifier quoting (`DELIMIDENT`) | done |
| `UPSERT` / `INSERT_IGNORE` via `MERGE` | done — verified on Informix 14.10 and 15.0.1 |
| `CREATE TABLE` / `ALTER TABLE` with Informix types | done — verified on Informix 14.10 and 15.0.1 |
| Integration tests on a real Informix (Testcontainers) | done — Informix 14.10 and 15.0.1, nightly in CI |
| Docker demo | planned |

## Requirements

- Apache NiFi 2.12.0 (other 2.x lines untested)
- Java 21
- IBM Informix 12.10 or 14.10+
- IBM Informix JDBC driver (`com.ibm.informix:jdbc`), supplied by you — it is not bundled

## Installation

1. Build the NAR:
   ```
   mvn clean install
   ```
2. Copy `nifi-informix-dialect-nar/target/nifi-informix-dialect-nar-<version>.nar` into NiFi's
   NAR auto-load directory — NiFi picks it up within seconds, no restart needed:
   - standard distribution: `<NIFI_HOME>/extensions/`
   - `apache/nifi` Docker image: `/opt/nifi/nifi-current/nar_extensions/`

   (The directory is `nifi.nar.library.autoload.directory` in `nifi.properties`.)
3. In NiFi, create a `DBCPConnectionPool` pointing at your Informix instance, with the IBM JDBC
   driver JAR on its *Database Driver Location(s)*.
4. Create an `InformixDatabaseDialectService` controller service and enable it.
   Leave **Quote Identifiers** at `false` unless your connection has `DELIMIDENT=Y`, and leave
   **Upsert String Length** at `2048` on Informix 14.10 (see [docs/limitations.md](docs/limitations.md)).
5. On the processor (`QueryDatabaseTable`, `GenerateTableFetch`, `PutDatabaseRecord`,
   `UpdateDatabaseTable`), set **Database Type** to `Database Dialect Service` and select the
   Informix service in **Database Dialect Service**.

## Compatibility

See [docs/compatibility.md](docs/compatibility.md) for the NiFi × Informix × driver matrix, with
what was actually tested.

## Limitations

Documented openly in [docs/limitations.md](docs/limitations.md). Read it before relying on this
bundle in production.

## Building

```
mvn clean verify
```

Unit tests run without a database. Integration tests start a real Informix with Testcontainers
(Docker required; the IBM developer image is about 700 MB and its license is accepted for you by
setting `LICENSE=accept` on the container):

```
mvn verify -Pintegration-tests
mvn verify -Pintegration-tests -Dinformix.image=icr.io/informix/informix-developer-database:14.10.FC9W1DE
```

CI runs them nightly and on demand against Informix 14.10 and 15.0.1.

## License

Apache License 2.0 — see [LICENSE](LICENSE).

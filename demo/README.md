# Demo: NiFi 2.12.0 + Informix, end to end

One command starts an Informix developer edition, seeds a database, starts NiFi with the Informix
dialect NAR, imports a flow and shows rows being copied from one table to another — incrementally,
with Informix-native SQL on both ends.

```
cd demo
./run.sh
```

Takes about two minutes on first run (Docker pulls `apache/nifi:2.12.0` and the Informix image,
roughly 2 GB together). Requirements: Docker with Compose v2, Maven, Java 21, `curl`. On Windows use
Git Bash.

## What it does

```
Informix table  orders  ──▶  QueryDatabaseTableRecord  ──▶  PutDatabaseRecord (UPSERT)  ──▶  Informix table  orders_copy
                              Database Type: Database       Database Type: Database
                              Dialect Service (Informix)    Dialect Service (Informix)
                              Maximum-value column: id      → MERGE INTO orders_copy ...
```

1. Builds the NAR if `nifi-informix-dialect-nar/target/` is empty.
2. Downloads the IBM Informix JDBC driver from Maven Central into `demo/drivers/` (git-ignored; the
   driver is IBM-licensed and never packaged with this project).
3. `docker compose up -d`: Informix initialises and runs [`informix/seed.sql`](informix/seed.sql)
   (database `demo`, tables `orders` and `orders_copy`, five rows); NiFi starts with the NAR
   hot-loaded from `nar_extensions/` and the driver mounted at `/opt/nifi/nifi-current/drivers/`.
4. Imports [`nifi/informix-demo-flow.json`](nifi/informix-demo-flow.json) through the REST API, sets
   the database password on the connection pool (sensitive values are never part of a flow
   definition), enables the controller services and starts the process group.
5. Prints `orders_copy`, inserts two more rows into `orders`, waits, and prints it again.

Then open <https://localhost:8443/nifi> (`admin` / `adminadminadmin`, self-signed certificate) to
look at the flow, or connect any SQL client to
`jdbc:informix-sqli://localhost:9088/demo:INFORMIXSERVER=informix` (`informix` / `in4mix`) and
insert rows into `orders` yourself.

## What to look at

- The `Read new orders (Informix)` processor's *Database Type* is `Database Dialect Service` and
  its *Database Dialect Service* is the `Informix Dialect` controller service. The generated query
  is `SELECT * FROM orders WHERE id > <last seen id>` — plain, and paging would be `SKIP`/`FIRST`.
- The `Upsert into orders_copy (Informix MERGE)` processor has *Statement Type* `UPSERT`. With the
  generic dialect this combination is refused; with the Informix dialect it becomes a `MERGE`.
  To see the upsert at work: stop `Read new orders (Informix)`, right-click it → *View state* →
  *Clear state*, start it again. Every row is read once more and `MERGE` updates the existing rows
  in `orders_copy` in place — no duplicate-key errors, still seven rows.
- Bulletins: none. If something is wrong, the processor shows a red indicator in the UI and
  `docker compose logs nifi` has the stack trace.

## Stop

```
docker compose down -v
```

Removes both containers and the Informix data volume. `demo/drivers/` keeps the downloaded driver.

## Variations

- Another Informix version: `INFORMIX_VERSION=14.10.FC9W1DE ./run.sh`
- Another NAR version: `NAR_VERSION=0.2.0 ./run.sh`
- Only the containers, no flow import: `docker compose up -d` (build the NAR and download the driver
  first, see steps 1–2).

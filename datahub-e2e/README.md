# datahub-e2e

End-to-end tests and the ingest benchmark, driven through `datahub-java-sdk` against a **running**
platform. Nothing here runs in a normal build: `test` excludes both tags, and a machine with no
stack configured skips rather than fails.

| Task | What it runs |
|---|---|
| `./gradlew :datahub-e2e:integrationTest` | correctness: JSON and binary ingest must store identical rows, every value type round-trips, an unknown series is refused |
| `./gradlew :datahub-e2e:benchmark` | JSON against binary at 100 million points, with wire bytes, latency and memory |
| `./gradlew :datahub-e2e:flowTest` | flows: dozens of scheduled flows ticking at once, two tenants kept apart, the flow service killed with runs in hand (see [Flows](#flows)) |

## What it needs running

The compose stack plus the api and the stateless consumer:

```bash
podman compose -f docker-compose.yml up -d
./scripts/vault-seed.sh                 # once, after the stack is up
```

Then the two services. **Run them from their jars, not `bootRun`**: the Spring Boot Gradle plugin
launches with `-XX:TieredStopAtLevel=1`, which disables the C2 compiler, and every number the
benchmark prints would be measuring that instead of the code.

```bash
./gradlew :datahub-api:bootJar :datahub-stateless-consumer:bootJar

java -Xms4g -Xmx4g -XX:+UseG1GC -Dspring.profiles.active=dev,local \
  -jar datahub-api/build/libs/datahub-api-*-SNAPSHOT.jar &

java -Xms4g -Xmx4g -XX:+UseG1GC -Dspring.profiles.active=dev,local \
  -jar datahub-stateless-consumer/build/libs/datahub-stateless-consumer-*-SNAPSHOT.jar &
```

## Environment

| Variable | Required | Default |
|---|---|---|
| `DATAHUB_BASE_URL` | yes | |
| `DATAHUB_TOKEN_URI` | yes | |
| `DATAHUB_CLIENT_ID` | yes | |
| `DATAHUB_CLIENT_SECRET` | yes | |
| `DATAHUB_SCOPE` | no | `openid organization:*` |
| `CLICKHOUSE_URL` | no | `http://localhost:18123` |
| `CLICKHOUSE_DB` | no | `foo` |
| `CLICKHOUSE_USER` / `CLICKHOUSE_PASSWORD` | no | `foobar` / `changeme` |
| `DATAHUB_BENCH_POINTS` | no | `100000000` |
| `DATAHUB_BENCH_SERIES` | no | `100` |
| `DATAHUB_BENCH_CHUNK` | no | `1000000` |
| `DATAHUB_BENCH_KEEP` | no | unset; set to keep the benchmark's series |

Against the dev compose stack:

```bash
export DATAHUB_BASE_URL=http://localhost:8081
export DATAHUB_TOKEN_URI=http://localhost:8090/realms/datahub/protocol/openid-connect/token
export DATAHUB_CLIENT_ID=datahub-service-foo
export DATAHUB_CLIENT_SECRET=changeme-foo
```

Client credentials rather than a pasted token, because a hundred million points take longer than a
token lives and the SDK refreshes these on its own.

## Flows

`flowTest` runs `FlowsE2eIT`: each flow reads a series over the last minute and writes it to a
second one, so a tick shows in the data as well as in the run. Three scenarios, in order, and
each takes real minutes because the ticks are real minutes:

1. **Scale** — `DATAHUB_E2E_FLOWS` (default 25) flows on a per-minute schedule; every flow must
   succeed on its first two ticks, exactly once per tick, with the output series filled and the
   run's records readable. Prints the tick-to-start lag.
2. **Tenant isolation** — needs a second organization's service client in
   `DATAHUB_OTHER_CLIENT_ID` / `DATAHUB_OTHER_CLIENT_SECRET` (`datahub-service-bar` /
   `changeme-bar` in the dev realm); skipped without it. Tenant one's flow and run are invisible
   to tenant two by every route, and tenant two may reuse the name.
3. **Crash** — the flow service is killed (SIGKILL) while runs are in hand and kept down across a
   whole tick, then restarted. Every tick must end up run exactly once, the runs that were in
   hand on a second attempt. Only when the test started the service itself (below).

The flow service is either one already running, in `DATAHUB_FLOW_URL`, or started by the tests
from its boot jar (`./gradlew :datahub-flow:bootJar`; elsewhere, `DATAHUB_FLOW_JAR`) on a free
port with the profile in `DATAHUB_FLOW_PROFILES` (default `dev`), its lease cut to 6 s and its
loops to 2–3 s so a crash is noticed in seconds. The crash test needs the jar; against an
external service it is skipped.

The service clients only hold dataset grants out of the box, so before anything else the tests
put each one's service account into `/flows/read`, `/flows/write` and `/settings/flow/write` of
its organization, through the Keycloak admin API at the token URI's host with
`KEYCLOAK_ADMIN_USER` / `KEYCLOAK_ADMIN_PASSWORD` (default `admin` / `admin`), or
`KEYCLOAK_ADMIN_URL` when the admin API lives elsewhere. Without admin access they skip and say
which groups to grant by hand. Each tenant's runs act as that tenant's own client, registered
under the name `e2e` in the tenant's flow settings and removed again afterwards.

Against the dev stack, on top of the variables above:

```bash
export DATAHUB_OTHER_CLIENT_ID=datahub-service-bar
export DATAHUB_OTHER_CLIENT_SECRET=changeme-bar
./gradlew :datahub-flow:bootJar :datahub-e2e:flowTest
```

The deterministic counterparts, which need only a container runtime, are in
`datahub-flow` (`./gradlew :datahub-flow:integrationTest --tests '*RunCoordinatorScaleIT*'`):
two instances sharing 200 scheduled flows, one instance sweeping ten tenants through the real
routing datasource, a three-hour outage caught up on, and instances that die or stop with runs
in hand.

## Two product limits have to be off for the benchmark

Both would stop a hundred-million-point run long before the end, and neither is what the benchmark
measures. Pass them to the **api**:

```
-Ddatahub.limits.quota.enabled=false     # daily allowance: 10M points, 1 GiB of body
-Ddatahub.limits.rate.enabled=false      # 600 writes per minute per user
```

Leave them on for `integrationTest`, which is small enough not to notice them.

## Things that cost time the first time

- **Restarting the Keycloak container drops the org-group bootstrap.** It runs `start-dev` on an
  in-memory database, so a restart re-imports the realm from JSON and loses the protocol mappers
  and group memberships that `deploy/keycloak/bootstrap-org-groups.sh` adds. The symptom is every
  call failing `401` with *"Malformed organization claim: expected an object keyed by alias but got
  a list"*. Re-run the script after any restart; the `keycloak-bootstrap` service in
  `docker-compose.apps.yml` does it for you when you run the apps in compose.
- **ClickHouse and Neo4j ports are not configurable.** The platform builds
  `http://<host>:8123` and `bolt://<host>:7687` from the tenant's Vault entry, which carries a host
  and no port. If something already owns those ports, publish the container on another loopback
  address (`127.0.0.2:7687:7687`) and put that address in Vault, rather than remapping the port.
- **Tenant databases carry Flyway history.** A tenant migrated by a different branch fails
  validation on boot and its schema stays behind; recreate that one database if you see
  *"Migrations have failed validation"*.

## How the numbers are produced

- **Wire bytes** are counted by a TCP relay the SDK is pointed at, so they are the real bytes,
  headers included, not an estimate from the payload. It relays plaintext only.
- **Latency** is per ingest call, measured client-side, reported as mean and percentiles.
- **Settle time** is measured separately: ingest ends when the api has accepted everything, and
  settle is how long Pulsar and the consumer then took to make the rows readable in ClickHouse.
- **Memory** is the client's heap growth, plus the api's and the consumer's peak resident set
  sampled from `/proc`. Resident rather than heap for the servers, because the question is whether
  request bodies inflate the footprint, and that includes what lives off-heap.
- Both paths write to their own series, so the two runs differ only in transport, and the
  generated signal is a slow sine rather than noise, which is what the column codecs are tuned for.

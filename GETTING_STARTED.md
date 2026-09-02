# Getting Started (Local Development)

This walks you from a clean checkout to a running DataHub Platform on your
machine, backed by the local Docker stack in [`docker-compose.yml`](docker-compose.yml).

> **Local dev only.** The stack runs with no TLS/mTLS, dev-mode Vault, and weak
> default passwords (`changeme`). Do not expose it or reuse any of it in
> production. See [README.md](README.md) for the production deployment model.

For the bigger picture of how the modules fit together, read
[ARCHITECTURE.md](ARCHITECTURE.md) first.

## Prerequisites

- **Docker** (+ the `docker compose` plugin) **or Podman** (`podman compose`).
- **JDK 25** — needed **only** for the host/Gradle workflow (steps 4–6). The
  all-in-containers path (`./scripts/up.sh`) builds the jars **inside the image**, so
  it needs no host JDK.
- **HashiCorp `vault` CLI** on your `PATH` — needed **only** for the host/Gradle
  workflow (to seed Vault with `localhost` hosts). The containerized path seeds itself.

You do **not** need a separately installed Gradle; use the wrapper (`./gradlew`).

## 1. Start the backing services

```bash
docker compose -f docker-compose.yml up -d
```

> **The `-f` matters for this workflow.** A bare `docker compose up -d` also loads
> `docker-compose.override.yml`, which starts the four apps in containers *and* the seeded
> 3W demo — so ports 8080/8081 would already be taken when you get to `./gradlew bootRun`
> in step 6. Naming the base file explicitly disables the override and gives you backing
> services only. If you want the all-in-containers stack instead, skip to
> [Run everything in containers](#run-everything-in-containers-optional).

This brings up Postgres 18, ClickHouse 26.5, Neo4j 5.26 (APOC baked in), Valkey 9.1,
Kvrocks 2.14, Pulsar 4.0.11 (plaintext standalone), persistent auto-unsealed Vault, and
Keycloak 26.7. Give Pulsar and Keycloak ~30s to finish booting. (Neo4j is built once from
`deploy/neo4j/Dockerfile`; the rest are pulled.)

On the first start (an empty data volume), Postgres and ClickHouse each create a
**`foo`** and **`bar`** database from the init scripts in `deploy/postgres/init/` and
`deploy/clickhouse/init/`, one per demo tenant. ClickHouse additionally creates the
`events` + `datapoints_*` tables in each database (applying the platform's
`datahub-api/.../db/clickhouse.sql`, the single source of truth, per tenant); the
Postgres tables come from Flyway later (step 5). Postgres also runs with
`POSTGRES_HOST_AUTH_METHOD=trust` because the app's per-tenant datasource connects
with an empty password (local dev only). Those init scripts only run when the volume
is fresh; if you change them, reset with `docker compose down -v` (or `podman compose
down -v`) and bring the stack back up.

> **Podman:** `podman compose -f docker-compose.yml up -d` works the same way (`podman compose` delegates to
> the external `docker-compose` provider). The bind mounts carry the `:z` SELinux label
> so they work on relabeling hosts; it's a no-op elsewhere.

Check everything is healthy:

```bash
docker compose ps
```

| Service    | Host port(s)     | Default creds                |
|------------|------------------|------------------------------|
| Postgres   | 5433             | `foobar` / `changeme`       |
| ClickHouse | 18123, 19000     | `foobar` / `changeme`       |
| Neo4j      | 7474, 7687       | `neo4j` / `changeme123`      |
| Valkey     | 6379             | password `changeme`          |
| Kvrocks    | 6666             | —                            |
| Pulsar     | 6650, 18080      | none (plaintext)             |
| Vault      | 8200             | auto-unsealed (see step 2)   |
| Keycloak   | 8090             | `admin` / `admin`            |

> ClickHouse (`18123`/`19000`) and the Pulsar admin (`18080`) are remapped off their
> native ports (`8123`/`9000`, `8080`) so they don't collide with a host-native
> ClickHouse or the console on `8080`. In-network the services still listen on their
> standard ports, and the apps reach them by service name — so the remap is transparent
> to the containerized workflow.

## 2. Vault (auto-initialised + auto-unsealed) and seeding

Vault runs in **persistent (file-storage) mode**, not dev-mode — a real secret store whose
contents survive restarts, so it's where you (and your clients) keep secrets. It comes up
sealed, but the **`vault-init` one-shot auto-initialises and unseals it on every `up`** —
no manual step. The unseal key + root token are saved on the `vaultinit` volume; grab the
root token (for the Vault UI at http://localhost:8200, or to manage secrets) from its log:

```bash
docker compose logs vault-init        # prints the root token on first init
```

**Seeding** writes the global secrets and the per-tenant connection registry for **two
tenants, `foo` and `bar`** (each its own Postgres + ClickHouse database; Neo4j shared) and
pins the AppRole `role-id` / `secret-id`. In the all-in-one container run (see "Run everything
in containers") the `vault-seed` one-shot does this automatically. For the host/Gradle
workflow (steps 4–6), seed it yourself with localhost hosts:

```bash
export VAULT_TOKEN=$(docker compose logs vault-init 2>&1 | grep -oE 'hvs\.[A-Za-z0-9]+' | tail -1)
./scripts/vault-seed.sh               # writes localhost hosts; prints role-id / secret-id
```

Copy the printed **role-id** / **secret-id** for step 4. Re-running the seed is safe. After a
restart, the next `up` auto-unseals again and the seeded secrets are still there.

> **Hardening Vault for production.** The unseal key is stored next to the data (on the
> `vaultinit` volume), so "sealed at rest" protection is limited — fine for a local
> install. For production, use **auto-unseal backed by a KMS** (AWS/GCP/Azure) or a Transit
> Vault and keep the key off the box: that preserves zero-touch startup *and* real at-rest
> protection. Manual Shamir unseal (keys held off-host) is the other option, at the cost of
> a human step on every boot.

## 3. Verify the stack

`docker-compose.yml` only runs the **backing services**, not the DataHub apps
themselves (those you run with Gradle in steps 4–6). After step 1 (up) and step 2
(seed), confirm the backing stack came up correctly. Substitute `podman compose` for
`docker compose` if you use Podman.

```bash
docker compose ps          # all 8 services should be Up / healthy
```

**Postgres** — both tenant databases exist and the passwordless (`trust`) login the
apps use works (host port 5433):

```bash
docker compose exec -T postgres \
  psql -U foobar -d datahub -tAc \
  "SELECT datname FROM pg_database WHERE datname IN ('foo','bar') ORDER BY 1;"
# expect:  foo  then  bar
```

**ClickHouse** — both tenant databases exist, each with its time-series + events tables:

```bash
docker compose exec -T clickhouse \
  clickhouse-client -u foobar --password changeme -q "SHOW DATABASES" | grep -E '^(foo|bar)$'

# Each tenant DB should hold events + one datapoints_* table per value type:
# datapoints_bigint, datapoints_float, datapoints_float32, datapoints_numeric,
# datapoints_decimal32, datapoints_mixed, datapoints_text.
docker compose exec -T clickhouse \
  clickhouse-client -u foobar --password changeme -q "SHOW TABLES FROM foo"
```

**Vault** — both tenants are registered in the per-tenant registry (`tenant-resources`).
Vault is persistent, so read the generated root token from the init log first:

```bash
VT=$(docker compose logs vault-init 2>&1 | grep -oE 'hvs\.[A-Za-z0-9]+' | tail -1)
docker compose exec -T -e VAULT_TOKEN="$VT" vault \
  vault kv get -format=json intellistream-datahub/tenant-resources \
  | python3 -c 'import sys,json; d=json.load(sys.stdin)["data"]["data"]; [print(k, d[k]["postgresql"]["uri"]) for k in sorted(d)]'
# expect:  bar jdbc:postgresql://localhost:5433/bar
#          foo jdbc:postgresql://localhost:5433/foo
```

**Keycloak** — each user's token carries its own tenant's `organization` claim:

```bash
for u in foo bar; do
  curl -s localhost:8090/realms/datahub/protocol/openid-connect/token \
    -d grant_type=password -d client_id=datahub-client -d client_secret=changeme \
    -d username=$u -d password=$u \
  | python3 -c 'import sys,json,base64; t=json.load(sys.stdin)["access_token"]; p=t.split(".")[1]; p+="="*(-len(p)%4); print(json.loads(base64.urlsafe_b64decode(p))["organization"])'
done
# expect:  {'foo': {'id': '11111111-...'}}  then  {'bar': {'id': '22222222-...'}}
```

When you are done, tear the stack down:

```bash
docker compose down       # stop and remove containers + network (volumes KEPT:
                          # Vault stays initialised; next 'up' auto-unseals it)
docker compose down -v    # also wipe volumes — next 'up' re-creates the foo/bar DBs and
                          # re-initialises + re-seeds Vault from scratch (still automatic)
```

## 4. Point the apps at the local stack

The apps authenticate to Vault with `vault.address` / `vault.role-id` /
`vault.secret-id` (read by `VaultConfigurationLoader`). The local Vault is plaintext;
against one that requires a client certificate, add `vault.keystore` /
`vault.keystore-password` (a PKCS12 with the client certificate) and, for a private CA,
`vault.truststore`. Every `vault.*` key is also an environment variable (`VAULT_KEYSTORE`
and so on). The Vault-derived
Pulsar URLs assume TLS ports (`pulsar+ssl://…:6651`), so for the plaintext local
broker you also override the Pulsar service URLs and disable TLS.

Create `application-local.yml` in **each** service you intend to run
(`datahub-api`, `datahub-stateless-consumer`,
`datahub-console`) under `src/main/resources/`. These files are gitignored
because they hold your role-id/secret-id.

```yaml
# datahub-<service>/src/main/resources/application-local.yml
vault:
  address: http://localhost:8200
  role-id: <paste role-id from vault-seed.sh>
  secret-id: <paste secret-id from vault-seed.sh>

# Plaintext Pulsar overrides (Vault defaults assume TLS).
# Vault props are registered with addLast (lowest precedence), so these win.
pulsar:
  service:
    url: pulsar://localhost:6650
    httpUrl: http://localhost:18080   # admin port is remapped off 8080 (console uses 8080)

datahub:
  pulsar:
    tls:
      enabled: false   # honored by PulsarConfig — skips mTLS + OAuth2
```

Then run each service with both the `dev` and `local` profiles active, e.g.:

```bash
./gradlew :datahub-api:bootRun --args='--spring.profiles.active=dev,local'
```

> The console uses `application-local.properties` (it's a `.properties` module)
> with the same `vault.*` keys.

## 5. Database migrations run automatically

There's no separate migration step. Spring Boot's built-in Flyway is disabled
(`spring.flyway.enabled=false`), but the API's `TenantFlywayMigrator` applies the migrations in
`datahub-api/src/main/resources/db/migration` against **every** tenant database when the API boots
(step 6), and again for any tenant added later. So each tenant's schema is created and kept current
just by starting the API.

If you ever need to run Flyway by hand against a single database, the `flyway` Gradle extension in
`datahub-api/build.gradle` is wired to one hardcoded URL/user — edit it to point at the database you
want, then:

```bash
./gradlew :datahub-infra:flywayInfo      # see pending migrations for that one database
./gradlew :datahub-infra:flywayMigrate   # apply them
```

## 6. Run the services

Individually:

```bash
./gradlew :datahub-api:bootRun                 --args='--spring.profiles.active=dev,local'   # API,  :8081
./gradlew :datahub-stateless-consumer:bootRun  --args='--spring.profiles.active=dev,local'   # datapoint/event consumer
./gradlew :datahub-console:bootRun             --args='--spring.profiles.active=dev,local'    # console UI
```

Or all four at once (note the profile is set per-process, so prefer the
individual commands while you're getting `application-local.yml` right):

```bash
./gradlew startBootStack
```

Prefer running from IntelliJ's own Spring Boot run configurations? See
[intellij-idea.md](intellij-idea.md) (it covers the working-directory setting the
console needs).

Once up:

- API: http://localhost:8081 — Swagger UI at http://localhost:8081/swagger-ui.html
- Console: http://localhost:8080 (Spring Boot's default port; Pulsar's admin port
  was remapped to 18080 so it doesn't collide)
- Keycloak admin: http://localhost:8090 (`admin` / `admin`)

## Run everything in containers (optional)

Steps 4–6 run the apps on the host with Gradle. Alternatively, run all six apps in
containers too, via the `docker-compose.apps.yml` overlay (kept as a separate file you
merge in — rather than a compose `profile` — for broad docker/podman compatibility).
This replaces steps 3–6.

```bash
./scripts/up.sh --build     # builds the boot jars inside the image (no host JDK) + starts everything
```

`scripts/up.sh` is the recommended launcher: it auto-detects this host's IP and uses it as
the Keycloak issuer, so **browser login works with no `/etc/hosts` edit** (the browser and the
containers both reach Keycloak at that IP). It picks `docker compose` or `podman compose`
automatically. Override the address with `KC_ADDR=<ip-or-host> ./scripts/up.sh`.

### The 3W demo comes up with it

By default `up.sh` also merges `docker-compose.demo.yml`, which seeds the Petrobras 3W well
fleet into the `foo` tenant and then keeps appending live datapoints — so the console has
something to show instead of empty pages. It adds two containers: `demo-seed` (runs once and
exits) and `demo-feed` (stays up). Pass `--no-demo` for an empty stack.

The payloads are pre-generated and committed under `deploy/demo/data`, so seeding downloads
nothing and takes seconds; on a restart the seed sees the wells already exist and exits
immediately. To change the fleet or the amount of history, regenerate them — see
`deploy/demo/generate/README.md`.

> **Launch it through `up.sh`, not compose directly.** The demo mints its own tokens, and
> datahub-api only accepts the issuer `vault-seed` wrote — which is `${KC_ADDR}`. A bare
> `podman compose ... up` leaves `KC_ADDR` unset, so the demo falls back to `keycloak:8090`
> and every one of its calls 401s. Export `KC_ADDR` yourself if you must call compose
> directly.

<details><summary>Equivalent manual commands (uses the <code>keycloak</code> name → needs the hosts alias below)</summary>

```bash
podman compose -f docker-compose.yml -f docker-compose.apps.yml build
podman compose -f docker-compose.yml -f docker-compose.apps.yml up -d
# docker users: same commands with `docker compose`
```
</details>

This brings up the eight backing services plus four one-shots and the six apps — fully
automatic, no manual Vault step:

- **vault-init** auto-initialises + unseals Vault and saves the unseal key + root token to
  the `vaultinit` volume (so restarts re-unseal with no human). See `scripts/vault-init.sh`.
- **vault-seed** (after vault-init) seeds Vault using in-network (service-name) hosts and pins
  fixed dev AppRole credentials (`datahub-local-roleid` / `datahub-local-secretid`) that the
  app containers authenticate with, reading the root token from the `vaultinit` volume — so
  there is nothing to export and no role-id/secret-id to copy.
- **pulsar-init** creates the `datahub-internal` / `datahub-public` tenants and their
  namespaces, plus the per-customer `foo` / `bar` tenants the subscription provisioner
  expects (the standalone broker starts empty; see `scripts/pulsar-init.sh`).
- Each app waits (via its image entrypoint) for the seed, Postgres, and the Pulsar
  namespaces before starting, so ordering is correct without relying on
  `depends_on`. Each image compiles the boot jars in a JDK build stage, so no host JDK or
  `./gradlew` step is needed (`deploy/app/Dockerfile`).

Once up, the API (`localhost:8081`) and console (`localhost:8080`) are served from
containers. Tear down with:

```bash
podman compose -f docker-compose.yml -f docker-compose.apps.yml down -v
```

A few things to know:

- **Stop any host-run apps first.** The containers publish 8081 (API) and 8080
  (console); a Gradle/IDE `bootRun` on those ports will block the container from
  binding them.
- **It re-seeds Vault for the in-network hosts.** To go back to the host/Gradle
  workflow, re-run `./scripts/vault-seed.sh` (which writes `localhost` hosts) and
  recreate `application-local.yml`.
- **Browser login + the Keycloak issuer.** Keycloak listens on `8090` (published `8090:8090`).
  The browser and the in-network apps must agree on one Keycloak URL:
  - **With `scripts/up.sh` (recommended): nothing to do.** It sets the issuer to this host's
    IP (`http://<ip>:8090/realms/datahub`), which the browser (it's the host) and the
    containers both reach. Just open http://localhost:8080 and log in as `foo`/`foo`.
  - **With the manual `compose` commands**, the issuer is `http://keycloak:8090/...`, so map
    the name to localhost once (otherwise the login redirect shows "server not found"):
    ```bash
    echo "127.0.0.1 keycloak" | sudo tee -a /etc/hosts
    ```
  (The host/Gradle workflow in steps 4–6 needs neither — there everything is `localhost:8090`.)

## Authentication (Keycloak)

`docker-compose.yml` auto-imports [`deploy/keycloak/datahub-realm.json`](deploy/keycloak/datahub-realm.json)
on first boot (`start-dev --import-realm`, which only imports realms that don't
already exist). It provisions:

- a **`datahub`** realm (issuer `http://localhost:8090/realms/datahub`, matching
  the value `vault-seed.sh` writes);
- the **`datahub-client`** confidential client (secret `changeme`) used for the
  console's authorization-code login — the console relays the logged-in user's
  access token straight to datahub-api;
- three realm roles: **`DATAHUB_ACCESS`** (API `@PreAuthorize`), **`DATAHUB_CONSOLE`**
  (required for every console page), and **`DATAHUB_CHAT`** (console AI assistant).
  Dataset access is **not** a realm role: it comes from organization groups, and
  all-datasets access is the `/datasets/*/read` + `/datasets/*/write` wildcard
  groups (without the write grant you can browse but not save
  datasets/resources/timeseries);
- two test users, **`foo`** / **`foo`** and **`bar`** / **`bar`**, one per demo
  tenant, each holding all three roles;
- two client-credentials clients for the SDKs, **`datahub-service-foo`** /
  `changeme-foo` and **`datahub-service-bar`** / `changeme-bar`, one per demo tenant
  (see [Machine-to-machine tokens](#machine-to-machine-tokens) below).

The realm uses the real Keycloak **Organizations** feature, one organization per tenant, with
their ids pinned to `11111111-1111-1111-1111-111111111111` (`foo`) and
`22222222-2222-2222-2222-222222222222` (`bar`) so they match the tenants `vault-seed.sh` writes.
`OrganizationValidator` reads `organization.<alias>.id` into the tenant context.

### The post-import step (automatic)

A realm import cannot do everything, so `deploy/keycloak/bootstrap-org-groups.sh` finishes the job.
**The compose stack runs it for you** — the `keycloak-bootstrap` one-shot service waits for the
realm to import, then applies it (see `docker-compose.apps.yml`). You only run it by hand when you
manage Keycloak yourself, or after surgically recreating just the Keycloak container:

```bash
./deploy/keycloak/bootstrap-org-groups.sh          # idempotent; safe to re-run
```

Two things it covers, both verified against `keycloak:26.7`:

- **The `organization` client scope's protocol mappers.** Declaring `clientScopes` in the import
  replaces the entire built-in set (`roles`, `profile`, `email` and the rest all vanish), so the
  mappers are configured over the Admin API instead. In particular `addOrganizationId` is off by
  default, and without it the claim is a flat array of aliases with no id for the API to resolve a
  tenant from.
- **Organization group membership.** The organizations, their members and the group tree all
  import fine; membership in a group does not (`user.groups` pointing at an organization group path
  fails with a 500, and a `members` array inside a group representation fails to parse).

The script also joins each dev principal to its grant groups: the `/datasets/*` wildcard pair
(all-datasets read + write, replacing the old `DATAHUB_DATASET_ALL` realm role) and the
`data_set_demo` pair, so the per-dataset path is exercised and not just the wildcard.

### Getting a token

`datahub-client` allows direct access grants. **Note the `organization:*` scope**: without a
selector Keycloak emits no `organization` claim at all and the API rejects the token. Swap `foo`
for `bar` to act as the other tenant:

```bash
curl -s http://localhost:8090/realms/datahub/protocol/openid-connect/token \
  -d grant_type=password -d client_id=datahub-client -d client_secret=changeme \
  -d username=foo -d password=foo -d 'scope=openid organization:*' \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["access_token"])'
```

`organization:*` means "every organization I belong to". The dev users each belong to one, so it
resolves cleanly. A user in several organizations gets an ambiguous token, which the API rejects
rather than guessing at; those clients must pin one with `organization:<alias>`.

Dataset grants are **not** in the access token. They live in the group paths returned by the
UserInfo endpoint, which the API reads and caches. See
[datahub-api/KEYCLOAK_ORG_GROUPS.md](datahub-api/KEYCLOAK_ORG_GROUPS.md).

> Re-importing: the realm is only created if it doesn't exist. To pick up edits
> to `datahub-realm.json`, delete the realm in the admin UI (or
> `docker compose down -v` to wipe everything) and bring the stack back up — the
> `keycloak-bootstrap` service re-applies the post-import step automatically.

### Machine-to-machine tokens

The SDKs (`datahub-java-sdk`, the Rust SDK) authenticate with the client-credentials
grant — no user, no browser. The realm ships one such client per demo tenant:

```bash
curl -s http://localhost:8090/realms/datahub/protocol/openid-connect/token \
  -u datahub-service-foo:changeme-foo -d grant_type=client_credentials \
  -d 'scope=organization:*' \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["access_token"])'
```

Point the Java SDK at it with the three env vars it reads (`TOKEN_URI`, `CLIENT_ID`,
`CLIENT_SECRET` — see `DatahubConfig.fromEnv()`):

```bash
export TOKEN_URI=http://localhost:8090/realms/datahub/protocol/openid-connect/token
export CLIENT_ID=datahub-service-foo
export CLIENT_SECRET=changeme-foo
export SCOPE='organization:*'
export BASE_URL=http://localhost:8081
```

`SCOPE` is required **for this realm**, which uses Keycloak Organizations: that claim comes from a
dynamic client scope, so the request has to name it. Without it the token carries no `organization`
claim and every call fails `401 invalid_token`, which looks like a credentials problem but is not.
A deployment that produces the claim with a protocol mapper instead needs no `SCOPE` (see
[KEYCLOAK_ORG_GROUPS.md](datahub-api/KEYCLOAK_ORG_GROUPS.md), option B).

**The tenant is bound to the client, not passed at call time.** `OrganizationValidator` rejects
any token without an `organization` claim, so each client's *service-account user*
(`service-account-<clientId>`) is a **member of that tenant's organization**. One client per
tenant; to add a third, clone the `datahub-service-foo` client block, add its service-account user,
and list that user in the new organization's `members`.

Two failures look similar from the client and have different causes:

| Symptom | Cause |
|---|---|
| `401 invalid_token` | No `organization` claim. Either the token request omitted the `organization:*` scope selector, or the principal is not a **member** of any organization. Membership is what puts the claim in the token. |
| Authenticates, then every list is empty | Member of the organization, but holding no dataset grants and no blanket role. Access is missing, not identity. |

The first looks like a credentials problem and is not; the second looks like a data problem and is
not.

> Adding a tenant this way needs **both** halves. A client with
> `serviceAccountsEnabled` but no matching `service-account-<clientId>` user entry
> mints tokens with no `organization` claim, and every API call fails
> `401 invalid_token` — which looks like a credentials problem but isn't.

Other providers (Entra ID, Auth0) do **not** drop in here: the API validates a single
Keycloak issuer, reads roles from `realm_access.roles`, and needs the nested
`organization` claim. Their own `SCOPE`/`AUDIENCE` requirements apply to *minting* their
token, but that token still has to be exchanged for a Keycloak one before `datahub-api`
will accept it — see [EntraID.md](EntraID.md).

## Vault contract

What each service reads from Vault (KV-v2 mount `intellistream-datahub`). This is the
layout the app uses after the master merge — three secrets, written by
`scripts/vault-seed.sh`:

| Path | Read by | Contents |
|------|---------|----------|
| `tenant-resources` | api, consumers, console | Per-tenant connection registry — one nested JSON object per tenant (`foo`, `bar`) with `org-id`, `postgresql`, `clickhouse`, `neo4j`, `valkey`, `kvrocks`, `file-storage`, `pulsar`, `tenant-config`, and the optional `llm`. The source of truth for all backend connections. |
| `datahub-platform` | api, consumers, console | Flat dotted keys: the global Pulsar broker (`pulsar.host`, OAuth2 client/admin creds, `pulsar.internal-tenant`) and the JWT `keycloak.issuer` (the console reads its issuer from here too). |
| `datahub-console` | console | Flat dotted keys: the OAuth2 login client (`oauth.client-id`/`-secret`/`-provider`/`-scope`/`-redirect-uri`, role JSON-paths), `console.datahub-url`, the Spring Session Valkey store (`http.session.valkey.*`), and the deployment-wide chat defaults (`llm.provider`, `llm.api-key`, `llm.model`, `llm.base-url`, `llm.effort`, `llm.reasoning-effort`, `llm.max-output-tokens`, `llm.turn-timeout`, `llm.instructions`). |

### The per-tenant model

`llm` is an optional block in a tenant's `tenant-resources` entry naming the model that tenant's
agents use. **One per tenant** — every agent it runs bills to the same key, which is what makes
usage attributable to a customer without a reconciliation step.

```json
"acme": {
  "tenant-config": { "files": true, "chat": true },
  "llm": { "provider": "anthropic", "api-key": "sk-ant-...", "model": "claude-opus-5" }
}
```

or, for a tenant running its own model:

```json
"llm": { "provider": "openai-compatible", "base-url": "http://vllm.acme:8000/v1",
         "model": "qwen3-32b", "reasoning-effort": "none", "turn-timeout": "10m" }
```

It says **which model and how to reach it** — nothing about how much to spend on it. Effort and
the output-token roof are per agent, in that tenant's `agent` table, because they are cost dials
an operator wants to turn without touching a secret store.

Every field is optional, and an unset one falls back to the deployment-wide `llm.*` defaults on
the `datahub-console` secret. A tenant with no `llm` block at all uses those defaults entirely,
which is what every tenant did before the block existed.

### Who may change a tenant's settings

Two organization groups gate configuration, alongside the `/datasets/...` groups that gate data:

```
/settings/read     list agents, read the tool catalogue
/settings/write    create, replace and delete agents
```

Flat, organization-scoped, with no wildcard — settings are one thing, not a hierarchy of things.
Write does not imply read, matching how the dataset pair behaves, so a person gets both and an
automation that only pushes configuration gets one. `DATAHUB_ADMIN` implies both.

Fetching one agent by name (`GET /agents/{externalId}`) is deliberately **not** gated: that is how
the console learns what it is about to run, so requiring `/settings/read` for it would mean
granting the group to everyone who uses the assistant. Nothing in a definition is secret — the
model credential is a tenant-level Vault value and never appears in one.

Host fields in `tenant-resources` are bare (no port) — the code appends each store's
port; `postgresql.uri` is a full JDBC URL. Each tenant value is a nested JSON **object**
(not a stringified blob), so `TenantConfigService` can deserialize it into a `Tenant`.

## Known rough edges

This is a **v1 scaffold** — expect to tweak a few things for your machine:

- **Image tags may need adjusting** if a specific patch tag isn't available for
  your platform/arch. `hashicorp/vault:1.18` is a floating minor tag; the rest are
  pinned. Neo4j is built from `deploy/neo4j/Dockerfile` (pinned `neo4j:5.26.26` with
  `apoc-5.26.27-core.jar` baked into `plugins/`, allowlist `apoc.path.*`) — the
  resource/dataset graph view calls `apoc.path.subgraphAll`, so without APOC it 500s.
  Image names are fully qualified (`docker.io/...`) so Podman resolves them without an
  `unqualified-search-registries` entry.
- **Host vs. compose-network hostnames.** `vault-seed.sh` writes `localhost`
  hostnames assuming you run the apps on the host via `./gradlew bootRun`. If you
  instead containerize the apps on the compose network, override the `*_HOST`
  env vars (see the top of `vault-seed.sh`) to use the service names.
- **Tenant registry.** The seed writes two tenants, `foo` and `bar`, whose `org-id`s
  must match the `organization` claim in each user's JWT. They are wired up to agree
  out of the box; if you change them, change `FOO_ID` / `BAR_ID` in `vault-seed.sh`
  **and** the matching organization `id` in `datahub-realm.json` together. Organization ids are
  pinned in the import for exactly this reason (Keycloak would otherwise generate them); note they
  are unique per Keycloak server, not per realm.
- **Per-tenant databases are created once.** The `foo`/`bar` Postgres and ClickHouse
  databases come from the `deploy/*/init` scripts, which only run on a fresh data
  volume. If the tenants change, or a database is missing, reset with
  `docker compose down -v` (or `podman compose down -v`) and bring the stack back up.
- **Neo4j is shared.** Community edition has a single database, so both tenants use
  the same graph. Real per-tenant graph isolation needs Neo4j Enterprise (or one
  Neo4j per tenant).

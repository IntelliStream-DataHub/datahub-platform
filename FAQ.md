# Frequently Asked Questions

Answers to common questions about running, deploying, and understanding the design of
IntelliStream DataHub. For an overview of what the platform *does*, start with the
[README](README.md).

## General

### What is DataHub Platform?

A data platform that pairs operational data (events and time-series) with an ontological
layer describing what that data is about: the assets it belongs to, the functions those assets
perform, and the business context that gives them meaning. See the [README](README.md) and
[intellistream.ai](https://intellistream.ai).

### How mature is it, and what is not built yet?

The ingestion and modelling half is real and running: the ontology and graph, time-series and
event ingest, per-dataset access control, multi-tenancy, the console, the MCP surface for
agents, and the time-series relationship analysis. That is what the platform does today.

**Lineage and data-quality traceability are not built yet.** They are described throughout this
document and in the README because they are the direction and the reason the data model is
shaped the way it is, but nothing in the code records lineage today. Where this document
describes them, it says so.

The platform is pre-1.0 and interfaces still change between versions. It has not been through
a third-party security audit.

### Why did we build it?

Because we believe a measurement should never be just a number, and that getting there
shouldn't require an enterprise procurement process. The core conviction behind DataHub is
that useful answers, and eventually autonomous operations, need *context*: a pressure
reading matters because of the pump it came from, the process that pump serves, and the KPI
that process feeds. Platforms that deliver this kind of contextual model exist, but they
typically arrive with a seven-figure quote, a multi-quarter implementation, and a proprietary
data model you can't leave.

The alternative most engineering-led teams reach for is building it themselves from open
components, and most of them ship the ingestion and storage parts, then leave lineage, data
quality, and the ontology UX as a "phase two" that never comes. Those are the hard parts. We
are, in a real sense, the team that went and did the build-it-yourself project properly, then
open-sourced the result so the category has an open answer: the contextual data model, built to
carry lineage and data quality as first-class citizens rather than bolt them on later, on
standard components you can audit, run air-gapped, and fork if we ever disappear.

### What is DataHub's value proposition?

A single contextual model of your operation, designed from the start around lineage and data
quality, at open-source economics. Concretely, that buys you four things, the last of which is
still ahead of us:

- **One simple model over every silo.** Data arriving from a historian, an ERP, a
  work-permit system, or a monitoring stack is reduced to a few primitives: things become
  resources, signals become time-series, and occurrences (a permit closed, a purchase order
  raised, an alarm fired) become events. You query all of it through one interface, without
  ever learning the source systems' data models. That is where much of the everyday saving
  lives: less training before someone can answer a question, less time hunting for where
  data is and how to read it, and fewer errors from misreading a source schema.
- **Audit-ready reporting** *(planned, not built)*. Every reported figure traced back through
  every transformation to the raw signals it was computed from, with data-quality flags intact,
  so reports become auditable artifacts instead of claims. This is the direction the model is
  built toward. See [how mature is it](#how-mature-is-it-and-what-is-not-built-yet).
- **Faster investigations.** Incidents are explored by following relationships (asset to
  function to KPI, signal to event) instead of a human joining five systems by hand.
- **Cheap onboarding of new data.** A new source is dropped into the ontology once, and
  every downstream model and report inherits its context.

The value of contextualized operational data is not speculative; the category has public
proof, and every figure below links to the vendor's own published account so you can check it
rather than take ours. Palantir's [work with Airbus](https://www.palantir.com/impact/airbus/)
on the Skywise platform reports that better insight and collaboration "accelerated delivery of
A350s by 33%". Cognite's [case study with Aarbakke](https://www.cognite.com/en/customers/aarbakke),
the Norwegian machining company, reports "$6m value from digital programme per year", alongside
a 60% reduction in tool assemblies. That digitalization went well enough that the effort became
its own company: [Ignos](https://ignos.no/en/) was, in its own words, "born on the shop floor"
at Aarbakke and "became a company of its own" in 2021, selling smart-factory solutions to other
manufacturers.

These are other vendors' customers on other vendors' platforms, cited as evidence that the
category delivers rather than as DataHub's results: they are what this class of platform can
deliver when budget and timeline are there. DataHub's proposition is the same
class of capability without the usual cost of entry: running in days rather than quarters,
on standard components, open source, and with no proprietary model to migrate off if you
change your mind.

### What can you actually use DataHub for? Four very different examples

The ontology is not a fixed industrial taxonomy. You model what *your* operation is made of,
and the platform treats it the same way: assets, the functions they serve, the business
context around them, and the events and time-series they emit. Four deployments that share
nothing but the platform:

- **A grid operator.** Assets are generating units, substations, battery storage, and wind
  farms; functions are balancing, delivery, and load management; the business knowledge layer
  holds tariffs, PPAs, and regulated reporting obligations. SCADA telemetry streams in as
  time-series, grid events as events. The team can answer "which assets contributed to
  yesterday's capacity shortfall, and what events were raised against them?" in one query,
  and the hourly emissions figure they submit to the regulator traces back to individual
  generating units with data-quality flags attached.

- **An IT operations team.** Assets are servers, storage arrays, switches, and VMs; functions
  are the services and pipelines they host; deploys, alerts, and incidents are events;
  latency, utilization, and error rates are time-series. When a service degrades, the
  investigation traverses the graph: service, to host, to the array showing elevated error
  rates, to the switch that dropped packets, to the deploy that went out twenty minutes
  earlier. "What's the blast radius of taking down this switch?" is a relationship query,
  not tribal knowledge. (This one isn't hypothetical: we run DataHub on our own
  infrastructure this way.)

- **A municipal water utility.** Assets are treatment plants, pumping stations, and network
  segments; functions are the treatment stages and delivery zones they serve; the business
  knowledge layer holds the regulatory frameworks each site reports under. When an effluent
  reading goes out of spec, the team traverses from the excursion to the contributing
  treatment stages to the upstream events and maintenance history. Compliance submissions
  stop being spreadsheets assembled by hand and become queries against the model, with every
  number carrying its audit trail.

- **A plant that just wants its own data back.** Not every deployment starts with a full
  ontology; sometimes the value is plain data liberation. The data sits in a historian, a
  maintenance system, a work-permit system, and an ERP, and each one guards it behind its
  own complex data model that takes vendor training to query. DataHub liberates that data by
  simplifying it: whatever arrives from a silo becomes one of a few primitives. A work
  permit being opened or closed, a new purchase order, a sensor alarm, a system state
  change, they all become *events*; signals become *time-series*; the things they describe
  become *resources*. You never need to understand the source system's schema to use its
  data, and there is one interface, the same API and console, for querying and reading all
  of it. Lineage, the knowledge graph, and everything above can be layered on later; having
  every silo readable through one simple model is worth doing by itself, and because the
  platform is open source, the liberated data hasn't just moved into a newer cage.

Same platform, same model primitives, no vertical editions. The difference between
deployments is the ontology you load into it, and how much of one you need on day one.

### How does DataHub compare to platforms like Palantir Foundry or Cognite Data Fusion?

Same category, different execution. Foundry, Cognite Data Fusion, and the other enterprise
industrial platforms are the mature answer: a decade of deployments, deep vertical tooling
(3D models, P&ID integration, prebuilt historian connectors), and vendor-backed support
organizations that we, honestly, don't match in breadth. If you have the budget, the
timeline for a multi-quarter implementation, and a preference for a single accountable
vendor, those platforms are a legitimate choice.

DataHub takes the same core model (assets, functions, business knowledge, time-series, and
events, all linked, and lineage on every derived value as the model is completed) and executes
it as an open-source
platform built entirely on standard components: PostgreSQL, Apache Pulsar, ClickHouse, Neo4j.
That changes the deal structurally rather than incrementally: you can read the code before
you adopt it, deploy it in your cloud or fully air-gapped, extend the model yourself instead
of filing vendor tickets, and walk away with your data in open formats at any time. It's
built for engineering-led operations teams who can evaluate a platform on the merits and want
the capabilities of this category without the commitment that usually comes attached.

### What does "git branches, but for data lineage" mean?

> **Describes the intended design, not current behaviour.** Lineage is not implemented yet.
> This answer is here because it is the model the platform is being built toward.

It's the mental model for how DataHub is meant to treat derived data. In git, no commit overwrites
history: every commit points to its parents, and you can trace any line of code back through
every change to its origin. DataHub is meant to treat data the same way. Raw measurements and
events are immutable once ingested, which is true today, and every derived value, whether a
cleaned signal, a filtered window, an hourly aggregate, or a reported KPI, is to record the
transformations and inputs it was computed from, like a commit pointing to its parents.

That would give you the two things git gives you. First, `blame` for data: start from a figure in
a report and walk its ancestry back through every transformation to the raw samples and
events behind it, with data-quality flags (gap-filled, interpolated, missing) visible at each
step. That is what turns a reported number into an auditable artifact. Second, branching:
multiple derivations, say alternative cleanings, different aggregation windows, or an
experimental model alongside the production one, can fan out from the same source data
without touching the source or each other, each carrying its own complete history.

### What's the license?

GNU AGPL-3.0-or-later for the platform. **The client libraries are Apache-2.0**:
`datahub-api-model` and `datahub-java-sdk` are licensed permissively so they can be linked into
your own applications, which is the whole point of a client library. Contributions are welcome
under the DCO sign-off policy described in [CONTRIBUTING.md](CONTRIBUTING.md). Third-party
components keep their own licences, listed in [NOTICE](NOTICE).

### What does the AGPL mean for me in practice?

Not legal advice, and your situation may need a lawyer. The mechanics, though, are narrower than
the licence's reputation suggests:

- **Running it, unmodified, for your own organization.** No obligations beyond the licence
  travelling with the software. Self-hosted, in your cloud, or air-gapped, your data is yours and
  nothing has to be published.
- **Modifying it and letting people use it over a network.** This is the clause that makes the
  AGPL different from the GPL. Section 13 says users interacting with your modified version
  remotely must be offered its source. Those users are often your own staff, so in practice this
  means keeping your fork's source available to them. The console carries a source link in its
  footer for exactly this reason.
- **Calling the REST API from your own application.** Your application is a separate program
  talking to a server over HTTP. It is not a derivative work, and the AGPL places no conditions
  on it, whatever language it is written in.
- **Linking the Java SDK into your application.** Fine, and deliberately so.
  `ai.intellistream:datahub-sdk` and the `datahub-api-model` it depends on are Apache-2.0, not
  AGPL, so linking them carries no copyleft into your program. The AGPL applies to the platform
  you run, not to the code you write against it.

### Why open source?

Partly conviction, partly because it's the honest answer to the biggest objection in this
category: vendor risk. An operational data platform sits under your reporting, your
investigations, and eventually your automation, so adopting one is a decade-scale decision.
Closed platforms answer the "what if the vendor disappears, triples the price, or
deprecates what we depend on" question with a contract. Open source answers it structurally:
you can read the code before you adopt it, audit what it does with your data, run it
air-gapped, and fork it if we ever vanish. The lock-in conversation is over before it
starts, and we think this category deserves at least one answer like that.

It's also how we want to work. The platform is built for engineering-led teams, and those
teams evaluate tools by reading them. Open source means the evaluation, the bug report, and
the extension all happen on the merits, in the open.

As for AGPL specifically: it guarantees that improvements stay open even when the platform is
offered as a hosted service, rather than disappearing into someone's proprietary cloud
offering. It places no restrictions on using DataHub for your own organization's data,
self-hosted, in your cloud, or air-gapped.

### What do I actually need to run?

Four deployable services, `datahub-api` (REST API), `datahub-console` (web UI), and the two
Pulsar consumer (`datahub-stateless-consumer`), plus the
backing stores: PostgreSQL, Apache Pulsar, ClickHouse, Neo4j, Apache Kvrocks (Redis-compatible
store for event key mappings), Valkey/Redis (query-cursor caching and console sessions),
HashiCorp Vault (secrets and tenant configuration), and an OAuth2/OIDC identity provider.

Two supporting modules round out a production deployment: `datahub-pulsar-filter` (a Pulsar
broker plugin, see below) and `datahub-cleanup` (a scheduled housekeeping service that prunes
stale file-storage temp files and sweeps orphaned Pulsar subscriptions).

## Architecture decisions

### Why is the platform split into several services?

Each service has a different scaling profile and failure mode. The API is request/response and
scales with user traffic; the consumers are throughput-bound and scale with ingest volume; the
console is a thin UI layer. Splitting them means you can scale ingest without adding API
instances, restart the UI without dropping ingestion, and keep the consumers free of any web
server at all.

### Why are there two consumers?

They do different jobs with different consistency needs:

- **datahub-stateless-consumer** lands high-volume, immutable data (datapoints and events)
  in ClickHouse using batched inserts, and fans datapoints out to WebSocket subscribers. It
  scales horizontally; Pulsar's subscription types coordinate work distribution across
  instances.
- **datahub-api** applies resource create/update/delete changes to the Neo4j
  knowledge graph. Graph mutations are order-sensitive, so this consumer is kept separate from
  the bulk-ingest path and is typically run with failover rather than fan-out.

### What is `datahub-pulsar-filter` and do I need it?

It's a Pulsar **broker-side `EntryFilter` plugin**, packaged as a NAR archive. It lets one
partitioned fan-out topic serve many logical WebSocket subscriptions by filtering messages on
dispatch, so each subscriber only receives the keys it asked for, without each subscription
needing its own topic. Deploy it to every Pulsar broker's `entryFiltersDirectory` and enable
it in `broker.conf`. You need it if you use live data subscriptions over WebSocket.

### Why Apache Pulsar?

Three Pulsar features are load-bearing for us:

1. **First-class multi-tenancy**: Pulsar tenants and namespaces map directly onto our tenant
   model, with isolation and quotas at the broker level rather than by naming convention.
2. **Subscription types**: `Shared`, `Key_Shared`, and `Failover` subscriptions let each
   consumer pick the right distribution model (bulk ingest scales out; graph mutations stay
   ordered) without external coordination.
3. **Broker-side entry filters**: the plugin API behind `datahub-pulsar-filter`, which makes
   per-subscriber filtering cheap enough to do at the broker instead of in every client.

### Why ClickHouse for time-series?

Time-series telemetry is high-cardinality, append-heavy, and queried in large analytical
sweeps, exactly the columnar workload ClickHouse is built for. PostgreSQL holds the
relational model; ClickHouse holds the measurements.

### Why Neo4j when there's already PostgreSQL?

The ontology is a graph, and the questions users ask of it are traversals: "every asset under
this function that feeds this KPI." Expressing multi-hop traversals over typed relationships
in SQL gets painful quickly; in Cypher it's natural. PostgreSQL remains the system of record
for entities; Neo4j serves relationship traversal.

### Why is the console server-side rendered (Thymeleaf) instead of a SPA?

Deliberate simplicity. Server-side rendering with vanilla JS means no frontend framework
churn, no separate API-token handling in the browser (the console is a regular OAuth2 client
with server-held tokens), fast first paint, and templates that live next to the code that
serves them. The console is an operational UI, not an app platform, so the trade-off favours
longevity and a low contribution barrier.

## Multi-tenancy

### How does multi-tenancy work?

Each tenant gets its own PostgreSQL database, ClickHouse database, Neo4j database, and file
storage root. At request time, the tenant is resolved from the JWT's organization claim and
stored in a `ThreadLocal` (`TenantContext`). Everything downstream (the routing JDBC
datasource, the ClickHouse client, the Neo4j session, file path resolution) reads the tenant
from that context. A servlet filter clears the context at the end of every request so tenant
state never leaks across pooled threads.

The two Redis-compatible stores (Kvrocks and Valkey) also carry per-tenant connection config:
each tenant names its own Kvrocks and Valkey `host`/`port`, so a tenant can run on its own
instance or share one with others, the same way the databases do. They hold only derived or
short-lived data, and as defense in depth their keys are still constructed so one tenant's
entries cannot be reached from another tenant's requests: Kvrocks keys are 128-bit BLAKE3
hashes of the external ID *combined with the tenant ID*, so a key is infeasible to guess and
never collides across tenants; Valkey holds query cursors under random single-use UUID keys
with short expiry.

Tenant connection details live in Vault and are refreshed periodically at runtime, so tenants
can be added without restarting the platform.

### Why database-per-tenant instead of schema-per-tenant?

We considered both and chose database-per-tenant deliberately:

1. **Hard isolation.** Each tenant database has its own credentials. Cross-tenant data access
   requires a credential compromise, not merely an application bug in `search_path` or
   connection-state handling. For a platform holding customers' operational data, that
   difference matters, and it's a much easier answer in security reviews.
2. **Consistency across stores.** ClickHouse and Neo4j don't have a schema concept the way
   PostgreSQL does; their natural isolation unit is the database. Using database-level
   isolation everywhere gives all four stores (including file storage) the same tenant
   boundary, the same provisioning shape, and the same cleanup primitive.
3. **Placement flexibility.** Because each tenant carries its own connection URI, a heavy
   tenant can be moved to dedicated database hardware by changing its configuration entry,
   with no application changes and no re-architecture.
4. **Per-tenant resource limits.** With an external connection pooler, each tenant gets its
   own pool with its own size cap, so one tenant's load can't starve another's connections.
5. **Clean lifecycle.** Tenant offboarding is `DROP DATABASE`, the most airtight cleanup
   primitive PostgreSQL offers. Per-tenant backup and restore are plain `pg_dump`/restore of
   one database.

The honest trade-offs: migrations run once per tenant database, provisioning a tenant means
creating real databases rather than a schema, and production deployments want an external
connection pooler (see below). We think those are the right costs to pay for the isolation
guarantees.

### Why does the application use a non-pooling datasource instead of HikariCP?

The routing datasource (`StatelessRoutingDataSource`) resolves the target database per
request, per tenant. Holding a Hikari pool per tenant inside every application instance
multiplies idle connections by *tenants × instances × services*. Instead, the application
opens plain connections and delegates pooling to an external pooler such as PgBouncer, which
multiplexes all instances' traffic onto a small number of PostgreSQL backends and enforces
per-tenant pool limits in one place.

### Do I need PgBouncer?

For development or a small single-tenant install, no: direct connections work, they're just
not pooled. For production, an external pooler (PgBouncer in transaction mode, or equivalent)
is strongly recommended: PostgreSQL backends are a process each, and without a pooler every
transaction pays connection-setup cost.

### Why is tenant configuration in Vault rather than a database table?

Chicken-and-egg, mostly: the tenant registry decides *which database to connect to*, so it
can't live in one of those databases. It also consists largely of credentials, which belong in
a secret store with access control and audit, not in application tables. The platform reads
the registry at startup and refreshes it periodically.

### How do Flyway migrations work with many tenant databases?

Spring Boot's default Flyway integration is disabled (it would need a single datasource). A
custom migrator runs at startup, iterates every tenant in the registry, and applies the same
migration scripts (`datahub-api/src/main/resources/db/migration/`) to each tenant database.
Tenants that appear in the registry at runtime are migrated when discovered. Flyway's
schema-history lock keeps concurrent instances from racing.

### How do I provision a new tenant?

Tenant provisioning is infrastructure work and is deliberately out of scope for the platform
itself. To add a tenant you create its resources (a PostgreSQL database and role, a
ClickHouse database and user, a Neo4j database, Valkey and Kvrocks endpoints, file-storage
directories, and the corresponding organization in your identity provider), then register
the tenant under the
platform's Vault secret (`intellistream-datahub/tenant-resources`) with its connection details and
organization ID. The platform picks the new tenant up on its next registry refresh and runs
migrations automatically. Automate those steps with whatever tooling fits your environment.

### Can different tenants live on different database servers?

Yes. Each tenant's configuration carries its own connection details, so tenants can be placed
on different PostgreSQL, ClickHouse, Neo4j, Valkey, or Kvrocks servers independently, which is
useful for isolating heavy tenants or meeting data-residency requirements.

## Operations

### How do the services scale?

All services run as multiple instances behind a load balancer. The API is fully stateless —
including its WebSocket endpoints, which don't need sticky routing: the subscription cursor lives
in Pulsar and the live tail is a non-durable consumer, so a reconnect resumes on any instance. The
console externalizes its sessions to Valkey/Redis; the stateless consumer scales by adding
instances. See the
[Deployment & Scaling section of the README](README.md#deployment--scaling) for the details
and load-balancer configuration examples.

### How do I back up a tenant?

Because every store is database-per-tenant, backup is per-database: `pg_dump` the tenant's
PostgreSQL database, `BACKUP DATABASE` in ClickHouse, and your standard Neo4j backup for the
tenant's graph database, plus the tenant's file-storage root. Restore is similarly scoped:
one tenant can be restored without touching the others.

# datahub-pulsar-filter

Broker-side Pulsar `EntryFilter` plugin that lets one partitioned topic serve many logical
subscribers by filtering dispatched entries against a subscription-scoped property.

Pulsar's own page on broker-side filtering has gone from the documentation site. StreamNative's
deep dive, [Everything You Wanted from Broker-Side Filtering (and
More)](https://www.youtube.com/watch?v=nCr0t9yTGiY), covers the mechanism and the reasons to
reach for it.

This plugin is required by the subscription fan-out architecture (see
`datahub-api/SubscriptionService` and `BatchedDatapointsListener`). Without it
loaded on every broker, WebSocket subscribers will receive messages that belong
to other subscriptions.

## How it works

1. `BatchedDatapointsListener` publishes every fan-out message to the shared topic
   `persistent://<public-tenant>/subscriptions/fanout` with `msg.key = <subscription externalId>`.
2. Each logical subscription is a separate Pulsar subscription on that topic,
   named by its externalId. At create time, `SubscriptionService` attaches a
   subscription property `filter.key=<externalId>` via
   `pulsarAdmin.topics().updateSubscriptionProperties(...)`.
3. On dispatch, the broker calls `SubscriptionKeyEntryFilter.filterEntry()` for
   every candidate entry. The filter compares the entry's partition key against
   the subscription's `filter.key` property. Match → `ACCEPT`. Mismatch → `REJECT`.
4. Subscriptions without a `filter.key` property are passed through unchanged
   (opt-in per subscription).

## Build

```bash
./gradlew :datahub-pulsar-filter:nar
```

Output: `build/distributions/datahub-pulsar-filter.nar`.

Layout inside the NAR:

```
META-INF/
├── services/entry_filter.yml              # Pulsar definition (name + class)
└── bundled-dependencies/
    └── datahub-pulsar-filter-<ver>.jar    # classes loaded via the NAR classloader
```

The `pulsarVersion` in `gradle.properties` must match the running broker.

## Deploy

1. **Copy the NAR to every broker** (and any proxies/standalone instances) under
   the directory that `broker.conf` references as `entryFiltersDirectory`
   (default: `./filters` relative to the Pulsar install root).

   ```bash
   cp build/distributions/datahub-pulsar-filter.nar /opt/pulsar/filters/
   ```

2. **Enable the filter in `broker.conf`:**

   ```
   entryFilterNames=datahub-pulsar-filter
   ```

   The value must match the `name:` field in
   `src/main/nar/META-INF/services/entry_filter.yml`. To run multiple filters,
   comma-separate the names — they execute in listed order and REJECT short-circuits.

3. **Restart the broker.** On startup you should see:

   ```
   EntryFilterProvider - Searching for entry filters in /opt/pulsar/filters
   NarUnpacker - Extracting datahub-pulsar-filter.nar ...
   EntryFilterProvider - Loaded entry filter: datahub-pulsar-filter
   ```

   If the broker logs `No entry filter is found for name 'datahub-pulsar-filter'`,
   check that the NAR is in the correct directory, that the `name` in
   `entry_filter.yml` matches `entryFilterNames`, and that the broker has read
   permission on the file.

## Verify

After the broker is up and an app has published through the fanout topic, look
at topic stats:

```bash
pulsar-admin topics stats \
  persistent://datahub-public-dev/subscriptions/fanout \
  | jq '.subscriptions[] | {filterAcceptedMsgs, filterRejectedMsgs}'
```

Every subscription with a `filter.key` property should show a non-zero
`filterRejectedMsgs` as soon as any other subscription receives a message —
that's proof the broker is filtering on this subscription's behalf.

## Filter behaviour matrix

| Subscription has `filter.key` | Message has partition key | Keys equal | Result |
|-------------------------------|---------------------------|------------|--------|
| no                            | any                       | —          | ACCEPT |
| yes                           | no                        | —          | REJECT |
| yes                           | yes                       | yes        | ACCEPT |
| yes                           | yes                       | no         | REJECT |

## Compatibility

- Pulsar 2.11+ (`EntryFilter` API). Tested with 4.0.9.
- **Java bytecode target: 17.** The broker loads this NAR in its own JVM (Java 21
  on the Pulsar 4.0.x images), while the repo's shared toolchain compiles with a
  newer Java. The module pins `options.release = 17` so the broker doesn't reject
  the classes with `UnsupportedClassVersionError` on startup. Keep this at or
  below the broker JVM's version when upgrading.
- `pulsar-broker` artifact is a `compileOnly` dependency; the broker provides
  the implementation at runtime.
- Subscription types: works with `Exclusive`, `Shared`, `Failover`, and
  `Key_Shared`. DataHub uses `Failover` per WebSocket client so reconnects get
  primary selection for free.

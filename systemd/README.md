# Running DataHub under systemd on bare metal

Example units and tuning for the DataHub services on dedicated dual-socket servers (two
NUMA nodes) running AlmaLinux 9 or Debian 12/13. The principle: **one JVM per NUMA
node, 16-20 GB of heap each**, pinned so every heap access is local and two services on
a host never compete for the same cores or memory controller. The nginx edge that fronts
the api and console instances is under [`../nginx/`](../nginx/README.md).

```
systemd/
  datahub@.service                     one template for all six services
  datahub@<name>.service.d/placement.conf   per-instance NUMA node, memory ceiling, extra mounts
  env/common.env                       profile, Vault AppRole, Pulsar, shared env
  env/<name>.env                       JAVA_OPTS per service
  config/<name>/application.yml        Spring Boot / Tomcat tuning (api, console, analysis)
  sysctl.d/90-datahub-app.conf         kernel settings for the app hosts
```

Instance names are the short module names: `api`, `console`, `stateless-consumer`,
`stateful-consumer`, `analysis`, `cleanup`. `systemctl status datahub@api` and so on.

## Host layout

Two services per host, one per socket. With two hosts this gives the two api and two
console instances the load balancer expects; the consumers, analysis and cleanup go on
a third host (or on the same two if the hardware is shared).

| Host | NUMA node 0 | NUMA node 1 |
|---|---|---|
| app-1 | `datahub@api` (20 GB heap) | `datahub@console` (16 GB) |
| app-2 | `datahub@api` (20 GB) | `datahub@console` (16 GB) |
| app-3 | `datahub@stateless-consumer` (20 GB) | `datahub@stateful-consumer` (16 GB) |
| app-4 | `datahub@analysis` (16 GB) | `datahub@cleanup` (4 GB) |

The node for each instance is set in its `placement.conf` (`NUMAMask=0` or `1`), so a
different layout is a one-line change per instance on that host. Check the actual CPU
numbering with `lscpu | grep NUMA`; the units use `CPUAffinity=numa`, which derives the
CPU set from the node, so they need no CPU numbers.

The stateful consumer and cleanup must run as exactly one instance each (order-sensitive
graph writes; single-instance housekeeping); the others scale by adding hosts.

## Install (per host)

```sh
# Java 25 (Temurin via the Adoptium repo, or the distro OpenJDK 25 package)
dnf install temurin-25-jdk          # AlmaLinux   |   apt install temurin-25-jdk   # Debian

# Service user and directories
useradd --system --home-dir /opt/datahub --shell /usr/sbin/nologin datahub
install -d -o root -g root -m 755 /opt/datahub /etc/datahub

# One jar per service, built with ./gradlew bootJar
for m in api console; do                       # whichever this host runs
  install -d /opt/datahub/$m
  install -m 644 datahub-$m/build/libs/datahub-$m-0.0.1-SNAPSHOT.jar /opt/datahub/$m/app.jar
done

# Unit, placement drop-ins, env, Spring config
install -m 644 systemd/datahub@.service /etc/systemd/system/
cp -r systemd/datahub@api.service.d systemd/datahub@console.service.d /etc/systemd/system/
install -m 600 systemd/env/common.env systemd/env/api.env systemd/env/console.env /etc/datahub/
install -d /etc/datahub/api /etc/datahub/console
install -m 644 systemd/config/api/application.yml     /etc/datahub/api/
install -m 644 systemd/config/console/application.yml /etc/datahub/console/
#   -> edit /etc/datahub/common.env (Vault AppRole, Pulsar URLs) and the internal-proxies
#      list in /etc/datahub/api/application.yml (the load balancer addresses)

# Kernel and transparent huge pages
install -m 644 systemd/sysctl.d/90-datahub-app.conf /etc/sysctl.d/ && sysctl --system
echo 'w /sys/kernel/mm/transparent_hugepage/enabled - - - - madvise' > /etc/tmpfiles.d/thp.conf
echo 'w /sys/kernel/mm/transparent_hugepage/defrag  - - - - madvise' >> /etc/tmpfiles.d/thp.conf
systemd-tmpfiles --create /etc/tmpfiles.d/thp.conf

systemctl daemon-reload
systemctl enable --now datahub@api datahub@console
journalctl -fu datahub@api
```

`common.env` holds the Vault secret-id, hence mode 0600; systemd reads it as root, the
service never sees the file. If Vault requires a client certificate, the `VAULT_KEYSTORE`
lines in the same file point at a PKCS12 under `/etc/datahub`, which the unit's
`ProtectSystem=strict` still lets the service read. Firewall: the api listens on 8081 and the console on 8080
for the load balancer only; open them to the LB addresses, not the world, and the metrics ports
(9080, 9081; see [Metrics](#metrics)) to the Prometheus host.

The apps expect a **pgbouncer on localhost** (`StatelessRoutingDataSource` opens a
connection per request and has no pool of its own). The unit orders itself after
`pgbouncer.service` when that exists. The api and cleanup also need write access to the
tenants' file-storage roots (from Vault); the placement drop-ins grant `/srv/datahub-files`,
adjust to the real mount.

## Dual stack (IPv4 and IPv6)

Tomcat binds the wildcard address, so every service listens on both families with no
setting. Three things to line up:

- `internal-proxies` in `config/api/application.yml` (and analysis) must list the load
  balancers' addresses in the family nginx connects **from**; the example has both.
- The pgbouncer on localhost should listen on `::1` as well as `127.0.0.1`
  (`listen_addr = 127.0.0.1, ::1`), so a `localhost` that resolves to `::1` first works.
- Outbound address selection: the JVM prefers IPv4 for dual-stack names. The commented
  `JAVA_TOOL_OPTIONS` line in `env/common.env` switches it to the host's policy
  (`preferIPv6Addresses=system`) once every backing service answers on both.

`sysctl.d/90-datahub-app.conf` turns off router advertisements, autoconf and temporary
addresses on the app hosts; a server's source address must stay put.

## Metrics

Every service can serve Prometheus metrics at `/actuator/prometheus` on a port of its own: JVM
memory and GC, threads, CPU, Tomcat connections and request latency per endpoint. The api, console
and analysis serve it on a second port next to the application port, so the load balancer never
reaches it; the consumers and cleanup listen on nothing else.

**It ships switched off.** The scrape carries no token, so whatever reaches the port reads your
request rates, error rates, endpoint inventory and JVM internals. Turn it on per service with

```yaml
management:
  endpoints:
    web:
      exposure:
        include: prometheus
```

in `/etc/datahub/<name>/application.yml`.

| Instance | Application port | Metrics port |
|---|---|---|
| `datahub@api` | 8081 | 9081 |
| `datahub@console` | 8080 | 9080 |
| `datahub@analysis` | 8082 | 9082 |
| `datahub@stateless-consumer` | none | 9083 |
| `datahub@stateful-consumer` | none | 9084 |
| `datahub@cleanup` | none | 9085 |

Open the metrics ports to the Prometheus host only, and prefer a certificate over trusting that
rule. Put the stores and their passwords in the shared `datahub-platform` Vault secret, the same one
that holds the Keycloak issuer:

| Vault key | What it is |
|---|---|
| `metrics.keystore` | PKCS12 (or `.jks`) holding this host's server certificate and key |
| `metrics.keystore-password` | its password |
| `metrics.truststore` | the CA that signed the Prometheus client certificate |
| `metrics.truststore-password` | its password |
| `metrics.client-auth` | optional; `need` unless set |

Each service reads those at startup and configures `management.server.ssl.*` (or `server.ssl.*` for
the three without an application port) from them, so the passwords never sit in a file on the
application hosts. Set no `metrics.keystore` and the port stays plain HTTP, which is what an
installation that has not set this up gets.

Prometheus then scrapes with `scheme: https` and a `tls_config` naming its client certificate and
the same CA. The ports bind the wildcard address like the application ports;
`management.server.address` (or `server.address`) narrows that as well, if the host has an internal
interface. A scrape job per host:

```yaml
scrape_configs:
  - job_name: datahub
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ["app-1.internal.example.org:9081", "app-2.internal.example.org:9081"]
        labels: { service: api }
      - targets: ["app-1.internal.example.org:9080", "app-2.internal.example.org:9080"]
        labels: { service: console }
```

The health endpoint is not exposed: its indicators would probe the tenant-routing datasource,
which has no connection to offer without a tenant. nginx's passive checks (`max_fails`) cover
upstream health.


## Why these numbers

**Heap 16-20 GB, fixed size.** `-Xms` = `-Xmx` plus `AlwaysPreTouch`: the whole heap is
faulted in at start, under the unit's `NUMAPolicy=bind`, so it is node-local and never
grows or pages in the request path. Startup takes a few seconds longer. G1 with its defaults is the safe choice;
generational ZGC (`-XX:+UseZGC`) trades some throughput for sub-millisecond pauses if the
api's latency tail matters more.

**Transparent huge pages** (`-XX:+UseTransparentHugePages` with THP in `madvise` mode)
cut TLB misses on a 20 GB heap. Combined with pre-touch the huge pages are assembled at
start, not by khugepaged at runtime. Needs `enabled` set to `madvise` or `always`, hence
the tmpfiles line above.

**Compact object headers** (`-XX:+UseCompactObjectHeaders`, JEP 519, a product flag in
Java 25): 8-byte instead of 12-byte headers, 5-10 % less heap churn on object-heavy
JSON workloads.

**Off-heap.** Direct buffers (Netty, the Pulsar and ClickHouse clients), metaspace and the
code cache are left at the JVM defaults; the memory ceiling in each `placement.conf`
(`MemoryMax`) is the guard against a leak, not a target. `MALLOC_ARENA_MAX=4` keeps glibc from holding on to one
arena per thread.

**Never `-XX:TieredStopAtLevel=1` in production.** It disables the C2 compiler and with
it the SHA-256 intrinsic the upload checksum relies on; uploads drop to a fraction of
disk speed. Dev-only.

**Tomcat** (in `config/<name>/application.yml`): the api runs on virtual threads, so
connections, not threads, are the thing to size. `max-connections` is a ceiling on open
sockets, not a reservation (an idle one costs about 16 KB), and only the api raises it:
nginx keeps `keepalive x worker_processes` idle connections to every instance (128 x 32
workers is 4096 per LB, 8192 with two, which is Tomcat's whole default before a single
WebSocket), so the api gets 20000. The console and analysis stay at the default 8192,
which only nginx's pool ever approaches. `accept-count: 1024` on the api is the listen
backlog for reconnect bursts (needs `somaxconn`, in the sysctl file). `keep-alive-timeout: 75s`
and `max-keep-alive-requests: -1` make Tomcat keep nginx's upstream connections open
until **nginx** drops them at 30 s idle; the default (100 requests, then close) produces
sporadic 502s at the edge. `max-http-request-header-size: 16KB` to match nginx's
`large_client_header_buffers`, which is headroom for the live-tail WebSocket's request
line rather than for the token (the dataset grants are read from UserInfo, so the token
stays a few KB). `forward-headers-strategy:
native` plus a narrow `internal-proxies` list makes the api trust `X-Forwarded-*` from
the load balancers only. Compression is enabled in the apps and off at nginx, so the
gzip CPU lands on the app hosts' cores rather than the edge.

**Kernel** (`sysctl.d/90-datahub-app.conf`): deliberately short, four settings that are
actually wrong for this setup rather than general tuning. NUMA balancing off (the units
already bind; balancing would only add migration work fighting a placement that is
fixed), `vm.max_map_count` raised (the default is sized for a desktop, not several JVMs
with G1's region-per-mmap layout), swappiness 1 (a pre-touched, pinned heap should never
be swap's first target), and `tcp_tw_reuse` on (the connection-per-request to the
localhost pgbouncer is enough churn to a single destination to matter for ephemeral port
turnover; safe here specifically because it is loopback, not the public internet).
Everything else is left at the kernel's own default.

**Hardening** in the unit is the standard read-only-root, private-tmp, no-new-privileges
set. Two things are deliberately *not* there: `MemoryDenyWriteExecute` (the JIT needs
W+X pages) and `SystemCallFilter` (the JVM's syscall surface is wide and a missing call
fails in odd places).

## Diagnostics

No heap dump on OOM (the JVM default) and no core dump (`LimitCORE=0`): both are a copy of
process memory, credentials and tenant data included. An OOM ends the process
(`ExitOnOutOfMemoryError`) and systemd restarts it. Nothing else is logged by default;
everything below is switched on when needed:

```sh
PID=$(systemctl show -p MainPID --value datahub@api)

# Heap occupancy now, and a GC log from now on (one line per collection, before->after)
jcmd $PID GC.heap_info
jcmd $PID VM.log output=file=/var/log/datahub/api/gc.log what=gc decorators=time

# Heap growing: start a recording (about 1 % overhead, bounded to 24 h / 1 GB). The
# environment event is off so the recording does not hold the Vault secret-id.
jcmd $PID JFR.start name=datahub disk=true maxage=24h maxsize=1g memory-leaks=stack-traces jdk.InitialEnvironmentVariable#enabled=false

# Later, pull it with leak candidates and their reference chains (a safepoint while the
# live heap is walked; pick a quiet moment)
jcmd $PID JFR.dump name=datahub path-to-gc-roots=true filename=/var/log/datahub/api/api-%t.jfr
jfr view memory-leaks-by-site /var/log/datahub/api/api-*.jfr        # or JDK Mission Control
jfr view allocation-by-site /var/log/datahub/api/api-*.jfr
jfr view gc-pauses /var/log/datahub/api/api-*.jfr
jcmd $PID JFR.stop name=datahub
```

A recording holds exception messages and stack traces: treat a `.jfr` like a log file.

RSS growing while the heap stays flat is off-heap. Native memory tracking cannot be enabled
at runtime: add `-XX:NativeMemoryTracking=summary` to the env file, restart that instance,
then `jcmd $PID VM.native_memory baseline` and, after a while, `summary.diff`.

When a heap dump is genuinely needed, take it deliberately:

```sh
jcmd $PID GC.heap_dump -gz=1 /var/lib/datahub/api/api-$(date +%F).hprof.gz
```

The JVM pauses while it writes (take the instance out of the load balancer first). Analyse
it on the host or on an encrypted volume, delete it when done, and rotate every secret that
was in the process if the file ever left the host.

## Checking a running service

```sh
systemctl show -p NUMAMask -p CPUAffinity -p MemoryMax datahub@api   # placement applied
numastat -p $(systemctl show -p MainPID --value datahub@api)         # memory all on one node
grep AnonHugePages /proc/$(systemctl show -p MainPID --value datahub@api)/smaps_rollup
jcmd $(systemctl show -p MainPID --value datahub@api) VM.flags       # effective JVM flags
jcmd $(systemctl show -p MainPID --value datahub@api) GC.heap_info
ss -tn state established '( sport = :8081 )' | wc -l                 # open connections
```

`numastat -p` should show all but a few MB under the bound node. If the `Huge` column
stays at zero, THP is not in `madvise`/`always` mode.

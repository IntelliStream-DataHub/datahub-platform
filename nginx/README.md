# nginx load balancer for DataHub

Example configuration for an nginx edge in front of two `datahub-api` and two
`datahub-console` instances: round-robin, TLS terminated at nginx to the SSL Labs A+
bar, tuned for many concurrent connections, with kernel settings for either a 1 or a
10 Gbit/s uplink. The matching
application-side tuning (systemd units, JVM and Tomcat settings) is under
[`../systemd/`](../systemd/README.md).

```
nginx/
  debian/nginx.conf                  tuned main config, replaces /etc/nginx/nginx.conf
  debian/datahub.conf                the two vhosts, goes in sites-available
  almalinux/nginx.conf               same, RHEL-family paths and user
  almalinux/datahub.conf             the two vhosts, goes in conf.d
  sysctl.d/90-datahub-lb.conf        kernel tuning for the LB host, link-independent (both distros)
  sysctl.d/91-datahub-lb-1g.conf     plus ONE of these: bandwidth-dependent settings for a 1 Gbit/s uplink
  sysctl.d/91-datahub-lb-10g.conf    ... or for a 10 Gbit/s uplink
  systemd/nginx.service.d/override.conf   file-descriptor ceiling for nginx.service
```

The two `datahub.conf` files are generated from one template and differ only in the
header comments and an SELinux note; keep them in sync if you change one.

## What the site file does

| Host | Upstream | Notes |
|---|---|---|
| `api.example.org` | `api1`, `api2` :8081 | `/timeseries/datapoints/` is upgraded to WebSocket with hour-long timeouts; `/files`, `/resources/import` and `/resources/export/` stream uploads and downloads with no body cap and no buffering; everything else is buffered REST with a 20 MB body cap |
| `console.example.org` | `console1`, `console2` :8080 | forwards `X-Forwarded-Proto/Host/Port` so the console's OAuth2 redirect URI comes out with the public scheme and host |

Neither host needs session affinity, see the root `README.md`, "Deployment & Scaling".

TLS, per server block: TLS 1.2 and 1.3 only, six ECDHE AEAD suites (no DHE, so no
dhparam file), session tickets off, OCSP stapling, HSTS for two years with
`includeSubDomains`. With a certificate from a public CA this scores A+ on SSL Labs.
`preload` is deliberately left off the HSTS header; add it only when you are ready to
submit the domain to the browser preload list, since that is hard to undo.

Certificate layout: the certbot commands below request one certificate covering both
hostnames (`-d api.example.org -d console.example.org`), and both server blocks point at
the same `/etc/letsencrypt/live/example.org/` directory. nginx does not care that two
`server` blocks share one `ssl_certificate` file (OCSP stapling included: the staple is
tied to the certificate itself, not to `server_name`), as long as the certificate's SAN
list covers whatever host the client SNI asked for. `--cert-name example.org` pins that
directory name explicitly; without it certbot names the directory after the first `-d`
domain (`api.example.org` here), which shifts if the flags are ever reordered. A
wildcard certificate (`*.example.org`, DNS-01 only, see the commented alternative below)
works the same way: one directory, referenced from both blocks. For two separately
issued certificates instead (say api and console on different providers), run certbot
once per domain and point that server block's `ssl_certificate`, `ssl_certificate_key`
and `ssl_trusted_certificate` at its own `/etc/letsencrypt/live/<hostname>/` directory.

## Install

### Debian / Ubuntu

```sh
apt install nginx certbot python3-certbot-nginx
cp debian/nginx.conf   /etc/nginx/nginx.conf
cp debian/datahub.conf /etc/nginx/sites-available/datahub.conf
ln -s /etc/nginx/sites-available/datahub.conf /etc/nginx/sites-enabled/datahub.conf
rm -f /etc/nginx/sites-enabled/default
cp sysctl.d/90-datahub-lb.conf sysctl.d/91-datahub-lb-1g.conf /etc/sysctl.d/   # or -10g
sysctl --system
mkdir -p /etc/systemd/system/nginx.service.d
cp systemd/nginx.service.d/override.conf /etc/systemd/system/nginx.service.d/
certbot certonly --nginx --cert-name example.org -d api.example.org -d console.example.org   # or --webroot
# Wildcard instead (needs a certbot DNS plugin, e.g. --dns-cloudflare, or manual DNS-01;
# HTTP-01/--nginx above cannot issue wildcards):
#   certbot certonly --dns-<provider> --cert-name example.org -d example.org -d '*.example.org'
systemctl daemon-reload && nginx -t && systemctl restart nginx
ufw allow 'Nginx Full'                                               # if ufw is in use
```

`http2 on` needs nginx 1.25.1 or newer (Debian 13 ships 1.26). On Debian 12 (1.22) or
Ubuntu 24.04 (1.24) use the older `listen 443 ssl http2;` form instead.

### AlmaLinux / RHEL

```sh
dnf module enable nginx:1.26                  # AlmaLinux 9; 10 ships 1.26 without modules
dnf install nginx certbot python3-certbot-nginx   # certbot from EPEL
cp almalinux/nginx.conf   /etc/nginx/nginx.conf
cp almalinux/datahub.conf /etc/nginx/conf.d/datahub.conf
cp sysctl.d/90-datahub-lb.conf sysctl.d/91-datahub-lb-1g.conf /etc/sysctl.d/   # or -10g
sysctl --system
mkdir -p /etc/systemd/system/nginx.service.d
cp systemd/nginx.service.d/override.conf /etc/systemd/system/nginx.service.d/
certbot certonly --nginx --cert-name example.org -d api.example.org -d console.example.org   # or --webroot
# Wildcard instead (needs a certbot DNS plugin, e.g. --dns-cloudflare, or manual DNS-01;
# HTTP-01/--nginx above cannot issue wildcards):
#   certbot certonly --dns-<provider> --cert-name example.org -d example.org -d '*.example.org'
setsebool -P httpd_can_network_connect 1      # SELinux: let nginx open upstream sockets
firewall-cmd --permanent --add-service=http --add-service=https && firewall-cmd --reload
systemctl daemon-reload && nginx -t && systemctl enable --now nginx
```

Without the SELinux boolean every `proxy_pass` fails with "Permission denied" and the
client sees a 502. Certificates under `/etc/letsencrypt` or `/etc/pki` already carry
the `cert_t` label nginx may read; anywhere else needs
`semanage fcontext -a -t cert_t '/path(/.*)?' && restorecon -Rv /path`.

## Performance notes

Where the numbers come from, and what to check on the live box.

- **Workers and connections.** `worker_processes auto` with `worker_cpu_affinity auto`
  gives one pinned worker per hardware thread. `worker_connections 65536` per worker,
  with two connections (client plus upstream) per proxied request. `worker_rlimit_nofile`
  and the matching `LimitNOFILE` in the nginx.service drop-in raise the descriptor limit
  to match; the kernel's own default ceiling (`fs.nr_open`) is already exactly that
  number, so there is nothing to change there. An idle TLS connection costs roughly
  20 KB of nginx memory.
- **Accept path.** `reuseport` on the api vhost's `listen` gives each worker its own
  listening socket so the kernel spreads new connections instead of waking every worker.
  `backlog=65535` only takes effect with `net.core.somaxconn` at least that high, which
  is the one setting `sysctl.d/90-datahub-lb.conf` raises off its default.
- **Upstream keep-alive.** 128 idle connections per worker to the api group, 64 to the
  console, 10000 requests per connection. The 30 s idle timeout is deliberately shorter
  than Tomcat's 75 s (`server.tomcat.keep-alive-timeout` in `../systemd/config`), so
  nginx always closes first; if Tomcat closes first you get sporadic 502s from nginx
  reusing a socket that is going away.
- **No disk spooling.** REST responses get 1 MB of in-memory buffers and
  `proxy_max_temp_file_size 0`, so anything larger streams through at the client's pace
  instead of being written to `/var/lib/nginx` first. `/files` and the WebSockets have
  buffering off entirely.
- **Header sizes.** `large_client_header_buffers 4 16k`, with Tomcat matched on the app
  side (`server.max-http-request-header-size: 16KB`). Not for the token: dataset grants
  are read from UserInfo rather than carried in it, so a Keycloak access token stays a
  few KB and the 8k default would already fit it. The headroom is for the live-tail
  WebSocket, whose request line carries the token as `?token=` alongside a
  comma-separated `externalIds` list.
- **Kernel, link-independent** (`sysctl.d/90-datahub-lb.conf`): just `somaxconn`, to
  match nginx's `backlog=65535`, which the kernel would otherwise cap at its own
  (lower) default. Deliberately short: everything else is left at the kernel's own
  default, which on a current kernel (Debian 13 / AlmaLinux 9 or newer) is already sized
  for a busy server. The file's comments cover one thing worth knowing but NOT set:
  disabling IPv6 router advertisements/autoconf, sometimes recommended for a static-
  address server, is unsafe as a blanket default on a host that is also a router or
  firewall, or a cloud instance whose uplink relies on RA for its own IPv6 connectivity.
- **Kernel, per uplink speed** (install one `91-` file): the only thing that differs
  between the two is the TCP autotuning ceiling, sized to the bandwidth-delay product of
  the link so a single large stream (a `/files` upload or download) can reach line rate
  over a real round trip. It does not affect the many small keep-alive/WebSocket
  connections, which stay small either way.

  | Setting | `91-datahub-lb-1g.conf` | `91-datahub-lb-10g.conf` |
  |---|---|---|
  | `rmem_max` / `wmem_max`, `tcp_rmem` / `tcp_wmem` ceiling | 16 MB (1 Gbit/s x 100 ms = 12.5 MB) | 64 MB (10 Gbit/s x 50 ms = 62 MB) |

  **Upgrading the uplink**: `rm /etc/sysctl.d/91-datahub-lb-1g.conf`, copy the `10g` file
  in, `sysctl --system`. No nginx change, no restart. At higher line rates the NIC itself
  also starts to matter: ring sizes (`ethtool -G`), RSS queues spread over the cores
  nginx runs on (`ethtool -L`, IRQ affinity), offloads on (`ethtool -k`); none of that is
  a sysctl and it is not covered here.

  **This assumes a dedicated NIC you control**, where "1 Gbit/s" or "10 Gbit/s" is a real,
  guaranteed line rate. On virtualized or shared-tenancy hosting (Hetzner Cloud, AWS EC2,
  and similar), the advertised port speed is not that: it is commonly burstable, traffic
  shaped, capped by instance/plan size, and shared with other tenants on the same
  physical NIC. Sizing buffers off that nominal number can go either way: throttled well
  below it regardless of buffer size, or oversized, spending RAM on headroom a small,
  memory-constrained instance cannot actually use. On that kind of host:

  - Measure actual sustained throughput to a realistic peer (`iperf3` to a similarly
    distant host, or real traffic) rather than trusting the plan's advertised speed.
  - Check whether the provider publishes its own recommended TCP tuning; several do,
    precisely because their instances do not behave like a dedicated line.
  - When unsure, start from the `1g` file. It is the conservative choice, and only raise
    it once you have measured that the host can sustain more.
- **Compression** is done by the apps (`server.compression` in the Spring Boot tuning),
  not by nginx, so the edge spends its CPU on TLS and copying. If you prefer stock app
  config, turn `gzip on` in `nginx.conf` with `gzip_proxied any; gzip_types application/json
  text/css application/javascript; gzip_comp_level 2;` instead.
- **Logs** are buffered (`buffer=256k flush=5s`): one write per 256 KB instead of one per
  request. The `datahub_lb` format adds upstream address, request and upstream timings,
  and the negotiated TLS protocol and cipher.

## Dual stack (IPv4 and IPv6)

Everything here serves both families; the checklist for making that real:

- **DNS**: `api.example.org` and `console.example.org` need an `AAAA` record next to the
  `A` record. Certbot's `--nginx` authenticator validates over whichever family Let's
  Encrypt picks (it prefers IPv6 when an AAAA exists), so the v6 address must actually
  answer on port 80 before you request the certificate.
- **Listeners**: each `server` has `listen 443 ssl` and `listen [::]:443 ssl`. nginx binds
  `[::]` with `ipv6only=on`, so the two sockets are independent and `reuseport`/`backlog`
  are set on both. Same on port 80 for the redirect.
- **Upstreams**: give the internal hostnames one address family, or use literal addresses
  (`server [fd00:10::21]:8081;`). A name with both `A` and `AAAA` becomes two peers for
  one instance and skews the round-robin. Whichever family nginx connects from must be in
  the api's `internal-proxies` list (`../systemd/config/api/application.yml`), or Tomcat
  ignores `X-Forwarded-For` from that LB.
- **Resolver**: the OCSP `resolver` line lists v4 and v6 nameservers; keep at least one
  the host can reach.
- **Firewall**: firewalld's `http`/`https` services cover both families; ufw does too as
  long as `IPV6=yes` in `/etc/default/ufw` (the default). Never block ICMPv6, IPv6 has no
  router fragmentation and relies on "packet too big" messages for path MTU discovery.
- **Kernel**: `net.core.somaxconn` in `sysctl.d/90-datahub-lb.conf` applies to both
  families. Router advertisements and autoconf are deliberately left at the kernel
  default (on): disabling them stabilizes a static server's address, but only when
  nothing on that segment legitimately sends RA, not true on a router/firewall host or a
  cloud instance whose own uplink relies on it. See the comment in that file before
  turning it off, and scope it to one interface rather than "all" if you do.

Useful checks once it is running:

```sh
nginx -T | grep -E 'worker_(processes|connections|rlimit)'   # effective limits
cat /proc/$(pgrep -o -x nginx)/limits | grep 'open files'    # master's descriptor limit
ss -s                                                        # socket totals, TIME_WAIT count
sysctl net.core.somaxconn net.core.rmem_max                              # kernel tuning applied?
openssl s_client -connect api.example.org:443 -tls1_1 </dev/null   # must fail
curl -sI https://api.example.org/ | grep -i strict-transport     # HSTS present
curl -6 -sI https://api.example.org/ | head -1                   # reachable over IPv6
ss -ltn '( sport = :443 )'                                       # one listener per family
```

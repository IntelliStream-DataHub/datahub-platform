# SPDX-License-Identifier: AGPL-3.0-or-later
"""Shared plumbing for the 3W demo seeder and feeder.

Holds the three things both need: a client that waits until the stack will actually accept
a tenant-scoped write, adapters that turn the committed wire payloads back into SDK
objects, and the datapoint CSV reader.
"""
from __future__ import annotations

import base64
import csv
import gzip
import json
import os
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

import intellistream_datahub_sdk as dh

DATA_DIR = Path(os.environ.get("DEMO_DATA_DIR", "/app/data"))
DATASET_ID_PLACEHOLDER = "__DATASET_ID__"


def demo_disabled():
    """True when the operator has opted out of the demo.

    The demo is on by default so a fresh `compose up` has something to show, but a stack
    used for development rather than demoing does not want ~62k datapoints in its `foo`
    tenant or a feeder appending to it forever. compose cannot conditionally skip a service
    (podman-compose ignores `profiles`), so the containers start and exit immediately
    instead: `DEMO_ENABLED=false docker compose up -d`.
    """
    return os.environ.get("DEMO_ENABLED", "true").strip().lower() in ("0", "false", "no", "off")


def sdk_version():
    """Version of the SDK actually installed, for traceability in the log."""
    try:
        from importlib.metadata import version
        return version("intellistream-datahub-sdk")
    except Exception:
        return "unknown"


def log(msg):
    print(f"[{os.environ.get('DEMO_ROLE', 'demo')}] {msg}", flush=True)


def env_int(name, default):
    try:
        return int(os.environ.get(name, "") or default)
    except ValueError:
        return default


# --------------------------------------------------------------------------------------
# Auth
# --------------------------------------------------------------------------------------

def _claims(token):
    """Decode a JWT payload without verifying it — for diagnostics only."""
    try:
        part = token.split(".")[1]
        part += "=" * (-len(part) % 4)
        return json.loads(base64.urlsafe_b64decode(part))
    except Exception:
        return {}


def make_client(announce=True):
    base_url = os.environ.get("DATAHUB_BASE_URL", "http://datahub-api:8081")
    token_url = os.environ.get("DATAHUB_TOKEN_URL", "")
    client_id = os.environ.get("DATAHUB_CLIENT_ID", "datahub-service-foo")
    scope = os.environ.get("DATAHUB_SCOPE", "openid organization:foo")

    # Printed before anything else, because both ways this fails look like a credentials
    # problem and are not. The api validates the token's `iss` against the issuer Vault was
    # seeded with (scripts/up.sh sets that to this host's IP via KC_ADDR), so a token minted
    # through a different host:port is rejected outright. And without an organization
    # selector in the scope, Keycloak issues a token with no `organization` claim at all,
    # which SecurityConfig.OrganizationValidator rejects on every request.
    if announce:
        log(f"api    {base_url}")
        log(f"token  {token_url}")
        log(f"client {client_id}   scope '{scope}'")

    return dh.DataHubClient(
        base_url=base_url, token_url=token_url, client_id=client_id,
        client_secret=os.environ.get("DATAHUB_CLIENT_SECRET", ""), scope=scope,
    )


def wait_for_org_claim(timeout=None):
    """Block until Keycloak issues a token DataHub can actually resolve a tenant from.

    This is the readiness signal that matters on a cold start, and it is not the one the
    app containers use. They gate on the OIDC discovery document, which Keycloak serves as
    soon as the realm is imported — a good ten seconds BEFORE keycloak-bootstrap has set
    `addOrganizationId` on the organization membership mapper. Tokens minted in that window
    carry `organization` as a flat array of aliases with no id, which
    SecurityConfig.OrganizationValidator rejects outright.

    So we mint a probe token here with urllib rather than the SDK, look at the claim, and
    wait for the shape the api requires. Two reasons not to use the SDK for this: it caches
    its token until expiry and does not re-mint on a 401, so a probe through it would
    poison the client it hands back; and a plain HTTP call gives us the raw claim to report.
    """
    timeout = timeout or env_int("DEMO_READY_TIMEOUT", 600)
    token_url = os.environ.get("DATAHUB_TOKEN_URL", "")
    data = urllib.parse.urlencode({
        "grant_type": "client_credentials",
        "client_id": os.environ.get("DATAHUB_CLIENT_ID", "datahub-service-foo"),
        "client_secret": os.environ.get("DATAHUB_CLIENT_SECRET", ""),
        "scope": os.environ.get("DATAHUB_SCOPE", "openid organization:foo"),
    }).encode()

    deadline = time.time() + timeout
    attempt, complained = 0, False
    while time.time() < deadline:
        attempt += 1
        claim = "unavailable"
        try:
            with urllib.request.urlopen(token_url, data=data, timeout=10) as resp:
                token = json.loads(resp.read())["access_token"]
            claim = _claims(token).get("organization")
            # The shape the api needs: an object keyed by alias, each carrying an id.
            if isinstance(claim, dict) and claim and all(
                    isinstance(v, dict) and v.get("id") for v in claim.values()):
                log(f"organization claim ready: {list(claim)}")
                return
        except Exception as e:
            claim = f"token request failed: {str(e)[:80]}"
        if not complained:
            complained = True
            log(f"waiting for keycloak-bootstrap — organization claim is {claim!r}, which the")
            log("  api cannot resolve a tenant from. This is normal for the first ~15s of a")
            log("  cold start, while the org membership mapper is still being configured.")
        time.sleep(2)
    log(f"organization claim never became usable within {timeout}s (last: {claim!r})")
    _explain_auth_failure()
    raise SystemExit("keycloak did not become ready")


def wait_for_api(timeout=None):
    """Block until a tenant-scoped, authenticated call succeeds; return a working client.

    Deliberately not a socket or `GET /` check. This one call only returns once Keycloak
    has imported the realm, keycloak-bootstrap has applied the organization mapper and the
    /datasets/* grants (which arrive via UserInfo, not the token), and datahub-api has
    finished the per-tenant Flyway migration this request itself triggers. compose
    `depends_on` proves none of that, and is unreliable under podman-compose anyway.

    A NEW client is built for every attempt, which matters more than it looks: on a cold
    start this container can mint its first token in the window before keycloak-bootstrap
    has fixed the organization mappers, and that token carries a claim the api rejects.
    The SDK caches its token until expiry, so retrying on the same client re-sends the same
    bad token and can never recover — the wait would burn its whole budget and fail even
    though the stack came good seconds in.
    """
    timeout = timeout or env_int("DEMO_READY_TIMEOUT", 600)
    deadline = time.time() + timeout
    attempt, last = 0, None
    while time.time() < deadline:
        attempt += 1
        client = make_client(announce=(attempt == 1))
        try:
            client.datasets.by_ids(["demo_readiness_probe"])
            log(f"api ready after {attempt} attempt(s)")
            return client
        except Exception as e:
            last = str(e).strip()[:200] or f"{type(e).__name__} with no message"
            if attempt in (3, 10) or attempt % 30 == 0:
                log(f"waiting for api ... ({last})")
            time.sleep(2)
    # Always explain on the way out. The SDK sometimes raises with an empty message, and
    # "api not ready, last error: " tells nobody anything — while the cause is nearly
    # always one of two knowable things.
    _explain_auth_failure()
    raise SystemExit(f"api not ready after {timeout}s; last error: {last}")


def _explain_auth_failure():
    """State the two knowable causes, with this container's actual settings."""
    token_url = os.environ.get("DATAHUB_TOKEN_URL", "")
    log("  this is nearly always one of two things:")
    log(f"  1. issuer mismatch — tokens are minted at {token_url}, but the api only accepts")
    log("     the issuer vault-seed wrote. Both come from ${KC_ADDR}: start the stack with")
    log("     ./scripts/up.sh (which exports it) rather than calling compose directly, or")
    log("     export KC_ADDR=<this host's IP> first.")
    log(f"  2. no organization claim — scope is '{os.environ.get('DATAHUB_SCOPE', '')}', which")
    log("     must name one, e.g. 'openid organization:foo'. Without it Keycloak omits the")
    log("     claim entirely and every request 401s.")


# --------------------------------------------------------------------------------------
# Committed payloads -> SDK objects
# --------------------------------------------------------------------------------------
#
# The payloads under data/ are the wire bodies the SDK itself produced (see
# generate/generate.py), so field names are the api's camelCase. These adapters map them
# back onto SDK constructors. Keys are listed explicitly rather than splatted: the api
# rejects a body naming a field it does not have, so an unknown key must fail here, loudly
# and locally, instead of becoming a 400 halfway through provisioning.

def _dataset_id(value, real_id):
    if value in (None, ""):
        return None
    return real_id if value == DATASET_ID_PLACEHOLDER else int(value)


def resource_from_wire(d, real_id=None):
    return dh.Resource(
        external_id=d.get("externalId"), name=d.get("name"),
        metadata=d.get("metadata") or None, description=d.get("description"),
        is_root=bool(d.get("isRoot", False)),
        data_set_id=_dataset_id(d.get("dataSetId"), real_id),
        source=d.get("source"), labels=d.get("labels") or None,
    )


def timeseries_from_wire(d, real_id=None):
    return dh.TimeSeries(
        external_id=d["externalId"], name=d.get("name"),
        value_type=d.get("valueType"), unit=d.get("unit"),
        description=d.get("description"), metadata=d.get("metadata") or None,
        data_set_id=_dataset_id(d.get("dataSetId"), real_id),
    )


def rel_from_wire(d, real_id=None):
    return dh.RelForm(
        relationship_type=d["relationshipType"],
        from_external_id=d.get("fromExternalId"), to_external_id=d.get("toExternalId"),
        metadata=d.get("metadata") or None,
        data_set_id=_dataset_id(d.get("dataSetId"), real_id),
        description=d.get("description"),
    )


def dataset_from_wire(d):
    return dh.Dataset(
        external_id=d["externalId"], name=d.get("name"),
        description=d.get("description"), metadata=d.get("metadata") or None,
    )


# --------------------------------------------------------------------------------------
# Committed data
# --------------------------------------------------------------------------------------

def load_payloads():
    assets = json.loads((DATA_DIR / "assets.json").read_text())
    series = json.loads((DATA_DIR / "timeseries.json").read_text())
    manifest = json.loads((DATA_DIR / "MANIFEST.json").read_text())
    wells = [w for w in manifest["wells"] if w["externalId"] in assets]
    selected = os.environ.get("DEMO_WELLS", "").split()
    if selected:
        wells = [w for w in wells if str(w["wellId"]) in selected]
    if not wells:
        raise SystemExit(f"no wells to seed (DEMO_WELLS={os.environ.get('DEMO_WELLS')})")
    return assets, series, manifest, wells


def read_datapoints(well_id):
    """Return (series_external_ids, rows) from the committed csv.gz.

    Rows are (offset_seconds, [value|None per series]). Offsets are relative on purpose:
    absolute timestamps would put the demo however far in the past the checkout is old, so
    the caller anchors them against its own clock.
    """
    path = DATA_DIR / "datapoints" / f"well-{well_id:0>5}.csv.gz"
    with gzip.open(path, "rt", newline="") as fh:
        reader = csv.reader(fh)
        header = next(reader)
        names = header[1:]
        rows = []
        for raw in reader:
            values = [float(v) if v != "" else None for v in raw[1:]]
            rows.append((float(raw[0]), values))
    return names, rows

"""Seed the 3W well fleet into a running DataHub. One-shot; safe to re-run.

Replays the pre-generated payloads under data/ through the DataHub SDK: a Dataset per
well, then its asset model (subsystems, equipment, instruments, timeseries and the edges
between them), the anomaly-detection lineage graph, and a backfill of history that ends at
now.

Exits non-zero if the data does not become readable, because ingest is asynchronous —
POST /timeseries/data hands off to Pulsar and a consumer writes ClickHouse later, so
"every request returned 2xx" does not mean the demo is there.
"""
from __future__ import annotations

import os
import sys
import time
from datetime import datetime, timedelta, timezone

import intellistream_datahub_sdk as dh

from demo.common import (
    demo_disabled, sdk_version, dataset_from_wire, env_int, load_payloads, log, read_datapoints,
    rel_from_wire, resource_from_wire, timeseries_from_wire, wait_for_api, wait_for_org_claim,
)


def sentinel_id(well_id):
    """The node that means "this well finished provisioning".

    The anomaly-detection root is created last, so its presence is the one cheap check
    that distinguishes a complete well from one interrupted half-way.
    """
    return f"Well_{well_id:0>5}_anomaly_detection"


def already_provisioned(client, well_id):
    try:
        return bool(client.resources.by_ids([sentinel_id(well_id)]))
    except Exception:
        return False


def create_missing(client, nodes, relations=None):
    """Create only the nodes that do not exist yet, then the relations.

    Resource creation is all-or-nothing and external ids are unique, so re-posting a batch
    that is even partly present fails the whole batch with a 409. Reconcile first rather
    than posting hopefully and swallowing the error.
    """
    wanted = {n.external_id: n for n in nodes}
    existing = set()
    if wanted:
        try:
            existing = {r.external_id for r in client.resources.by_ids(list(wanted))}
        except Exception:
            existing = set()
    missing = [n for eid, n in wanted.items() if eid not in existing]
    if missing:
        client.resources.create(missing)
    if relations:
        try:
            client.resources.create(nodes=[], relations=relations)
        except Exception as e:
            # Edges carry a uniqueness constraint too; on a partial re-run the batch can
            # fail wholesale, so fall back to one at a time and let the duplicates 409.
            log(f"  relations batch rejected ({str(e)[:80]}); retrying individually")
            for rel in relations:
                try:
                    client.resources.create(nodes=[], relations=[rel])
                except Exception:
                    pass
    return len(missing)


def seed_well(client, well, assets, series):
    ext = well["externalId"]
    wid = well["wellId"]
    payload = assets[ext]

    dataset = ensure_dataset(client, payload["dataset"])
    real_id = int(dataset.id)

    root = [resource_from_wire(n, real_id) for n in payload["root"].get("nodes", [])]
    body = [resource_from_wire(n, real_id) for n in payload["assets"].get("nodes", [])]
    ts_items = [timeseries_from_wire(t, real_id) for t in series[ext].get("items", [])]
    rels = [rel_from_wire(r, real_id) for r in payload["relations"].get("relations", [])]
    an_nodes = [resource_from_wire(n, real_id) for n in payload["anomaly"].get("nodes", [])]
    an_rels = [rel_from_wire(r, real_id) for r in payload["anomaly"].get("relations", [])]

    # Order matters: edges reference their endpoints by external id, so every node —
    # including the timeseries a `senses_on` edge points at — has to exist first.
    create_missing(client, root)
    create_missing(client, body)
    create_timeseries(client, ts_items)
    create_missing(client, [], rels)
    # Last, so the sentinel only appears once everything above landed.
    create_missing(client, an_nodes, an_rels)

    log(f"well {wid}: dataset {real_id}, {len(root) + len(body)} resources, "
        f"{len(ts_items)} timeseries, {len(rels)} relations, "
        f"anomaly graph {len(an_nodes)} nodes / {len(an_rels)} edges")
    return real_id


def ensure_dataset(client, wire):
    """Fetch-then-create: datasets do not enforce unique external ids, so a blind create
    on a re-run would silently make a second one and split the demo across both."""
    # The captured body is the wire envelope, {"items": [ ... ]}.
    wire = wire["items"][0] if "items" in wire else wire
    ext = wire["externalId"]
    found = client.datasets.by_ids([ext])
    if found:
        return found[0]
    created = client.datasets.create([dataset_from_wire(wire)])
    if not created:
        raise SystemExit(f"could not create or find dataset {ext}")
    return created[0]


def create_timeseries(client, items):
    if not items:
        return
    existing = set()
    try:
        existing = {t.external_id for t in client.timeseries.by_ids([i.external_id for i in items])}
    except Exception:
        pass
    missing = [i for i in items if i.external_id not in existing]
    if missing:
        client.timeseries.create(missing)


def backfill(client, well, batch=None):
    """Upload the committed history, anchored so its last point is now."""
    wid = well["wellId"]
    names, rows = read_datapoints(wid)
    if not rows:
        return 0
    batch = batch or env_int("DEMO_BACKFILL_BATCH", 20000)

    refs = {t.external_id: t for t in client.timeseries.by_ids(names)}
    span = rows[-1][0] - rows[0][0]
    end = datetime.now(timezone.utc)
    start = end - timedelta(seconds=span)
    stamps = [start + timedelta(seconds=(off - rows[0][0])) for off, _ in rows]

    total, probe = 0, None
    for col, name in enumerate(names):
        ref = refs.get(name)
        if ref is None:
            continue  # not provisioned on this well
        ts, vals = [], []
        for stamp, (_, values) in zip(stamps, rows):
            v = values[col]
            if v is not None:
                ts.append(stamp)
                vals.append(v)
        for i in range(0, len(ts), batch):
            client.timeseries.insert_from_lists(ts[i:i + batch], vals[i:i + batch], ref)
        total += len(ts)
        if probe is None and len(ts) > 1:
            probe = (name, len(ts))
    log(f"well {wid}: backfilled {total:,} points across {len(refs)} series "
        f"({stamps[0]:%Y-%m-%d %H:%M} → now)")
    # Hand back a series to verify and how many points it should have, so the confirmation
    # can check the history actually landed rather than that *something* did.
    return total, {"probe": probe, "start": start, "end": end}


def confirm(client, wells, uploaded, timeout=None):
    """Prove the backfill is readable, not merely accepted.

    Reads back through retrieve_datapoints, which goes to ClickHouse — so this is the one
    check that proves the Pulsar → stateless-consumer → ClickHouse hop actually completed.
    (retrieve_latest_datapoints would not: it is served from Valkey first and will happily
    return a point that never reached storage.)

    It counts, rather than asking "is there at least one point", and it stops at the moment
    seeding ended so the feeder's live writes cannot be mistaken for backfilled history.
    An earlier version did neither and passed happily on a run where Pulsar had dropped
    nearly the whole backfill — one surviving live datapoint was enough to satisfy it.
    """
    timeout = timeout or env_int("DEMO_CONFIRM_TIMEOUT", 180)
    # Ingest is asynchronous and batched, so allow for some of the tail still being in
    # flight; anything below this means points were dropped, not delayed.
    need = float(os.environ.get("DEMO_CONFIRM_RATIO", "0.9"))
    deadline = time.time() + timeout
    pending = {w["externalId"]: w for w in wells if uploaded.get(w["externalId"], {}).get("probe")}
    best, complained = {}, set()
    while pending and time.time() < deadline:
        for ext, well in list(pending.items()):
            info = uploaded[ext]
            probe, expected = info["probe"]
            try:
                got = client.timeseries.retrieve_datapoints(dh.RetrieveFilter(
                    ts=probe,
                    start=info["start"] - timedelta(minutes=1),
                    # Stop where the backfill stopped: past this point the feeder is
                    # writing, and its data would mask a lost history.
                    end=info["end"] + timedelta(seconds=1),
                    limit=expected + 10,
                ))
                found = len(got[0].get_datapoints()) if got else 0
                best[ext] = max(best.get(ext, 0), found)
                if found >= expected * need:
                    log(f"well {well['wellId']}: {found:,}/{expected:,} backfilled points "
                        "readable from storage")
                    pending.pop(ext)
            except Exception as e:
                # Report the first failure per well. Swallowing these silently makes a
                # broken read look identical to data that has not arrived yet, which sends
                # you debugging the consumers when the bug is right here.
                if ext not in complained:
                    complained.add(ext)
                    log(f"well {well['wellId']}: read-back failed ({str(e)[:120]})")
        if pending:
            time.sleep(3)
    if pending:
        detail = ", ".join(
            f"{e}: {best.get(e, 0):,}/{uploaded[e]['probe'][1]:,}" for e in sorted(pending))
        raise SystemExit(
            f"backfilled datapoints never became readable within {timeout}s ({detail}). "
            "The api accepted them, so the break is downstream: they were published to "
            "Pulsar but not written to ClickHouse. Check datahub-stateless-consumer's logs, "
            "and that pulsar-init pre-created the batched-datapoints-all-sub subscription — "
            "without it a consumer that subscribes late silently drops everything published "
            "before it attached.")

    # A second check: the asset model is readable back. This deliberately does NOT try to
    # count edges — `by_ids` does not populate `related_resources`, so a graph assertion
    # made here would report 0 whether or not the edges exist, which is worse than no
    # check. Whether the stateful consumer has applied them to Neo4j shows up in the
    # console's graph view, and can lag this by a few seconds.
    for well in wells:
        try:
            found = client.resources.by_ids([well["externalId"]])
            if not found:
                raise SystemExit(f"well {well['wellId']}: the well node is not readable "
                                 "after seeding — provisioning did not take effect")
            log(f"well {well['wellId']}: asset model readable ({found[0].name})")
        except SystemExit:
            raise
        except Exception as e:
            log(f"well {well['wellId']}: could not read back the well node ({str(e)[:80]})")


def main():
    os.environ.setdefault("DEMO_ROLE", "demo-seed")
    if demo_disabled():
        log("DEMO_ENABLED is false — nothing to do")
        return
    assets, series, manifest, wells = load_payloads()
    log(f"3W demo — {len(wells)} well(s) from data generated {manifest['generatedAt']}")
    log(f"sdk    intellistream-datahub-sdk {sdk_version()}")

    wait_for_org_claim()
    client = wait_for_api()

    force = os.environ.get("DEMO_FORCE_RESEED", "").lower() in ("1", "true", "yes")
    seeded, total, uploaded = [], 0, {}
    for well in wells:
        if not force and already_provisioned(client, well["wellId"]):
            log(f"well {well['wellId']}: already provisioned, skipping")
            continue
        try:
            seed_well(client, well, assets, series)
        except Exception as e:
            msg = str(e)
            if "Unknown field" in msg or "unreadable-request-body" in msg:
                raise SystemExit(
                    f"the api rejected a field this SDK sent ({msg[:160]}).\n"
                    f"  That usually means the pinned SDK is out of step with this api.\n"
                    f"  Installed: intellistream-datahub-sdk {sdk_version()}; the pin lives in "
                    f"deploy/demo/Dockerfile. Bump it and rebuild the demo image.")
            raise
        count, info = backfill(client, well)
        total += count
        uploaded[well["externalId"]] = info
        seeded.append(well)

    if seeded:
        confirm(client, seeded, uploaded)
        log(f"done — seeded {len(seeded)} well(s), {total:,} datapoints")
    else:
        log("done — nothing to do, the fleet is already seeded")


if __name__ == "__main__":
    main()

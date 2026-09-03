#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Regenerate the committed 3W demo payloads under deploy/demo/data/.

MAINTAINER TOOL. This is not part of the image and not part of `up.sh --demo`; users
never run it. It exists so the slow, deterministic half of the demo — downloading ~130MB
of Petrobras 3W parquet, parsing it, and rebuilding the asset model — happens once here
instead of on every machine that wants to look at DataHub.

## How it gets the payloads right

It does NOT hand-write JSON against the API docs. It runs the real provisioning code from
the 3W demo repo through the real SDK, pointed at a capture server that records each
request body and answers with a canned success. So every committed payload is one the SDK
itself produced, with the SDK's own conversions already applied — which matters more than
it sounds:

  * `provisioning.py` passes `value_type="DECIMAL"`, but AllowedValueTypeValidator only
    accepts `bigint, float, float32, numeric, decimal32, mixed, text`. The SDK normalises
    it to `float` on the way out. Hand-writing "DECIMAL" would 422.
  * The valve-state series are `bigint` while the 3W ESTADO-* columns are doubles, and
    TimeseriesService.insertDatapoints runs Long.parseLong per point and 422s the WHOLE
    request on one fractional value. We therefore emit those columns pre-rounded.

## It writes to nothing

Nothing is provisioned anywhere: the capture server never forwards. An earlier version
proxied to a live stack so the payloads were provably accepted, but generating then had to
delete what it made, and the API's stranded-resource rule refuses a delete that would cut
other resources off from the graph root — so cleanup failed against any tenant that was
not empty. Capturing offline removes the whole problem, needs no stack and no credentials,
and is repeatable. Acceptance is still proven, just later and for real: `demo.seed`
confirms every well reached ClickHouse and Neo4j on its first run.

Usage (needs only the 3W demo repo checked out):

    python generate.py --demo-repo ../../../3W_demo

Run it with the 3W demo's own virtualenv, which has pandas/pyarrow and the SDK.
"""
from __future__ import annotations

import argparse
import csv
import gzip
import json
import os
import shutil
import subprocess
import sys
import threading
import urllib.request
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

OUT_DIR = Path(__file__).resolve().parent.parent / "data"
DATASET_ID_PLACEHOLDER = "__DATASET_ID__"


# --------------------------------------------------------------------------------------
# Recording proxy
# --------------------------------------------------------------------------------------

class _Recorder:
    """Captured (path, body) pairs, drained one SDK call at a time."""

    def __init__(self):
        self.entries: list[tuple[str, dict]] = []
        self.lock = threading.Lock()

    def add(self, path, body):
        with self.lock:
            self.entries.append((path, body))

    def take(self):
        """Return everything captured since the last take() and reset."""
        with self.lock:
            out, self.entries = self.entries, []
        return out


# The dataset id the capture server hands back. Every occurrence is swapped for
# DATASET_ID_PLACEHOLDER on the way out, so the value only has to be unmistakable in a
# diff if the substitution ever misses one.
FAKE_DATASET_ID = 999999


def start_capture(recorder: _Recorder) -> str:
    """Run a capture server on a free port; return its base URL.

    Records each request body and answers with the minimum shape the SDK can deserialize.
    Nothing is forwarded, so this needs no stack, no token and no tenant.
    """

    def canned(path):
        if path.endswith("/datasets/byids"):
            return {"items": []}          # forces ensure_dataset down its create branch
        if path.endswith("/datasets/create"):
            # Deserialized into the SDK's Dataset, so every non-optional field of that
            # struct has to be present even though only `id` is ever read.
            return {"items": [{
                "id": str(FAKE_DATASET_ID), "externalId": "captured", "name": "captured",
                "description": None, "labels": ["DATASET"], "metadata": {},
                "policies": [], "connectedDataSets": [], "relatedResources": [],
                "source": None, "createdTime": "2026-01-01T00:00:00Z",
                "lastUpdatedTime": "2026-01-01T00:00:00Z",
            }]}
        if path.endswith("/timeseries/create"):
            return {"items": []}
        # /resources/create — note NOT including "items", which the SDK aliases onto
        # `nodes` and then rejects as a duplicate field.
        return {"nodes": [], "relations": []}

    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def do_POST(self):
            length = int(self.headers.get("Content-Length") or 0)
            raw = self.rfile.read(length) if length else b""
            if raw:
                try:
                    recorder.add(self.path, json.loads(raw))
                except ValueError:
                    pass
            body = json.dumps(canned(self.path)).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, *a):
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return f"http://127.0.0.1:{server.server_address[1]}"


# --------------------------------------------------------------------------------------
# Payload post-processing
# --------------------------------------------------------------------------------------

def blank_dataset_ids(obj, real_id):
    """Replace this well's dataset id with the placeholder the seed substitutes.

    The id is assigned by the API at dataset-create time, so it cannot be committed; the
    seed creates the dataset first and swaps its own id back in.
    """
    if isinstance(obj, dict):
        return {
            k: (DATASET_ID_PLACEHOLDER if k == "dataSetId" and str(v) == str(real_id)
                else blank_dataset_ids(v, real_id))
            for k, v in obj.items()
        }
    if isinstance(obj, list):
        return [blank_dataset_ids(v, real_id) for v in obj]
    return obj


def only(entries, suffix):
    """The single captured body for a path ending in `suffix`."""
    hits = [b for p, b in entries if p.endswith(suffix)]
    if len(hits) != 1:
        raise SystemExit(f"expected exactly one {suffix} capture, got {len(hits)}")
    return hits[0]


# --------------------------------------------------------------------------------------
# Main
# --------------------------------------------------------------------------------------

def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--demo-repo", type=Path, default=Path("../../../3W_demo"),
                    help="checkout of the 3W demo repo (source of the asset model)")
    ap.add_argument("--wells", type=int, nargs="+", default=[1, 2, 3])
    ap.add_argument("--rows", type=int, default=5000,
                    help="timestamps kept per well (after --stride)")
    ap.add_argument("--stride", type=int, default=5,
                    help="keep every Nth row; the 3W source is 1Hz, so 5 => a 5s cadence")
    args = ap.parse_args()

    demo_repo = args.demo_repo.resolve()
    if not (demo_repo / "demo_src" / "provisioning.py").exists():
        raise SystemExit(f"no 3W demo repo at {demo_repo} (pass --demo-repo)")
    sys.path.insert(0, str(demo_repo))
    os.chdir(demo_repo)  # config.WELLS_DIR and the parquet cache are repo-relative

    import pandas as pd
    import intellistream_datahub_sdk as dh
    from demo_src import timeline
    from demo_src.config import WELLS_DIR
    from demo_src.create_synth_wells2 import ensure_well_parquet
    from demo_src.metadata import node_metadata
    from demo_src.provisioning import (
        SENSOR_TS_SUFFIX, columns_with_data, ensure_dataset, nice_name, provision_well,
    )

    recorder = _Recorder()
    # A literal token: the capture server never checks it, and this keeps the generator
    # independent of Keycloak entirely.
    client = dh.DataHubClient(base_url=start_capture(recorder), token="capture-only")

    # Cell 9 of provision_well_fleet.ipynb, verbatim apart from the import alias. A fresh
    # dh.Resource per call is deliberate: reusing one template object aliases the SAME
    # object into `nodes` repeatedly, so the payload repeats one external_id -> 409.
    def build_anomaly_graph(well_id, timeseries):
        auto_encoder = dh.Resource(
            external_id=f"Well_{well_id:0>5}_anomaly_detection", labels=["ANOMALY"],
            name="Anomaly Detection", metadata={"Algorithm": "LSTM Autoencoder"},
        )
        nodes, rels = [auto_encoder], []

        def add_preprocessing(ts, signal):
            win = dh.Resource(
                name="windowing", external_id=f"{signal}_{well_id:0>5}_windowing",
                metadata={"stride": "150", "window length": "300", "window function": "Boxcar"},
                labels=["PREPROCESSOR"])
            scal = dh.Resource(
                name="Scaling", external_id=f"{signal}_{well_id:0>5}_scaling",
                labels=["PREPROCESSOR"], metadata={"scaling function": "minmax"})
            nodes.extend([win, scal])
            rels.extend([
                dh.RelForm(from_external_id=ts.external_id, to_external_id=win.external_id,
                           relationship_type="INPUT_TO"),
                dh.RelForm(from_external_id=win.external_id, to_external_id=scal.external_id,
                           relationship_type="INPUT_TO"),
                dh.RelForm(from_external_id=scal.external_id,
                           to_external_id=auto_encoder.external_id, relationship_type="INPUT_TO"),
            ])

        for ts in timeseries:
            for needle, signal in (("pdg_pressure", "pdg_pressure"),
                                   ("tpt_temp", "tpt_temperature"),
                                   ("tpt_pressure", "tpt_pressure")):
                if needle in ts.external_id:
                    add_preprocessing(ts, signal)
        return nodes, rels

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    (OUT_DIR / "datapoints").mkdir(exist_ok=True)
    assets, series, manifest_wells = {}, {}, []

    for wid in args.wells:
        ext = f"synthetic_3w_well_{wid:0>5}"
        print(f"[generate] well {wid}: ensuring parquet ...", flush=True)
        if not ensure_well_parquet(wid, WELLS_DIR):
            print(f"[generate] well {wid}: no 3W files, skipping")
            continue

        dataset = ensure_dataset(client, ext)
        dataset_body = only(recorder.take(), "/datasets/create")

        well = dh.Resource(
            external_id=ext, name=nice_name(ext),
            metadata=node_metadata(ext, ["ASSET", "WELL"]),
            is_root=True, labels=["ASSET", "WELL"], data_set_id=dataset.id,
        )
        present = columns_with_data(wid)
        nodes, ts_list, relations = provision_well(
            well, data_set_id=dataset.id, present_columns=present)

        # The order is load-bearing and mirrors notebook cell 8: root, then assets, then
        # timeseries, then relations LAST, because edges reference nodes by external_id.
        client.resources.create([well]);          root_body = only(recorder.take(), "/resources/create")
        client.resources.create(nodes);           assets_body = only(recorder.take(), "/resources/create")
        client.timeseries.create(ts_list);        ts_body = only(recorder.take(), "/timeseries/create")
        client.resources.create(nodes=[], relations=relations)
        rel_body = only(recorder.take(), "/resources/create")
        an_nodes, an_rels = build_anomaly_graph(wid, ts_list)
        client.resources.create(nodes=an_nodes, relations=an_rels)
        anomaly_body = only(recorder.take(), "/resources/create")

        blank = lambda b: blank_dataset_ids(b, dataset.id)
        assets[ext] = {
            "dataset": dataset_body, "root": blank(root_body), "assets": blank(assets_body),
            "relations": blank(rel_body), "anomaly": blank(anomaly_body),
        }
        series[ext] = blank(ts_body)

        # Which series are bigint decides how their values must be formatted; a single
        # fractional value 422s an entire bigint batch.
        value_types = {i["externalId"]: (i.get("valueType") or "float").lower()
                       for i in ts_body.get("items", [])}
        rows = write_datapoints(wid, ext, value_types, args, pd, timeline, WELLS_DIR,
                                SENSOR_TS_SUFFIX)
        manifest_wells.append({
            "externalId": ext, "wellId": wid,
            "resources": len(assets_body.get("nodes", [])) + 1,
            "timeseries": len(ts_body.get("items", [])),
            "relations": len(rel_body.get("relations", [])),
            "rows": rows, "sensors": len(value_types),
        })
        print(f"[generate] well {wid}: {manifest_wells[-1]}", flush=True)

    if not manifest_wells:
        raise SystemExit("no wells generated — check --wells and the 3W download")

    (OUT_DIR / "assets.json").write_text(json.dumps(assets, indent=1) + "\n")
    (OUT_DIR / "timeseries.json").write_text(json.dumps(series, indent=1) + "\n")
    (OUT_DIR / "MANIFEST.json").write_text(json.dumps({
        "generatedAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "generator": "deploy/demo/generate/generate.py",
        "sdkCommit": sdk_commit(),
        "strideSeconds": args.stride, "rowsPerWell": args.rows,
        "datasetIdPlaceholder": DATASET_ID_PLACEHOLDER,
        "wells": manifest_wells,
        "source": {
            "name": "3W Dataset", "publisher": "Petrobras",
            "repository": "https://github.com/petrobras/3W",
            "license": "CC BY 4.0",
            "licenseUrl": "https://creativecommons.org/licenses/by/4.0/",
            "attribution": ("Vargas, R.E.V. et al. (2019). A realistic and public dataset "
                            "with rare undesirable real events in oil wells. Journal of "
                            "Petroleum Science and Engineering, 181, 106223."),
            "doi": "https://doi.org/10.1016/j.petrol.2019.106223",
        },
    }, indent=1) + "\n")
    print(f"[generate] wrote {OUT_DIR}")


def write_datapoints(wid, ext, value_types, args, pd, timeline, wells_dir, suffix_map):
    """Write datapoints/well-<id>.csv.gz — offsets in seconds plus one column per series.

    Offsets rather than absolute timestamps, because the seed anchors the history so it
    ends at ITS "now". Committing absolute times would put the demo in the past by however
    long the checkout has been sitting there.
    """
    df = pd.read_parquet(wells_dir / f"WELL-{wid:0>5}.parquet")
    if args.stride > 1:
        df = df.iloc[::args.stride]
    df = df.iloc[-args.rows:]

    # Same rebasing the backfill uses: the parquet is a concatenation of separate incident
    # files, so its raw index jumps months between them and repeats where they overlap.
    offsets = timeline.continuous_seconds(df.index)

    cols = {}
    for source_col, ts_suffix in suffix_map.items():
        if source_col not in df.columns:
            continue
        # SENSOR_TS_SUFFIX values already carry their leading underscore ("_pdg_pressure"),
        # so concatenate — inserting another one yields a "well_00003__glck_open" that
        # matches no provisioned series and silently writes a file with no data in it.
        ext_id = f"{ext}{ts_suffix}"
        if ext_id not in value_types:
            continue  # not provisioned for this well (column was entirely null)
        cols[ext_id] = df[source_col]

    if not cols:
        raise SystemExit(
            f"well {wid}: no parquet column mapped to a provisioned series. "
            f"provisioned={sorted(value_types)[:3]}... parquet={list(df.columns)[:3]}...")

    dest = OUT_DIR / "datapoints" / f"well-{wid:0>5}.csv.gz"
    with gzip.open(dest, "wt", newline="") as fh:
        w = csv.writer(fh)
        names = sorted(cols)
        w.writerow(["offset_s"] + names)
        for i, off in enumerate(offsets):
            row = [f"{off:.0f}"]
            for n in names:
                v = cols[n].iloc[i]
                if v != v:  # NaN — the API takes a gap, not a null
                    row.append("")
                elif value_types[n] == "bigint":
                    row.append(str(int(round(float(v)))))
                else:
                    row.append(f"{float(v):.6g}")
            w.writerow(row)
    return len(offsets)


def sdk_commit():
    """Short commit of the SDK the payloads were produced by, for MANIFEST.json provenance.

    Read from a dataplatform-rust-sdk checkout alongside this repository
    (https://github.com/IntelliStream-DataHub/dataplatform-rust-sdk). Fails soft to "unknown"
    rather than erroring: the provenance stamp is not worth failing a regeneration over.
    """
    try:
        return subprocess.run(
            ["git", "-C", str(Path(__file__).resolve().parents[4] / "dataplatform-rust-sdk"),
             "rev-parse", "--short", "HEAD"],
            capture_output=True, text=True, timeout=5).stdout.strip() or "unknown"
    except Exception:
        return "unknown"


if __name__ == "__main__":
    main()

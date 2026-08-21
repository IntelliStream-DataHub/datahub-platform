# Regenerating the committed demo data

**Maintainer tool.** Nobody needs this to *run* the demo — `./scripts/up.sh` replays the
payloads already committed under `deploy/demo/data`. Run it only to change what the demo
contains: different wells, more or less history, or a new asset model.

## Why the data is committed at all

Building the demo from source means downloading ~130 MB of Petrobras 3W parquet from GitHub
one file at a time (~25 minutes), parsing it with pandas/pyarrow, and rebuilding the asset
model. That work is deterministic — it produces the same result every time — so it happens
once, here, instead of on every machine that wants to look at DataHub. The committed result
is ~300 KB and seeds in seconds, with no network access at run time.

It also settles two traps before they can reach a user, both of which the SDK handles
silently and a hand-written payload would get wrong:

- `provisioning.py` asks for `value_type="DECIMAL"`, but the api only accepts
  `bigint, float, float32, numeric, decimal32, mixed, text`. The SDK normalises it to
  `float`; the committed payloads carry the normalised value.
- The valve-state series are `bigint` while the 3W `ESTADO-*` columns are doubles, and the
  api parses every point of a bigint series with `Long.parseLong` — one fractional value
  rejects the whole request. The committed CSVs store those columns pre-rounded.

## How it works

It runs the real provisioning code from the 3W demo repo through the real SDK, pointed
at a local capture server that records each request body and answers with a canned success.
So the committed payloads are the ones the SDK itself produces, not a guess at the wire
format. Nothing is provisioned anywhere and no stack, tenant or credentials are involved.

Acceptance by the api is proven later and for real: `demo.seed` reads its data back from
ClickHouse and fails loudly if it never arrives.

## Regenerating it (maintainers only)

`generate.py` imports its provisioning code (`demo_src.provisioning`, `demo_src.timeline`,
`demo_src.config` and friends) from a separate 3W demo repository that is **not public**, so
this step cannot be run from a clone of this repository alone. That is the point rather than
an oversight: the output is committed precisely so that nobody else ever has to run it.

With that repository checked out alongside this one, and its virtualenv (pandas, pyarrow,
the SDK):

```bash
cd deploy/demo/generate
../../../../3W_demo/.venv/bin/python generate.py --demo-repo ../../../../3W_demo
```

The first run downloads the 3W source data into the demo repo's own `.cache/` (~25 min for
three wells); later runs reuse it. That source data is public: Petrobras publishes the 3W
dataset at [github.com/petrobras/3W](https://github.com/petrobras/3W) under CC BY 4.0. What
is not public is the provisioning wrapper around it.

`generate.py` also stamps `MANIFEST.json` with the SDK commit the payloads were produced by,
which it reads from a
[dataplatform-rust-sdk](https://github.com/IntelliStream-DataHub/dataplatform-rust-sdk)
checkout sitting alongside this repository. That repository is public, and the lookup fails
soft: without it the field records `unknown` and nothing else changes.

| flag | default | meaning |
|---|---|---|
| `--wells` | `1 2 3` | which wells to generate |
| `--rows` | `5000` | timestamps kept per well, taken from the tail |
| `--stride` | `5` | keep every Nth row; the source is 1 Hz, so 5 gives a 5s cadence |

Defaults give roughly 7 hours of history per well and ~300 KB of committed data. Raising
either knob raises the repo's size and the seed's runtime roughly linearly — check
`git diff --stat` before committing.

## What it writes

```
deploy/demo/data/
  MANIFEST.json              provenance: 3W attribution + licence, SDK commit, counts
  assets.json                per well: the dataset, root, assets, relations and anomaly payloads
  timeseries.json            per well: the timeseries create payload
  datapoints/well-N.csv.gz   offset_seconds + one column per series
```

Datapoint offsets are **relative**, not absolute. The seed anchors them so history ends at
its own "now" — committing absolute timestamps would put the demo however far in the past
the checkout happens to be old.

Dataset ids are replaced with `__DATASET_ID__`, because the api assigns them at create time;
the seed substitutes its own.

The 3W dataset is CC BY 4.0, so redistributing this slice is fine. `MANIFEST.json` carries
the attribution, and the seeded dataset repeats it in its description and metadata.

"""Keep the seeded 3W wells moving: append live datapoints forever.

The seed lays down history ending at "now"; this continues from there, replaying the same
committed rows forward and looping when it runs out. Values repeat, the time axis never
does — so nothing is ever overwritten and a chart keeps growing to the right.

Long-running and restart-safe: on start it asks where each well's data currently ends and
resumes one step after that, so a restart neither duplicates nor leaves a gap.
"""
from __future__ import annotations

import os
import time
from datetime import datetime, timedelta, timezone

from demo.common import (
    demo_disabled, sdk_version, env_int, load_payloads, log, read_datapoints, wait_for_api, wait_for_org_claim,
)


def last_stamp(client, names):
    """Where this well's data currently ends, or None if it has none.

    Uses retrieve_latest_datapoints, which is served from Valkey first. That is exactly
    right here — the question is "where did the last writer stop", not "did it reach
    storage" — and it is the reason this is a different call from the seed's confirmation.
    """
    try:
        got = client.timeseries.retrieve_latest_datapoints(names)
    except Exception as e:
        log(f"  could not read the latest datapoint ({str(e)[:100]}); starting from now")
        return None
    stamps = [dp.timestamp for c in got or [] for dp in (c.get_datapoints() or [])]
    if not stamps:
        return None
    newest = max(stamps)
    if newest.tzinfo is None:
        newest = newest.replace(tzinfo=timezone.utc)
    return newest


class WellFeed:
    """One well's cursor: which row comes next, and at what timestamp."""

    def __init__(self, client, well, step):
        self.well_id = well["wellId"]
        self.names, self.rows = read_datapoints(self.well_id)
        self.step = timedelta(seconds=step)
        self.refs = {t.external_id: t for t in client.timeseries.by_ids(self.names)}
        self.row = 0
        if not self.refs:
            # Not seeded yet. Stay quiet: the caller retries every tick, and announcing it
            # each time buries the log in a message that is not yet a problem.
            self.next_ts = None
            return
        end = last_stamp(client, list(self.refs)) or datetime.now(timezone.utc)
        self.next_ts = end + self.step
        log(f"well {self.well_id}: {len(self.refs)} series, resuming at {self.next_ts:%H:%M:%S}")

    def due(self, now, cap):
        """Collect the rows whose time has come, up to `cap`.

        Driven by the wall clock rather than "N rows per tick" so that a slow api or a
        paused container is caught up on afterwards instead of leaving data-time drifting
        permanently behind real time.
        """
        batch = []
        while self.next_ts <= now and len(batch) < cap:
            batch.append((self.next_ts, self.rows[self.row][1]))
            self.next_ts += self.step
            self.row += 1
            if self.row >= len(self.rows):
                self.row = 0  # loop the pass; timestamps keep advancing regardless
        return batch

    def push(self, client, batch):
        sent = 0
        for col, name in enumerate(self.names):
            ref = self.refs.get(name)
            if ref is None:
                continue
            stamps, values = [], []
            for stamp, row in batch:
                v = row[col]
                if v is not None:
                    stamps.append(stamp)
                    values.append(v)
            if stamps:
                client.timeseries.insert_from_lists(stamps, values, ref)
                sent += len(stamps)
        return sent


def main():
    os.environ.setdefault("DEMO_ROLE", "demo-feed")
    if demo_disabled():
        log("DEMO_ENABLED is false — nothing to do")
        return
    _, _, manifest, wells = load_payloads()
    interval = env_int("DEMO_FEED_INTERVAL", 5)
    cap = env_int("DEMO_FEED_MAX_ROWS_PER_TICK", 60)
    step = env_int("DEMO_FEED_STEP_SECONDS", manifest.get("strideSeconds", 5))

    wait_for_org_claim()
    client = wait_for_api()

    # The seed runs in a separate container and may still be working — compose can only
    # order container *starts*, not completion. So wells are picked up as their series
    # appear, indefinitely and on every tick, rather than in a bounded startup loop: a
    # feeder that gave up early would sit there logging happily while writing nothing,
    # which is exactly what a bounded loop did the first time this ran from cold.
    log(f"feeding every {interval}s at {step}s per row; waiting for seeded wells")
    feeds = {}
    announced = set()
    while True:
        for well in wells:
            wid = well["wellId"]
            if wid in feeds:
                continue
            try:
                feed = WellFeed(client, well, step)
            except Exception as e:
                if wid not in announced:
                    announced.add(wid)
                    log(f"well {wid}: waiting for the seed ({str(e)[:80]})")
                continue
            if feed.refs:
                feeds[wid] = feed

        now = datetime.now(timezone.utc)
        total = 0
        for feed in feeds.values():
            batch = feed.due(now, cap)
            if batch:
                try:
                    total += feed.push(client, batch)
                except Exception as e:
                    log(f"well {feed.well_id}: insert failed, will retry ({str(e)[:100]})")
        if total:
            log(f"pushed {total} datapoints across {len(feeds)} well(s)")
        time.sleep(interval)


if __name__ == "__main__":
    main()

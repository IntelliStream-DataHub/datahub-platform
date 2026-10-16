-- Dispatch looks for pending runs flow by flow: for each flow the next few it may start, earliest
-- window first. That reads the front of each flow's queue from this index, so the cost follows the
-- number of flows and the free room, not the size of a backlog (a backlog of 10 000 runs of one flow
-- at its limit is never scanned). It replaces run_pending_idx, whose order spans all flows and which
-- nothing reads any more; the board's count of queued runs per flow reads this one too.
CREATE INDEX run_pending_flow_idx ON flow.run (flow_id, window_start, created_at) WHERE status = 'PENDING';
DROP INDEX flow.run_pending_idx;

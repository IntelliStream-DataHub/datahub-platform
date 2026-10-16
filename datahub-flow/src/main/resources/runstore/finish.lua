-- ARGV: id, owner, attempt, kind (complete, retry or release), status, back-off ms, event owed
-- (0/1), then field/value pairs of the outcome. Only the owner of the current attempt may finish
-- it: 1 if it did, 0 if the run is no longer its.
local id, owner, attempt, kind, status = ARGV[1], ARGV[2], tonumber(ARGV[3]), ARGV[4], ARGV[5]
local backoff, owed = tonumber(ARGV[6]), ARGV[7]
local rk = runkey(id)
local m = redis.call('HMGET', rk, 'status', 'owner', 'attempt', 'flow_id', 'preview', 'window_start_ms', 'score')
if m[1] ~= 'RUNNING' or m[2] ~= owner or tonumber(m[3]) ~= attempt then return 0 end
local flow, preview = m[4], (m[5] == '1')
local now = now_ms()
redis.call('ZREM', 'flow:leases', id)
redis.call('ZREM', 'flow:deadlines', id)
if not preview then stopped_running(flow, id) end
for i = 8, #ARGV, 2 do
  redis.call('HSET', rk, ARGV[i], ARGV[i + 1])
end
redis.call('HDEL', rk, 'owner', 'lease_ms')
if kind == 'complete' then
  redis.call('HSET', rk, 'status', status, 'finished_ms', now, 'failure_event_owed', owed)
  if owed == '1' then redis.call('SADD', 'flow:owed', id) end
  ended(flow, id, status, redis.call('HGET', rk, 'error_code'), preview, m[6], now)
elseif kind == 'retry' then
  redis.call('HSET', rk, 'status', 'PENDING', 'attempt', attempt + 1, 'not_before_ms', now + backoff)
  redis.call('HDEL', rk, 'started_ms')
  make_pending(flow, id, m[7], now + backoff, now)
  redis.call('HINCRBY', meta(flow), 'retrying', 1)
else
  redis.call('HSET', rk, 'status', 'PENDING')
  redis.call('HDEL', rk, 'started_ms')
  make_pending(flow, id, m[7], 0, now)
  if attempt > 1 then redis.call('HINCRBY', meta(flow), 'retrying', 1) end
end
if not preview then refresh_ready(flow) end
return 1

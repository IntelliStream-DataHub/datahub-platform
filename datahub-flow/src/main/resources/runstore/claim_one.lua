-- ARGV: id, owner, lease ms, grace ms. Takes this one run, for a manual start (which the flow's
-- limit still governs) or a preview (which nothing governs). 1 taken, 0 not taken, 2 cancelled
-- because its flow is switched off.
local id, owner, lease, grace = ARGV[1], ARGV[2], tonumber(ARGV[3]), tonumber(ARGV[4])
local rk = runkey(id)
local m = redis.call('HMGET', rk, 'status', 'cancel_requested', 'preview', 'flow_id', 'attempt', 'timeout_ms')
if m[1] ~= 'PENDING' or m[2] == '1' then return 0 end
local now = now_ms()
local flow = m[4]
if m[3] ~= '1' then
  if not redis.call('ZSCORE', q(flow), id) then return 0 end
  local g = redis.call('HMGET', meta(flow), 'enabled', 'deployed', 'max_concurrent', 'running')
  if (g[1] == '0') or (g[2] == '0') then
    redis.call('ZREM', q(flow), id)
    if (tonumber(m[5]) or 1) > 1 then redis.call('HINCRBY', meta(flow), 'retrying', -1) end
    redis.call('HSET', rk, 'status', 'CANCELLED', 'error_code', 'FLOW_DISABLED',
      'error', 'The flow was switched off before the run could start.', 'finished_ms', now)
    ended(flow, id, 'CANCELLED', 'FLOW_DISABLED', false, nil, now)
    refresh_ready(flow)
    return 2
  end
  if (tonumber(g[4]) or 0) >= (tonumber(g[3]) or 1) then return 0 end
  redis.call('ZREM', q(flow), id)
  started_running(flow, id)
  if (tonumber(m[5]) or 1) > 1 then redis.call('HINCRBY', meta(flow), 'retrying', -1) end
end
redis.call('HSET', rk, 'status', 'RUNNING', 'owner', owner, 'started_ms', now, 'lease_ms', now + lease)
redis.call('HDEL', rk, 'finished_ms')
redis.call('ZADD', 'flow:leases', now + lease, id)
redis.call('ZADD', 'flow:deadlines', now + (tonumber(m[6]) or 0) + grace, id)
if m[3] ~= '1' then refresh_ready(flow) end
return 1

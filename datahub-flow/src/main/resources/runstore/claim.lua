-- ARGV: owner, lease ms, most runs to take, grace ms past a run's timeout. Takes the earliest
-- windows of the flows that have room, and cancels the runs of flows switched off. The ids taken.
local owner, lease, n, grace = ARGV[1], tonumber(ARGV[2]), tonumber(ARGV[3]), tonumber(ARGV[4])
local now = now_ms()
promote(now)
local claimed = {}
local guard = 0
while #claimed < n and guard < n * 10 + 100 do
  guard = guard + 1
  local flows = redis.call('ZRANGE', 'flow:ready', 0, 0)
  if #flows == 0 then break end
  local flow = flows[1]
  local popped = redis.call('ZPOPMIN', q(flow), 1)
  if #popped == 0 then
    redis.call('ZREM', 'flow:ready', flow)
  else
    local id, score = popped[1], popped[2]
    local rk = runkey(id)
    local m = redis.call('HMGET', meta(flow), 'enabled', 'deployed', 'max_concurrent', 'running')
    local usable = (m[1] ~= '0') and (m[2] ~= '0')
    local attempt = tonumber(redis.call('HGET', rk, 'attempt')) or 1
    if not usable then
      if attempt > 1 then redis.call('HINCRBY', meta(flow), 'retrying', -1) end
      redis.call('HSET', rk, 'status', 'CANCELLED', 'error_code', 'FLOW_DISABLED',
        'error', 'The flow was switched off before the run could start.', 'finished_ms', now)
      ended(flow, id, 'CANCELLED', 'FLOW_DISABLED', false, nil, now)
    elseif (tonumber(m[4]) or 0) >= (tonumber(m[3]) or 1) then
      redis.call('ZADD', q(flow), score, id)
      redis.call('ZREM', 'flow:ready', flow)
    else
      redis.call('HSET', rk, 'status', 'RUNNING', 'owner', owner, 'started_ms', now, 'lease_ms', now + lease)
      redis.call('HDEL', rk, 'finished_ms')
      redis.call('ZADD', 'flow:leases', now + lease, id)
      redis.call('ZADD', 'flow:deadlines', now + (tonumber(redis.call('HGET', rk, 'timeout_ms')) or 0) + grace, id)
      started_running(flow, id)
      if attempt > 1 then redis.call('HINCRBY', meta(flow), 'retrying', -1) end
      claimed[#claimed + 1] = id
    end
    refresh_ready(flow)
  end
end
return claimed

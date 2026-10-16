-- ARGV: most runs, back-off base ms, back-off cap ms. Runs whose lease has run out go back to
-- pending for another attempt, or fail when their attempts are used up. For each: id, what
-- happened (REQUEUED or FAILED), the attempt it was.
local limit, base, cap = tonumber(ARGV[1]), tonumber(ARGV[2]), tonumber(ARGV[3])
local now = now_ms()
local ids = redis.call('ZRANGEBYSCORE', 'flow:leases', '-inf', '(' .. now, 'LIMIT', 0, limit)
local out = {}
for _, id in ipairs(ids) do
  local rk = runkey(id)
  local m = redis.call('HMGET', rk, 'status', 'attempt', 'max_attempts', 'flow_id', 'preview', 'window_start_ms', 'score')
  redis.call('ZREM', 'flow:leases', id)
  redis.call('ZREM', 'flow:deadlines', id)
  if m[1] == 'RUNNING' then
    local attempt, flow, preview = tonumber(m[2]), m[4], (m[5] == '1')
    if not preview then stopped_running(flow, id) end
    redis.call('HDEL', rk, 'owner', 'lease_ms')
    if attempt < tonumber(m[3]) and not preview then
      local wait = math.min(base * (2 ^ math.min(attempt - 1, 20)), cap)
      redis.call('HSET', rk, 'status', 'PENDING', 'attempt', attempt + 1, 'not_before_ms', now + wait,
        'error_code', 'LEASE_LOST',
        'error', 'The instance running attempt ' .. attempt .. ' stopped renewing its lease.')
      redis.call('HDEL', rk, 'started_ms')
      make_pending(flow, id, m[7], now + wait, now)
      redis.call('HINCRBY', meta(flow), 'retrying', 1)
      out[#out + 1] = id; out[#out + 1] = 'REQUEUED'; out[#out + 1] = attempt
    else
      local owed = preview and '0' or '1'
      redis.call('HSET', rk, 'status', 'FAILED', 'error_code', 'LEASE_LOST',
        'error', 'The instance running the last attempt stopped renewing its lease.',
        'finished_ms', now, 'failure_event_owed', owed)
      if owed == '1' then redis.call('SADD', 'flow:owed', id) end
      ended(flow, id, 'FAILED', 'LEASE_LOST', preview, m[6], now)
      out[#out + 1] = id; out[#out + 1] = 'FAILED'; out[#out + 1] = attempt
    end
    if not preview then refresh_ready(flow) end
  end
end
return out

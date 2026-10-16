-- ARGV: id, flow, preview (0/1), window start ms or '', not-before ms (0: now), then field/value pairs.
-- Makes the run pending, unless one with this id exists: so a repeat does nothing. 1 if made.
local id, flow, preview, window_ms, not_before = ARGV[1], ARGV[2], ARGV[3], ARGV[4], tonumber(ARGV[5])
local rk = runkey(id)
if redis.call('EXISTS', rk) == 1 then return 0 end
local now = now_ms()
local seq = redis.call('INCR', 'flow:seq')
local base = tonumber(window_ms)
if not base then base = now end
local score = string.format('%.0f', base * 4096 + (seq % 4096))
redis.call('HSET', rk, 'id', id, 'flow_id', flow, 'status', 'PENDING', 'attempt', '1', 'created_ms', now,
  'score', score, 'not_before_ms', not_before, 'cancel_requested', '0', 'failure_event_owed', '0', 'preview', preview)
for i = 6, #ARGV, 2 do
  redis.call('HSET', rk, ARGV[i], ARGV[i + 1])
end
redis.call('ZADD', 'flow:live', now, id)
if preview == '1' then return 1 end
make_pending(flow, id, score, not_before, now)
refresh_ready(flow)
return 1

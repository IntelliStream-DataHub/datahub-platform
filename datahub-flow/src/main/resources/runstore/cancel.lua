-- ARGV: id, error code, error text. Ends a run that is still pending. 1 if it did.
local id, code, text = ARGV[1], ARGV[2], ARGV[3]
local rk = runkey(id)
local m = redis.call('HMGET', rk, 'status', 'flow_id', 'attempt', 'preview')
if m[1] ~= 'PENDING' then return 0 end
local flow, now = m[2], now_ms()
redis.call('ZREM', q(flow), id)
redis.call('ZREM', d(flow), id)
if m[4] ~= '1' and (tonumber(m[3]) or 1) > 1 then redis.call('HINCRBY', meta(flow), 'retrying', -1) end
redis.call('HSET', rk, 'status', 'CANCELLED', 'error_code', code, 'error', text, 'finished_ms', now)
ended(flow, id, 'CANCELLED', code, m[4] == '1', nil, now)
if m[4] ~= '1' then refresh_ready(flow) end
return 1

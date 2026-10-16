-- ARGV: most runs. Asks the runs that are past their timeout and grace to stop: the owner sees it
-- at its next heartbeat. How many were asked.
local ids = redis.call('ZRANGEBYSCORE', 'flow:deadlines', '-inf', '(' .. now_ms(), 'LIMIT', 0, tonumber(ARGV[1]))
local asked = 0
for _, id in ipairs(ids) do
  redis.call('ZREM', 'flow:deadlines', id)
  local rk = runkey(id)
  if redis.call('HGET', rk, 'status') == 'RUNNING' and redis.call('HGET', rk, 'cancel_requested') ~= '1' then
    redis.call('HSET', rk, 'cancel_requested', '1')
    asked = asked + 1
  end
end
return asked

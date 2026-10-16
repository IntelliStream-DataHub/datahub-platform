-- ARGV: seconds to keep a finished run, run id. The run's lifecycle event is written (or can never
-- be): it no longer owes it, and once ClickHouse has it too it may expire.
local id, ttl = ARGV[2], tonumber(ARGV[1])
local rk = runkey(id)
redis.call('SREM', 'flow:owed', id)
if redis.call('EXISTS', rk) == 1 then
  redis.call('HSET', rk, 'failure_event_owed', '0')
  if not redis.call('ZSCORE', 'flow:unarchived', id) then redis.call('EXPIRE', rk, ttl) end
end
return 1

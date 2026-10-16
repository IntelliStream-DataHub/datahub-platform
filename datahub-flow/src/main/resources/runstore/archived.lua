-- ARGV: seconds to keep a finished run, then run ids that ClickHouse has. A run that still owes its
-- lifecycle event is kept until that is written (event_paid.lua).
local ttl = tonumber(ARGV[1])
for i = 2, #ARGV do
  local id = ARGV[i]
  redis.call('ZREM', 'flow:unarchived', id)
  if redis.call('HGET', runkey(id), 'failure_event_owed') ~= '1' then
    redis.call('EXPIRE', runkey(id), ttl)
  end
end
return #ARGV - 1

-- ARGV: flow. Forgets a flow's runs that are pending or running (the flow was deleted); a run
-- of it that is still executing finds it is no longer its own when it ends.
local flow = ARGV[1]
local function forget(id)
  redis.call('DEL', runkey(id))
  redis.call('ZREM', 'flow:leases', id)
  redis.call('ZREM', 'flow:deadlines', id)
  redis.call('ZREM', 'flow:unarchived', id)
  redis.call('SREM', 'flow:owed', id)
end
local count = 0
for _, key in ipairs({ q(flow), d(flow) }) do
  for _, id in ipairs(redis.call('ZRANGE', key, 0, -1)) do forget(id); count = count + 1 end
  redis.call('DEL', key)
end
for _, id in ipairs(redis.call('SMEMBERS', running_set(flow))) do forget(id); count = count + 1 end
redis.call('DEL', running_set(flow), meta(flow), 'flow:last:' .. flow, 'flow:failed-windows:' .. flow)
redis.call('ZREM', 'flow:ready', flow)
redis.call('ZREM', 'flow:due', flow)
return count

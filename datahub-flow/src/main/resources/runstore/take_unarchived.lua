-- ARGV: most runs, seconds they stay out of sight. Hands out finished runs that are not in ClickHouse
-- yet to one archiver, and hides them from the others for a while: if it dies the runs reappear.
local most, hide = tonumber(ARGV[1]), tonumber(ARGV[2]) * 1000
local now = now_ms()
local ids = redis.call('ZRANGEBYSCORE', 'flow:unarchived', '-inf', now, 'LIMIT', 0, most)
for _, id in ipairs(ids) do
  redis.call('ZADD', 'flow:unarchived', now + hide, id)
end
return ids

-- ARGV: owner, lease ms, then run ids. Renews the leases this owner still holds. For each: id,
-- whether a cancel was asked for (0/1), the lease left in ms. A run missing from the answer is no
-- longer the owner's.
local owner, lease = ARGV[1], tonumber(ARGV[2])
local now = now_ms()
local out = {}
for i = 3, #ARGV do
  local id = ARGV[i]
  local rk = runkey(id)
  local m = redis.call('HMGET', rk, 'status', 'owner', 'cancel_requested')
  if m[1] == 'RUNNING' and m[2] == owner then
    redis.call('ZADD', 'flow:leases', now + lease, id)
    redis.call('HSET', rk, 'lease_ms', now + lease)
    out[#out + 1] = id
    out[#out + 1] = m[3] or '0'
    out[#out + 1] = lease
  end
end
return out

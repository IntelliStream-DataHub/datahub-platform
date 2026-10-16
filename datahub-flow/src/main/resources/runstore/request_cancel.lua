-- ARGV: id. Asks a running run to stop, which its owner sees at its next heartbeat. 1 if it was running.
local rk = runkey(ARGV[1])
if redis.call('HGET', rk, 'status') ~= 'RUNNING' then return 0 end
redis.call('HSET', rk, 'cancel_requested', '1')
return 1

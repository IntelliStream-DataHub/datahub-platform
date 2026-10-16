-- ARGV: flow, external id, data set id, enabled (0/1), deployed (0/1), most runs at once. What
-- dispatch needs to know of a flow, copied from Postgres.
local flow = ARGV[1]
redis.call('HSET', meta(flow), 'external_id', ARGV[2], 'data_set_id', ARGV[3], 'enabled', ARGV[4],
  'deployed', ARGV[5], 'max_concurrent', ARGV[6])
refresh_ready(flow)
return 1

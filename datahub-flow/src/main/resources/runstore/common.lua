-- Shared by every script of the run store (RunStore.java prepends this file to each). See
-- RUN_STORE.md for the keys. Times are epoch milliseconds from the server's own clock.

local function now_ms()
  local t = redis.call('TIME')
  return tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
end

local function runkey(id) return 'flow:run:' .. id end
local function q(flow) return 'flow:q:' .. flow end            -- pending, ready to start
local function d(flow) return 'flow:d:' .. flow end            -- pending, waiting out a back-off
local function meta(flow) return 'flow:meta:' .. flow end
local function running_set(flow) return 'flow:running:' .. flow end

-- A flow is in flow:ready exactly when it has a run in its queue and room to start one: a flow
-- under its limit, or one switched off or not deployed, whose runs are to be cancelled and so
-- need no room. A flow nobody has described yet counts as on, with a limit of one.
local function refresh_ready(flow)
  local head = redis.call('ZRANGE', q(flow), 0, 0, 'WITHSCORES')
  if #head == 0 then
    redis.call('ZREM', 'flow:ready', flow)
    return
  end
  local m = redis.call('HMGET', meta(flow), 'enabled', 'deployed', 'max_concurrent', 'running')
  local usable = (m[1] ~= '0') and (m[2] ~= '0')
  local room = 1
  if usable then
    room = (tonumber(m[3]) or 1) - (tonumber(m[4]) or 0)
  end
  if room > 0 then
    redis.call('ZADD', 'flow:ready', head[2], flow)
  else
    redis.call('ZREM', 'flow:ready', flow)
  end
end

-- Puts a pending run where it waits: the flow's queue, or its back-off set until its time.
local function make_pending(flow, id, score, not_before, now)
  if not_before > now then
    redis.call('ZADD', d(flow), not_before, id)
    local first = redis.call('ZRANGE', d(flow), 0, 0, 'WITHSCORES')
    redis.call('ZADD', 'flow:due', first[2], flow)
  else
    redis.call('ZADD', q(flow), score, id)
  end
end

-- Moves the runs whose back-off is over into their flows' queues. A bounded amount per call, so
-- one call never holds the server for long; the next call goes on.
local function promote(now)
  local budget = 500
  local flows = redis.call('ZRANGEBYSCORE', 'flow:due', '-inf', now, 'LIMIT', 0, 100)
  for _, flow in ipairs(flows) do
    if budget <= 0 then break end
    local ids = redis.call('ZRANGEBYSCORE', d(flow), '-inf', now, 'LIMIT', 0, budget)
    for _, id in ipairs(ids) do
      redis.call('ZREM', d(flow), id)
      local score = redis.call('HGET', runkey(id), 'score')
      if score then redis.call('ZADD', q(flow), score, id) end
      budget = budget - 1
    end
    local nxt = redis.call('ZRANGE', d(flow), 0, 0, 'WITHSCORES')
    if #nxt == 0 then
      redis.call('ZREM', 'flow:due', flow)
    else
      redis.call('ZADD', 'flow:due', nxt[2], flow)
    end
    refresh_ready(flow)
  end
end

-- A run that ended: waits to be archived, and is the flow's latest, and failed windows are kept.
local function ended(flow, id, status, error_code, preview, window_start, now)
  redis.call('ZADD', 'flow:unarchived', now, id)
  if preview then return end
  if status == 'FAILED' and window_start then
    redis.call('SADD', 'flow:failed-windows:' .. flow, window_start)
  elseif status == 'SUCCEEDED' and window_start then
    redis.call('SREM', 'flow:failed-windows:' .. flow, window_start)
  end
  if status == 'FAILED' or status == 'SUCCEEDED' then
    redis.call('HSET', 'flow:last:' .. flow, 'id', redis.call('HGET', runkey(id), 'id'), 'status', status,
      'error_code', error_code or '', 'finished_ms', now)
  end
end

-- One less running run of the flow, which may open room for the next.
local function stopped_running(flow, id)
  local left = redis.call('HINCRBY', meta(flow), 'running', -1)
  if left < 0 then redis.call('HSET', meta(flow), 'running', 0) end
  redis.call('SREM', running_set(flow), id)
end

local function started_running(flow, id)
  redis.call('HINCRBY', meta(flow), 'running', 1)
  redis.call('SADD', running_set(flow), id)
end

local MAX_SAFE = 9007199254740991

local function integer_arg(index, name, minimum)
  local value = tonumber(ARGV[index])
  if not value or value ~= math.floor(value) or value < minimum or value >= MAX_SAFE then
    error('invalid ' .. name)
  end
  return value
end

local function redis_type(key)
  local reply = redis.call('TYPE', key)
  if type(reply) == 'table' then
    return reply['ok']
  end
  return reply
end

local function stored_integer(raw, name, minimum, maximum)
  local value = tonumber(raw)
  if not value or value ~= math.floor(value) or value < minimum or value > maximum then
    error('corrupt ' .. name)
  end
  return value
end

local limit = integer_arg(1, 'limit', 1)
local windowMs = integer_arg(2, 'windowMs', 1)
local minimumPermits = integer_arg(3, 'minimumPermits', 1)
local desiredPermits = integer_arg(4, 'desiredPermits', 1)
if minimumPermits > desiredPermits or desiredPermits > limit or desiredPermits > 100 then
  error('invalid permit reservation')
end

local eventsType = redis_type(KEYS[1])
local metaType = redis_type(KEYS[2])
if eventsType ~= 'none' and eventsType ~= 'zset' then
  error('wrong sliding log events key type')
end
if metaType ~= 'none' and metaType ~= 'hash' then
  error('wrong sliding log metadata key type')
end

local eventCountBefore = redis.call('ZCARD', KEYS[1])
local metaSize = redis.call('HLEN', KEYS[2])
local lastRaw = redis.call('HGET', KEYS[2], 'last_ms')
local sequenceRaw = redis.call('HGET', KEYS[2], 'sequence')
if eventCountBefore > 0 and metaSize == 0 then
  error('events exist without metadata')
end
if metaSize ~= 0 and (metaSize ~= 2 or not lastRaw or not sequenceRaw) then
  error('corrupt sliding log metadata')
end

local redisTime = redis.call('TIME')
local redisNowMs = tonumber(redisTime[1]) * 1000 + math.floor(tonumber(redisTime[2]) / 1000)
if redisNowMs >= MAX_SAFE then
  error('unsafe Redis time')
end

local lastMs = redisNowMs
local sequence = 0
if metaSize ~= 0 then
  lastMs = stored_integer(lastRaw, 'last_ms', 0, MAX_SAFE - 1)
  sequence = stored_integer(sequenceRaw, 'sequence', 0, MAX_SAFE - 1)
end
if sequence + desiredPermits >= MAX_SAFE then
  error('unsafe sequence')
end
local effectiveNowMs = math.max(redisNowMs, lastMs)
if effectiveNowMs + windowMs + 2000 >= MAX_SAFE then
  error('unsafe sliding log expiry timestamp')
end
if effectiveNowMs > lastMs then
  sequence = 0
end

local cutoff = effectiveNowMs - windowMs
local current = redis.call('ZCOUNT', KEYS[1], '(' .. string.format('%.0f', cutoff), '+inf')
if current > limit then
  error('corrupt sliding log count')
end
redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', string.format('%.0f', cutoff))
if current == 0 then
  sequence = 0
end

local available = limit - current
local allowed = 0
local reserved = 0
local retryAfterMs = 0
if available >= minimumPermits then
  allowed = 1
  reserved = math.min(desiredPermits, available)
  local zaddArgs = {KEYS[1]}
  for i = 1, reserved do
    table.insert(zaddArgs, string.format('%.0f', effectiveNowMs))
    table.insert(zaddArgs, string.format('%.0f:%.0f', effectiveNowMs, sequence + i))
  end
  redis.call('ZADD', unpack(zaddArgs))
  sequence = sequence + reserved
  current = current + reserved
  available = limit - current
else
  local neededExpirations = current + minimumPermits - limit
  local oldest = redis.call('ZRANGE', KEYS[1], neededExpirations - 1, neededExpirations - 1, 'WITHSCORES')
  if #oldest ~= 2 then
    error('unable to calculate sliding log retry')
  end
  retryAfterMs = math.floor(tonumber(oldest[2]) + windowMs - effectiveNowMs)
  if retryAfterMs < 1 then
    retryAfterMs = 1
  end
end

local resetAfterMs = 0
if current > 0 then
  local newest = redis.call('ZREVRANGE', KEYS[1], 0, 0, 'WITHSCORES')
  if #newest ~= 2 then
    error('unable to calculate sliding log reset')
  end
  resetAfterMs = math.max(0, math.floor(tonumber(newest[2]) + windowMs - effectiveNowMs))
end

redis.call('HSET', KEYS[2], 'last_ms', string.format('%.0f', effectiveNowMs),
  'sequence', string.format('%.0f', sequence))
if current > 0 then
  redis.call('PEXPIRE', KEYS[1], string.format('%.0f', windowMs + 1000))
end
redis.call('PEXPIRE', KEYS[2], string.format('%.0f', windowMs + 2000))

local reservationValidForMs = 0
if allowed == 1 then
  reservationValidForMs = windowMs
end
return {allowed, reserved, available, retryAfterMs, resetAfterMs,
  reservationValidForMs, effectiveNowMs}

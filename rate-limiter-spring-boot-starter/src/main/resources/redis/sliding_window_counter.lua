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
if minimumPermits > desiredPermits or desiredPermits > limit then
  error('invalid permit reservation')
end
if limit * windowMs >= MAX_SAFE then
  error('unsafe numeric product')
end
if redis_type(KEYS[1]) ~= 'none' and redis_type(KEYS[1]) ~= 'hash' then
  error('wrong sliding counter key type')
end

local stateSize = redis.call('HLEN', KEYS[1])
local startRaw = redis.call('HGET', KEYS[1], 'current_start_ms')
local currentRaw = redis.call('HGET', KEYS[1], 'current_count')
local previousRaw = redis.call('HGET', KEYS[1], 'previous_count')
local lastRaw = redis.call('HGET', KEYS[1], 'last_ms')
if stateSize ~= 0 and (stateSize ~= 4 or not startRaw or not currentRaw or not previousRaw or not lastRaw) then
  error('corrupt sliding counter state')
end

local redisTime = redis.call('TIME')
local redisNowMs = tonumber(redisTime[1]) * 1000 + math.floor(tonumber(redisTime[2]) / 1000)
if redisNowMs >= MAX_SAFE then
  error('unsafe Redis time')
end

local currentStart = math.floor(redisNowMs / windowMs) * windowMs
local currentCount = 0
local previousCount = 0
local lastMs = redisNowMs
if stateSize ~= 0 then
  currentStart = stored_integer(startRaw, 'current_start_ms', 0, MAX_SAFE - 1)
  currentCount = stored_integer(currentRaw, 'current_count', 0, limit)
  previousCount = stored_integer(previousRaw, 'previous_count', 0, limit)
  lastMs = stored_integer(lastRaw, 'last_ms', 0, MAX_SAFE - 1)
  if currentStart > lastMs or lastMs >= currentStart + windowMs then
    error('inconsistent sliding counter timestamps')
  end
end

local effectiveNowMs = math.max(redisNowMs, lastMs)
if effectiveNowMs + 2 * windowMs + 1000 >= MAX_SAFE then
  error('unsafe sliding counter expiry timestamp')
end
local bucketStart = math.floor(effectiveNowMs / windowMs) * windowMs
if bucketStart > currentStart then
  local bucketsElapsed = math.floor((bucketStart - currentStart) / windowMs)
  if bucketsElapsed == 1 then
    previousCount = currentCount
  else
    previousCount = 0
  end
  currentCount = 0
  currentStart = bucketStart
end

local elapsed = effectiveNowMs - currentStart
local capacityNumerator = limit * windowMs
local weightedNumerator = previousCount * (windowMs - elapsed) + currentCount * windowMs
local available = math.floor((capacityNumerator - weightedNumerator) / windowMs)
available = math.max(0, math.min(limit, available))

local allowed = 0
local reserved = 0
local retryAfterMs = 0
if available >= minimumPermits then
  allowed = 1
  reserved = math.min(desiredPermits, available)
  currentCount = currentCount + reserved
  weightedNumerator = previousCount * (windowMs - elapsed) + currentCount * windowMs
  available = math.floor((capacityNumerator - weightedNumerator) / windowMs)
  available = math.max(0, math.min(limit, available))
else
  local untilBoundary = windowMs - elapsed
  local function permits_fit(delay)
    local futureNumerator
    if delay <= untilBoundary then
      futureNumerator = previousCount * (windowMs - elapsed - delay) + currentCount * windowMs
    else
      local afterBoundary = delay - untilBoundary
      futureNumerator = currentCount * math.max(0, windowMs - afterBoundary)
    end
    return futureNumerator + minimumPermits * windowMs <= capacityNumerator
  end
  local low = 1
  local high = 2 * windowMs - elapsed
  while low < high do
    local mid = math.floor((low + high) / 2)
    if permits_fit(mid) then
      high = mid
    else
      low = mid + 1
    end
  end
  retryAfterMs = low
end

local resetAfterMs = 0
if currentCount > 0 then
  resetAfterMs = 2 * windowMs - elapsed
elseif previousCount > 0 then
  resetAfterMs = windowMs - elapsed
end

redis.call('HSET', KEYS[1], 'current_start_ms', string.format('%.0f', currentStart),
  'current_count', string.format('%.0f', currentCount),
  'previous_count', string.format('%.0f', previousCount),
  'last_ms', string.format('%.0f', effectiveNowMs))
redis.call('PEXPIRE', KEYS[1], string.format('%.0f', 2 * windowMs + 1000))

local reservationValidForMs = 0
if allowed == 1 then
  reservationValidForMs = 2 * windowMs - elapsed
end
return {allowed, reserved, available, retryAfterMs, resetAfterMs,
  reservationValidForMs, effectiveNowMs}

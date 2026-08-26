local MAX_SAFE = 9007199254740991

local function integer_arg(index, name, minimum)
  local raw = ARGV[index]
  local value = tonumber(raw)
  if not value or value ~= math.floor(value) or value < minimum or value >= MAX_SAFE then
    error("invalid " .. name)
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
    error("corrupt " .. name)
  end
  return value
end

local function ceil_div(a, b)
  if a == 0 then
    return 0
  end
  return math.floor((a - 1) / b) + 1
end

local capacity = integer_arg(1, 'capacity', 1)
local refillTokens = integer_arg(2, 'refillTokens', 1)
local refillPeriodMs = integer_arg(3, 'refillPeriodMs', 1)
local minimumPermits = integer_arg(4, 'minimumPermits', 1)
local desiredPermits = integer_arg(5, 'desiredPermits', 1)
if minimumPermits > desiredPermits or desiredPermits > capacity then
  error('invalid permit reservation')
end

local capacityUnits = capacity * refillPeriodMs
if capacityUnits >= MAX_SAFE or refillTokens * refillPeriodMs >= MAX_SAFE then
  error('unsafe numeric product')
end
if redis_type(KEYS[1]) ~= 'none' and redis_type(KEYS[1]) ~= 'hash' then
  error('wrong token bucket key type')
end

local stateSize = redis.call('HLEN', KEYS[1])
local balanceRaw = redis.call('HGET', KEYS[1], 'balance_units')
local lastRaw = redis.call('HGET', KEYS[1], 'last_ms')
if stateSize ~= 0 and (stateSize ~= 2 or not balanceRaw or not lastRaw) then
  error('corrupt token bucket state')
end

local redisTime = redis.call('TIME')
local redisNowMs = tonumber(redisTime[1]) * 1000 + math.floor(tonumber(redisTime[2]) / 1000)
if redisNowMs >= MAX_SAFE then
  error('unsafe Redis time')
end

local balance
local lastMs
if stateSize == 0 then
  balance = capacityUnits
  lastMs = redisNowMs
else
  balance = stored_integer(balanceRaw, 'balance_units', 0, capacityUnits)
  lastMs = stored_integer(lastRaw, 'last_ms', 0, MAX_SAFE - 1)
end

local effectiveNowMs = math.max(redisNowMs, lastMs)
local deficit = capacityUnits - balance
local timeToFull = ceil_div(deficit, refillTokens)
local elapsed = math.min(effectiveNowMs - lastMs, timeToFull)
if elapsed > 0 then
  balance = math.min(capacityUnits, balance + elapsed * refillTokens)
end

local available = math.floor(balance / refillPeriodMs)
local allowed = 0
local reserved = 0
local retryAfterMs = 0
if available >= minimumPermits then
  allowed = 1
  reserved = math.min(desiredPermits, available)
  balance = balance - reserved * refillPeriodMs
  available = math.floor(balance / refillPeriodMs)
else
  retryAfterMs = ceil_div(minimumPermits * refillPeriodMs - balance, refillTokens)
  if retryAfterMs < 1 then
    retryAfterMs = 1
  end
end

local resetAfterMs = ceil_div(capacityUnits - balance, refillTokens)
if effectiveNowMs + resetAfterMs + 1000 >= MAX_SAFE then
  error('unsafe reset timestamp')
end
local ttlMs = math.max(1000, resetAfterMs + 1000)

redis.call('HSET', KEYS[1], 'balance_units', string.format('%.0f', balance),
  'last_ms', string.format('%.0f', effectiveNowMs))
redis.call('PEXPIRE', KEYS[1], string.format('%.0f', ttlMs))

local reservationValidForMs = 0
if allowed == 1 then
  reservationValidForMs = resetAfterMs
end
return {allowed, reserved, available, retryAfterMs, resetAfterMs,
  reservationValidForMs, effectiveNowMs}

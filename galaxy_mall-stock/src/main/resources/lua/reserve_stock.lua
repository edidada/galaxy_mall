local stockKey = KEYS[1]
local reservationKey = KEYS[2]
local quantity = tonumber(ARGV[1])
local productId = ARGV[2]

local current = redis.call('GET', stockKey)
if not current then
  return {2, -1}
end

current = tonumber(current)
if current < quantity then
  return {1, current}
end

local remaining = current - quantity
redis.call('DECRBY', stockKey, quantity)
redis.call('HSET', reservationKey,
  'productId', productId,
  'quantity', tostring(quantity),
  'remainingStock', tostring(remaining),
  'status', 'RESERVED')
return {0, remaining}

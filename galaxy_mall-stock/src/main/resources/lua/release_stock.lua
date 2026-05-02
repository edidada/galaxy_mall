local stockKey = KEYS[1]
local reservationKey = KEYS[2]

if redis.call('EXISTS', reservationKey) == 0 then
  return {1, -1}
end

local status = redis.call('HGET', reservationKey, 'status')
if status == 'RELEASED' then
  local current = tonumber(redis.call('GET', stockKey))
  return {0, current}
end
if status == 'CONFIRMED' then
  local current = tonumber(redis.call('GET', stockKey))
  return {0, current}
end

local quantity = tonumber(redis.call('HGET', reservationKey, 'quantity'))
redis.call('INCRBY', stockKey, quantity)
local current = tonumber(redis.call('GET', stockKey))
redis.call('HSET', reservationKey, 'status', 'RELEASED')
redis.call('HSET', reservationKey, 'remainingStock', tostring(current))
return {0, current}

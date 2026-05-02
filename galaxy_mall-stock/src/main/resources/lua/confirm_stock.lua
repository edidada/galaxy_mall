local reservationKey = KEYS[1]
if redis.call('EXISTS', reservationKey) == 0 then
  return {1, -1}
end
local status = redis.call('HGET', reservationKey, 'status')
if status == 'RELEASED' then
  return {2, -1}
end
if status == 'CONFIRMED' then
  return {0, 0}
end
redis.call('HSET', reservationKey, 'status', 'CONFIRMED')
return {0, 0}

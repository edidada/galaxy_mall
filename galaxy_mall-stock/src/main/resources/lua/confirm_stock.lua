local reservationKey = KEYS[1]
if redis.call('EXISTS', reservationKey) == 0 then
  return {1, -1}
end
redis.call('HSET', reservationKey, 'status', 'CONFIRMED')
return {0, 0}

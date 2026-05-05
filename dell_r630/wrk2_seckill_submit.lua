local counter = 0

request = function()
  counter = counter + 1

  local user_id = string.format("wrk-user-%d", counter)
  local client_ip = string.format("10.0.%d.%d", math.floor(counter / 255) % 255, counter % 255)
  local body = string.format(
    '{"userId":"%s","productId":"sku-seckill","quantity":1,"clientIp":"%s"}',
    user_id,
    client_ip
  )

  wrk.method = "POST"
  wrk.body = body
  wrk.headers["Content-Type"] = "application/json"

  return wrk.format(nil)
end

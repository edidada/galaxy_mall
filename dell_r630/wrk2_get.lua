-- wrk2_get.lua
wrk.method = "GET"
wrk.path = "/api/gateway/products/sku-iphone?userId=u100&clientIp=10.0.0.1"

function request()
    return wrk.format(nil, wrk.path)
end

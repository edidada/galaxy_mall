# API 文档

本文档整理当前工程实际暴露的 HTTP Server API，包括：

- 网关编排接口
- 各微服务直连接口
- Spring Cloud Gateway 正式代理路由

默认端口：

- gateway: `http://localhost:8080`
- user: `http://localhost:8081`
- product: `http://localhost:8082`
- stock: `http://localhost:8083`
- order: `http://localhost:8084`
- payment: `http://localhost:8085`

## 通用响应结构

除商品静态页接口外，JSON 响应统一为：

```json
{
  "success": true,
  "message": "string",
  "data": {}
}
```

字段说明：

- `success`: 是否成功
- `message`: 结果说明
- `data`: 业务返回对象；失败时通常为 `null`

## 数据结构

### UserView

```json
{
  "userId": "u100",
  "nickname": "andromeda",
  "level": 3
}
```

### ProductView

```json
{
  "productId": "sku-iphone",
  "name": "iPhone Galaxy Edition",
  "priceCent": 899900,
  "seckillProduct": false,
  "staticPage": "<html>...</html>"
}
```

### OrderView

```json
{
  "orderId": "ORD-1777515488759-1001",
  "userId": "u100",
  "productId": "sku-iphone",
  "quantity": 1,
  "amountCent": 899900,
  "status": "WAITING_PAYMENT",
  "queueToken": null,
  "acceptedEpochMillis": 1777515489000
}
```

### PaymentView

```json
{
  "paymentId": "PAY-xxx",
  "orderId": "ORD-xxx",
  "status": "SUCCESS",
  "message": "payment-success"
}
```

### StockView

```json
{
  "reservationId": "ORD-xxx",
  "productId": "sku-iphone",
  "reservedQuantity": 1,
  "remainingStock": 9,
  "status": "RESERVED"
}
```

## 1. 网关编排接口

Base URL: `http://localhost:8080/api/gateway`

### 1.1 查询商品

- Method: `GET`
- URL: `/api/gateway/products/{productId}`

路径参数：

- `productId`: 商品 ID

查询参数：

- `userId`: 用户 ID，必填
- `clientIp`: 客户端 IP，必填

请求示例：

```http
GET /api/gateway/products/sku-iphone?userId=u100&clientIp=10.0.0.1
```

成功出参：

```json
{
  "success": true,
  "message": "product-found",
  "data": {
    "productId": "sku-iphone",
    "name": "iPhone Galaxy Edition",
    "priceCent": 899900,
    "seckillProduct": false,
    "staticPage": "<html>...</html>"
  }
}
```

### 1.2 提交订单

- Method: `POST`
- URL: `/api/gateway/orders`
- Content-Type: `application/json`

入参：

```json
{
  "userId": "u100",
  "productId": "sku-iphone",
  "quantity": 1,
  "clientIp": "10.0.1.1",
  "queueToken": null
}
```

字段说明：

- `userId`: 用户 ID，必填
- `productId`: 商品 ID，必填
- `quantity`: 购买数量，必填，最小值 `1`
- `clientIp`: 客户端 IP，必填
- `queueToken`: 排队二次放行令牌，可选

成功出参：

```json
{
  "success": true,
  "message": "order-submitted",
  "data": {
    "orderId": "ORD-xxx",
    "userId": "u100",
    "productId": "sku-iphone",
    "quantity": 1,
    "amountCent": 899900,
    "status": "SUBMITTED",
    "queueToken": null,
    "acceptedEpochMillis": 1777515489000
  }
}
```

排队出参：

```json
{
  "success": true,
  "message": "Galaxy Mall Queue ...",
  "data": {
    "orderId": "QUEUE-xxx",
    "userId": "u300",
    "productId": "sku-iphone",
    "quantity": 1,
    "amountCent": 0,
    "status": "QUEUED",
    "queueToken": "uuid-token",
    "acceptedEpochMillis": 1777515489000
  }
}
```

### 1.3 查询订单

- Method: `GET`
- URL: `/api/gateway/orders/{orderId}`

路径参数：

- `orderId`: 订单 ID

成功出参：

```json
{
  "success": true,
  "message": "order-found",
  "data": {
    "orderId": "ORD-xxx",
    "userId": "u100",
    "productId": "sku-iphone",
    "quantity": 1,
    "amountCent": 899900,
    "status": "WAITING_PAYMENT",
    "queueToken": null,
    "acceptedEpochMillis": 1777515489000
  }
}
```

### 1.4 支付订单

- Method: `POST`
- URL: `/api/gateway/payments/{orderId}`

路径参数：

- `orderId`: 订单 ID

成功出参：

```json
{
  "success": true,
  "message": "payment-success",
  "data": {
    "paymentId": "PAY-xxx",
    "orderId": "ORD-xxx",
    "status": "SUCCESS",
    "message": "payment-success"
  }
}
```

## 2. Spring Cloud Gateway 正式代理路由

Base URL: `http://localhost:8080`

这些接口由 `Spring Cloud Gateway` 转发到下游服务：

- `GET /edge/users/{userId}` -> user service `/api/users/{userId}`
- `GET /edge/products/{productId}` -> product service `/api/products/{productId}`
- `POST /edge/stocks/reserve` -> stock service `/api/stocks/reserve`
- `POST /edge/stocks/confirm` -> stock service `/api/stocks/confirm`
- `POST /edge/stocks/release` -> stock service `/api/stocks/release`
- `POST /edge/orders` -> order service `/api/orders`
- `GET /edge/orders/{orderId}` -> order service `/api/orders/{orderId}`
- `POST /edge/orders/{orderId}/paid` -> order service `/api/orders/{orderId}/paid`
- `POST /edge/payments/{orderId}` -> payment service `/api/payments/{orderId}`

说明：

- Gateway 会透传或补充 `X-Trace-Id` 请求头和响应头
- `/edge/...` 路由是正式网关转发能力
- `/api/gateway/...` 是网关自身的业务编排接口

## 3. 用户服务接口

Base URL: `http://localhost:8081/api/users`

### 3.1 查询用户

- Method: `GET`
- URL: `/api/users/{userId}`

路径参数：

- `userId`: 用户 ID

成功出参：

```json
{
  "success": true,
  "message": "user-found",
  "data": {
    "userId": "u100",
    "nickname": "andromeda",
    "level": 3
  }
}
```

失败出参：

```json
{
  "success": false,
  "message": "user-not-found",
  "data": null
}
```

## 4. 商品服务接口

Base URL: `http://localhost:8082/api/products`

### 4.1 查询商品

- Method: `GET`
- URL: `/api/products/{productId}`

路径参数：

- `productId`: 商品 ID

成功出参：

```json
{
  "success": true,
  "message": "product-found",
  "data": {
    "productId": "sku-iphone",
    "name": "iPhone Galaxy Edition",
    "priceCent": 899900,
    "seckillProduct": false,
    "staticPage": "<html>...</html>"
  }
}
```

### 4.2 查询商品静态页

- Method: `GET`
- URL: `/api/products/{productId}/page`
- Response Content-Type: `text/html`

路径参数：

- `productId`: 商品 ID

成功出参：

```html
<html><body><h1>iPhone Galaxy Edition</h1><p>static page</p></body></html>
```

## 5. 库存服务接口

Base URL: `http://localhost:8083/api/stocks`

### 5.1 预扣库存

- Method: `POST`
- URL: `/api/stocks/reserve`
- Content-Type: `application/json`

入参：

```json
{
  "reservationId": "ORD-xxx",
  "productId": "sku-iphone",
  "quantity": 1
}
```

成功出参：

```json
{
  "success": true,
  "message": "stock-reserved",
  "data": {
    "reservationId": "ORD-xxx",
    "productId": "sku-iphone",
    "reservedQuantity": 1,
    "remainingStock": 9,
    "status": "RESERVED"
  }
}
```

### 5.2 确认扣减库存

- Method: `POST`
- URL: `/api/stocks/confirm`
- Content-Type: `application/json`

入参：

```json
{
  "reservationId": "ORD-xxx"
}
```

成功出参：

```json
{
  "success": true,
  "message": "db-confirmed",
  "data": {
    "reservationId": "ORD-xxx",
    "productId": "sku-iphone",
    "reservedQuantity": 1,
    "remainingStock": 9,
    "status": "CONFIRMED"
  }
}
```

### 5.3 释放库存

- Method: `POST`
- URL: `/api/stocks/release`
- Content-Type: `application/json`

入参：

```json
{
  "reservationId": "ORD-xxx",
  "reason": "confirm-failed"
}
```

成功出参：

```json
{
  "success": true,
  "message": "reservation-released:confirm-failed",
  "data": {
    "reservationId": "ORD-xxx",
    "productId": "sku-iphone",
    "reservedQuantity": 1,
    "remainingStock": 10,
    "status": "RELEASED"
  }
}
```

## 6. 订单服务接口

Base URL: `http://localhost:8084/api/orders`

### 6.1 创建订单

- Method: `POST`
- URL: `/api/orders`
- Content-Type: `application/json`

入参：

```json
{
  "userId": "u100",
  "productId": "sku-iphone",
  "quantity": 1
}
```

成功出参：

```json
{
  "success": true,
  "message": "order-submitted",
  "data": {
    "orderId": "ORD-xxx",
    "userId": "u100",
    "productId": "sku-iphone",
    "quantity": 1,
    "amountCent": 899900,
    "status": "SUBMITTED",
    "queueToken": null,
    "acceptedEpochMillis": 1777515489000
  }
}
```

### 6.2 查询订单

- Method: `GET`
- URL: `/api/orders/{orderId}`

路径参数：

- `orderId`: 订单 ID

成功出参：

```json
{
  "success": true,
  "message": "order-found",
  "data": {
    "orderId": "ORD-xxx",
    "userId": "u100",
    "productId": "sku-iphone",
    "quantity": 1,
    "amountCent": 899900,
    "status": "WAITING_PAYMENT",
    "queueToken": null,
    "acceptedEpochMillis": 1777515489000
  }
}
```

### 6.3 标记已支付

- Method: `POST`
- URL: `/api/orders/{orderId}/paid`

路径参数：

- `orderId`: 订单 ID

成功出参：

```json
{
  "success": true,
  "message": "order-paid",
  "data": {
    "orderId": "ORD-xxx",
    "userId": "u100",
    "productId": "sku-iphone",
    "quantity": 1,
    "amountCent": 899900,
    "status": "PAID",
    "queueToken": null,
    "acceptedEpochMillis": 1777515489000
  }
}
```

## 7. 支付服务接口

Base URL: `http://localhost:8085/api/payments`

### 7.1 发起支付

- Method: `POST`
- URL: `/api/payments/{orderId}`

路径参数：

- `orderId`: 订单 ID

成功出参：

```json
{
  "success": true,
  "message": "payment-success",
  "data": {
    "paymentId": "PAY-xxx",
    "orderId": "ORD-xxx",
    "status": "SUCCESS",
    "message": "payment-success"
  }
}
```

## 8. Actuator 接口

所有服务都暴露：

- `GET /actuator/health`
- `GET /actuator/info`

例如：

- `http://localhost:8080/actuator/health`
- `http://localhost:8081/actuator/health`
- `http://localhost:8082/actuator/health`
- `http://localhost:8083/actuator/health`
- `http://localhost:8084/actuator/health`
- `http://localhost:8085/actuator/health`

说明：

- 当前配置只开放 `health` 和 `info`

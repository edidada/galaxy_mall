# Galaxy Mall 100w+ TPS 测试方案

## 一、测试目标
验证 Galaxy Mall 微服务系统在 k3s 集群上能否达到 100w+ TPS 的性能指标

## 二、环境信息

> 说明：本文件最初面向 `2026-05-04` 的一次单节点压测准备。仓库和集群后续已经补上 `Redis / RocketMQ / jdbc-sharded` 联调能力，因此下面的“服务架构”如果用于描述**当前** k3s 运行态，需要以实际 Deployment 环境变量和副本数为准，而不能只看应用默认端口。

### 2.1 硬件配置
- **CPU**: 2路 Intel Xeon E5-2680 v3 (12核/24线程每路，共48核)
- **内存**: 62GB
- **操作系统**: Linux (k3s 节点)

### 2.2 服务架构
- **初始压测副本数**: `gateway=2, user=1, product=1, stock=2, order=2, payment=1`
- **仓库默认应用端口**: `gateway=8080, user=8081, product=8082, stock=8083, order=8084, payment=8085`
- **当前单节点 k3s 实际部署**:
  - 集群内很多 Deployment 已通过 `SERVER_PORT=8080` 统一监听 `8080`
  - 服务间调用地址应以实际 `SERVICES_*_URL=http://<service>:8080` 为准
  - 当前是否启用 `Redis / RocketMQ / jdbc-sharded` 应以 Deployment 环境变量为准，而不是以本文件初稿为准

## 三、测试工具选择

### 3.1 主要工具: wrk2 / wrk
最初方案按 `wrk2` 设计；如果实际执行使用的是 `wrk 4.2.0`，需要在结果文件里明确说明结果不可直接等价为固定速率 `wrk2` 结果。选择这类工具的原因：
- 高性能：基于 epoll 模型，单机能产生极高并发
- 精准延迟测量：支持高并发下的延迟统计
- Lua 脚本支持：可灵活定制请求

### 3.2 辅助工具
- **k6**: 用于复杂场景测试
- **Prometheus + Grafana**: 监控系统指标
- **kubectl top**: 监控 Pod 资源使用

## 四、测试场景

### 4.1 场景一：商品查询 (GET)
- **接口**: `/api/gateway/products/sku-iphone`
- **目标**: 100w+ TPS
- **特性**: 读多写少，多级缓存

### 4.2 场景二：订单创建 (POST)
- **接口**: `/api/gateway/orders`
- **目标**: 10w+ TPS (写操作受限于数据库/消息队列)
- **特性**: 异步处理，限流保护

## 五、测试步骤

### 5.1 准备阶段
1. 在 k3s 服务器上安装 wrk2
2. 部署 k6 作为备选工具
3. 确保所有服务正常运行
4. 预热系统（执行少量请求）

### 5.2 基准测试
1. 低并发测试：100 并发，10秒
2. 逐步增加并发：500 → 1000 → 5000 → 10000 → 50000
3. 记录每个阶段的 TPS、延迟、错误率

### 5.3 峰值测试
1. 调整服务副本数（如需要）
2. 增加并发数至极限
3. 持续压测 5-10 分钟
4. 监控系统资源使用

### 5.4 稳定性测试
1. 在 80% 峰值负载下持续运行 30 分钟
2. 检查错误率是否保持在 0.1% 以下

## 六、性能指标

### 6.1 关键指标
- **TPS**: 每秒事务数（目标 100w+）
- **延迟 (P50/P95/P99)**: 响应时间分布
- **错误率**: 请求失败比例（目标 < 0.1%）
- **资源使用**: CPU、内存、网络

### 6.2 预期结果
| 场景 | 目标 TPS | 预期延迟 (P95) |
|------|----------|----------------|
| 商品查询 | 100w+ | < 50ms |
| 订单创建 | 10w+ | < 200ms |

## 七、测试脚本

### 7.1 wrk2 测试脚本 (GET)
```lua
-- wrk2_get.lua
wrk.method = "GET"
wrk.path = "/api/gateway/products/sku-iphone?userId=u100&clientIp=10.0.0.1"

function request()
    return wrk.format(nil, wrk.path)
end
```

### 7.2 wrk2 测试脚本 (POST)
```lua
-- wrk2_post.lua
wrk.method = "POST"
wrk.path = "/api/gateway/orders"
wrk.headers["Content-Type"] = "application/json"

counter = 0

function request()
    counter = counter + 1
    local userId = "u" .. (100000 + counter)
    local clientIp = "10.0." .. (counter % 250) .. "." .. ((counter % 200) + 1)
    local body = string.format('{"userId":"%s","productId":"sku-iphone","quantity":1,"clientIp":"%s","queueToken":null}', userId, clientIp)
    return wrk.format(nil, wrk.path, nil, body)
end
```

## 八、执行命令

### 8.1 安装 wrk2
```bash
# 在 k3s 服务器上
git clone https://github.com/giltene/wrk2.git
cd wrk2
make
sudo cp wrk /usr/local/bin/
```

### 8.2 GET 测试命令

> 2026-10-17 更新：旧地址 `http://192.168.31.171:30897` 已失效（`.171` 是 Proxmox 宿主，不承载 k3s 端口）。
> 现在走 Traefik Ingress：用节点 IP + `-H "Host: mall.192.168.31.77.nip.io"`，
> **不要用域名压测**（本机 DNS 可能被代理 fake-ip 接管，压测结果会失真）。
> 节点 IP 取 `.77 / .211 / .251 / .88` 任意一个（Ingress 已覆盖 4 个节点）；URL 记得加引号，否则 `&` 会被 shell 吃掉。

```bash
# 小规模预热
wrk -H "Host: mall.192.168.31.77.nip.io" -t4 -c100 -d10s -R10000 \
  "http://192.168.31.77/api/gateway/products/sku-iphone?userId=u100&clientIp=10.0.0.1"

# 中等规模
wrk -H "Host: mall.192.168.31.77.nip.io" -t8 -c1000 -d30s -R100000 \
  "http://192.168.31.77/api/gateway/products/sku-iphone?userId=u100&clientIp=10.0.0.1"

# 大规模测试
wrk -H "Host: mall.192.168.31.77.nip.io" -t16 -c10000 -d60s -R1000000 \
  "http://192.168.31.77/api/gateway/products/sku-iphone?userId=u100&clientIp=10.0.0.1"
```

### 8.3 POST 测试命令
```bash
wrk -H "Host: mall.192.168.31.77.nip.io" -t8 -c500 -d30s -s wrk2_post.lua http://192.168.31.77
```

## 九、监控命令

### 9.1 监控 Pod 资源
```bash
watch -n 1 'kubectl top pods -A'
```

### 9.2 监控节点资源
```bash
watch -n 1 'kubectl top nodes'
```

### 9.3 查看服务日志
```bash
kubectl logs -f deployment/galaxy-mall-gateway
```

## 十、注意事项

1. **网络限制**: 单网卡可能成为瓶颈，考虑使用多网卡或负载均衡
2. **文件描述符**: 需要增加系统文件描述符限制
3. **TCP 连接**: 需要优化 TCP 参数（time_wait、port_range）
4. **中间件**: 如果要测真实异步写链路，必须先确认 `stock` 已切到 `redis`、`order` 已切到 `rocketmq + jdbc-sharded`
5. **限流配置**: 网关限流设置可能需要调整以达到目标 TPS；当前仓库 `prod` 模板的查询限流默认值显著高于 `application.yml` 默认值
6. **结果边界**: 单节点 `k3s + NodePort + 单压测机` 的结果只能代表当前单节点上限，不能外推成“生产可达 100w+ TPS”

## 十一、预期结论

基于当前硬件配置（48核62G）和架构设计，预期：
- **商品查询场景**: 可以达到或超过 100w TPS
- **订单创建场景**: 受限于写操作，预期达到 10w-50w TPS

实际结果将取决于具体的 k3s 配置、服务副本数、中间件性能等因素。

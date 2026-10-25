# Win11 用 Navicat 连接 k3s 上的 MySQL 查看数据

> 记录日期：2026-10-25
> 对象：middleware 命名空间的 `mysql` Service（ClusterIP `10.43.251.207:3306`，无 NodePort，已验证从 master 节点可达）

Win11（192.168.31.x 局域网）到 Pod 网络 `10.42.x` 不可直连，需经 k3s-master（192.168.31.77）中转，两种方式：

## 方案 A：Navicat 自带 SSH 隧道（推荐，一条连接搞定）

Navicat → 新建连接 → MySQL：

1. **常规选项卡**
   - 主机：`10.43.251.207`（mysql 的 ClusterIP，从 master 节点可达，已验证 3306 通）
   - 端口：`3306`
   - 用户名：`root`
   - 密码：在 Win11 终端跑下面命令取（不要存明文到文档）：

     ```bash
     ssh k3s-master 'k3s kubectl -n middleware get secret mysql-secret -o jsonpath="{.data.mysql-root-password}" | base64 -d'
     ```

2. **SSH 选项卡**（勾选"使用 SSH 隧道"）
   - 主机：`192.168.31.77`（k3s-master，`~/.ssh/config` 里已有免密别名）
   - 端口：`22`，用户名：`root`
   - 身份验证方法：**公钥**，私钥：`C:\Users\wdidada\.ssh\win_r630_proxmox_`（无密码短语）
3. 测试连接 → 保存

原理：Navicat 先 SSH 到 master，再从 master 内部连 ClusterIP（等价 `kubectl port-forward`，但不用额外进程）。

## 方案 B：本机端口转发（Navicat 连 127.0.0.1）

```powershell
ssh -N -L 13306:10.43.251.207:3306 k3s-master
```

窗口保持打开，Navicat 里建连接：主机 `127.0.0.1`、端口 `13306`、root + 上面取的密码。

## 连上后能看什么

| 库/表 | 说明 |
| --- | --- |
| `galaxy_order_0` / `galaxy_order_1` 的 `t_order_0..3` | 订单分库分表（UAT 冒烟订单都在这里），`status` 字段看 `SUBMITTED/PROCESSING/WAITING_PAYMENT/PAID/FAILED` |
| 其它业务数据 | **不在 MySQL**：用户（u100 等）、商品、库存"DB 账本"、等待室票据都在各服务进程内存；MySQL 里只有订单 |

## 顺带说明

- 同法可连 **Doris**（`business-doris-fe` 的 9030，MySQL 协议）和 StarRocks VM108 的 `9030`，Navicat 都当 MySQL 类型连即可。
- 不建议临时给 mysql 加 NodePort 暴露到局域网；SSH 隧道比"用完删 NodePort"省心且不扩大暴露面。

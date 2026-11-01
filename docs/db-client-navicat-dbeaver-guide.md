# Win11 用 Navicat / DBeaver 连接 k3s 上的 MySQL 查看数据

> 记录日期：2026-10-25，2026-11-01 追加 DBeaver 一节
> 对象：middleware 命名空间的 `mysql` Service（ClusterIP `10.43.251.207:3306`，无 NodePort，已验证从 master 节点可达）

Win11（192.168.31.x 局域网）到 Pod 网络 `10.42.x` 不可直连，需经 k3s-master（192.168.31.77）中转。

## 1. 密码取回（Navicat / DBeaver 通用）

在 Win11 PowerShell 执行（不要把密码写进文档/聊天记录）：

```powershell
ssh k3s-master 'k3s kubectl -n middleware get secret mysql-secret -o jsonpath="{.data.mysql-root-password}" | base64 -d'
```

## 2. Navicat：自带 SSH 隧道（推荐）

新建连接 → MySQL：

1. **常规选项卡**：主机 `10.43.251.207`，端口 `3306`，用户 `root`，密码用第 1 节取回的值
2. **SSH 选项卡**（勾选"使用 SSH 隧道"）：主机 `192.168.31.77`，端口 `22`，用户 `root`，
   身份验证=公钥，私钥 `C:\Users\wdidada\.ssh\win_r630_proxmox_`（Passphrase 留空）
3. 测试连接 → 保存

原理：Navicat 先 SSH 到 master，再从 master 内部连 ClusterIP（等价 `kubectl port-forward`，无需额外进程）。

## 3. DBeaver（dbeaver-ce-23.2.4）：内置 SSH 隧道

1. **数据库 → 新建连接 → MySQL**，常规页：
   - Server Host：`10.43.251.207`，Port：`3306`，Database：可先留空，连上后再选
   - Username：`root`，Password：第 1 节取回的值
2. 切到连接设置里的 **SSH 选项卡**，勾选 **Use SSH Tunnel**：
   - Host/IP：`192.168.31.77`，Port：`22`，User Name：`root`
   - Authentication Method：**Public Key**
   - Private key：`C:\Users\wdidada\.ssh\win_r630_proxmox_`（Passphrase 留空）
   - Local port：留空让 DBeaver 自动选
3. 点 **Test connection**（会分别验证 SSH 与 DB 两段）→ 完成

> 若密钥验证报错（老版本 DBeaver 对 OpenSSH 新格式 ed25519 偶有兼容问题），改用第 4 节方案，最稳。

## 4. 通用兜底：ssh 端口转发（零依赖）

PowerShell 开一个窗口保持运行：

```powershell
ssh -N -L 13306:10.43.251.207:3306 k3s-master
```

客户端里连 `127.0.0.1:13306`（root + 密码）。用完 Ctrl+C 关闭转发。

## 5. 连上后能看什么

| 库/表 | 说明 |
| --- | --- |
| `galaxy_order_0` / `galaxy_order_1` 的 `t_order_0..3` | 订单分库分表，`status` 字段看 `SUBMITTED/PROCESSING/WAITING_PAYMENT/PAID/FAILED` |
| 其它业务数据 | **不在 MySQL**：用户（u100 等）、商品、库存"DB 账本"、等待室票据都在各服务进程内存；MySQL 里只有订单 |

## 6. 顺带说明

- 同法可连 **Doris**（`business-doris-fe:9030`）和 **StarRocks**（VM108 `192.168.31.162:9030`），Navicat/DBeaver 都按 MySQL 类型连；VM108 端口本身通局域网，可直连无需隧道。
- 不建议临时给 mysql 加 NodePort 暴露到局域网；SSH 隧道不扩大暴露面。
- DBeaver 可把 r630 相关连接归到一个项目（如 `r630-k3s`）统一管理。

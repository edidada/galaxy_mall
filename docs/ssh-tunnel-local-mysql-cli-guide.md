# ssh 端口转发 + 本地 mysql.exe 连 k3s DB（实测通过）

> 记录日期：2026-11-01
> 前提：`~/.ssh/config` 已有 `k3s-master`（192.168.31.77）免密别名；目标为 middleware 的 MySQL（ClusterIP `10.43.251.207:3306`）
> 本地客户端：`D:\develops\mysql-8.4.8-winx64\bin\mysql.exe`（服务端实测 8.0.46，向前兼容）

## 1. 连接三步

### 第 1 步：开 SSH 隧道（PowerShell 窗口，保持打开）

```powershell
ssh -N -L 13306:10.43.251.207:3306 k3s-master
```

`-N` 不执行远程命令，`-L 本地端口:ClusterIP:3306` 把集群内 Service 映射到本机 13306。窗口挂着即可，Ctrl+C 关闭隧道。

### 第 2 步：密码取到变量（每次新会话执行一次）

```powershell
$pw = (ssh k3s-master 'k3s kubectl -n middleware get secret mysql-secret -o jsonpath="{.data.mysql-root-password}" | base64 -d') -join ''
```

### 第 3 步：本地 mysql.exe 连接

```powershell
& D:\develops\mysql-8.4.8-winx64\bin\mysql.exe --host=127.0.0.1 --port=13306 --user=root --password="$pw" -e "SHOW DATABASES;"
```

## 2. 已踩的坑（务必注意）

| 坑 | 现象 | 正确写法 |
| --- | --- | --- |
| 短参数被 PowerShell 拆坏 | `ERROR 2005 (HY000): Unknown MySQL server host '127'` | 参数用长格式：`--host=127.0.0.1 --port=13306`，不要 `-h127.0.0.1` |
| 密码参数带空格 | 被当成新参数/数据库名 | `--password="$pw"`，`=` 与引号连写；`-p"$pw"` 同理中间不能有空格 |
| 命令行密码明文 | mysql 会打 `[Warning] Using a password on the command line interface can be insecure.` | 提示无害；敏感场景改用 `mysql --login-path` 或交互输入 |

## 3. 实测记录（2026-11-01）

- `SELECT VERSION()` → **8.0.46**
- 数据库列表：`galaxy_order_0` / `galaxy_order_1` + 系统库（另有 alarm110、doit36_main_audit 等）
- 8 张 `t_order_0..3` 汇总：约 230 条订单（WAITING_PAYMENT 86 / FAILED 84 / PAID 53），含历史压测与冒烟数据
- 最近订单 `ORD-1793496815156-1021`（u100 / sku-coffee / PAID / 2026-11-01 09:33），证明 uat profile 下订单→支付闭环正常

常用查询示例（订单状态分布）：

```sql
SELECT status, COUNT(*) FROM (
  SELECT status FROM galaxy_order_0.t_order_0 UNION ALL SELECT status FROM galaxy_order_0.t_order_1
  UNION ALL SELECT status FROM galaxy_order_0.t_order_2 UNION ALL SELECT status FROM galaxy_order_0.t_order_3
  UNION ALL SELECT status FROM galaxy_order_1.t_order_0 UNION ALL SELECT status FROM galaxy_order_1.t_order_1
  UNION ALL SELECT status FROM galaxy_order_1.t_order_2 UNION ALL SELECT status FROM galaxy_order_1.t_order_3
) t GROUP BY status ORDER BY 2 DESC;
```

## 4. 同一隧道的其它用法

- 换目标只需改 `-L 本地端口:目标:端口`，例如 Doris：`ssh -N -L 19030:10.43.37.98:9030 k3s-master`（`business-doris-fe` ClusterIP）
- 图形客户端（Navicat/DBeaver）不想配自己的 SSH 实现时，也走同一条隧道连 `127.0.0.1:13306`，见 `db-client-navicat-dbeaver-guide.md`

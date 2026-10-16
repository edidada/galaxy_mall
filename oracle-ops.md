# Oracle XE 独立部署运维文档

> 目标：为需要 Oracle 的业务单独开一台 **Debian 12 的 VM/LXC**，在其上用 **Docker** 跑 `gvenzl/oracle-xe`（Oracle Database 21c Express Edition，免费、够开发/测试）。
> 设计原则：与 k3s 集群、Proxmox 宿主机解耦，独立资源、独立备份，不影响现有 galaxy-mall / MySQL 等负载。

---

## 1. 拓扑与资源

```
Proxmox 宿主机 (Debian 8 / PVE)
└── Oracle VM/LXC (Debian 12)
    └── Docker
        └── container: gvenzl/oracle-xe:21c
            ├── 端口 1521/tcp  (数据库监听, 供 k3s 集群/应用连接)
            ├── 端口 5500/tcp  (Oracle EM Express 管理界面)
            └── 卷  oracle-data -> /opt/oracle/oradata (持久化数据)
```

**资源建议（Oracle XE 上限内）**

| 项目 | 建议值 | 说明 |
|------|--------|------|
| vCPU | 2 | XE 强制最多用 2 vCPU，多给无意义 |
| 内存 | 4 GB | XE 限制 DB 用约 2 GB；留点给 OS/Docker |
| 系统盘 | 20 GB | Debian 12 + Docker |
| 数据盘/卷 | ≥ 30 GB | `/opt/oracle/oradata` 持久化；XE 总数据上限 12 GB |
| 网络 | 与 k3s 同一 VLAN/子网 | 静态 IP，确保集群 Pod 能访问 1521 |

> ⚠️ XE 限制：最多 2 vCPU、约 2 GB 内存、单实例、12 GB 用户数据、无 RAC。生产重负载请用 Oracle SE/EE 官方镜像（需 OTN 许可）。

---

## 2. 创建 VM / LXC（在 Proxmox 上）

**优先推荐 VM**（Docker 在 VM 里最省心，避免 LXC 嵌套问题）。若要 LXC，必须开启 `nesting=1` 且用特权或配置 `keyctl`。

### 选项 A：VM（推荐）
1. 上传 Debian 12 云镜像或挂载 ISO，新建 VM。
2. 分配上述资源，网卡桥接到 k3s 所在桥（如 `vmbr0`）。
3. 安装后配置静态 IP（例 `192.168.31.x`，网关同 k3s）。
4. 放行防火墙：`1521`、`5500`（仅放行业务/管理网段，不要公网暴露 1521）。

### 选项 B：LXC（如需更轻量）
```
# Proxmox 上创建后，设置
pct set <CTID> --features nesting=1
```
容器模板选 Debian 12，同样配静态 IP 与防火墙。

---

## 3. 安装 Docker（Debian 12）

```bash
# 1) 基础依赖
apt update && apt install -y ca-certificates curl gnupg lsb-release

# 2) 添加 Docker 官方仓库
install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/debian/gpg -o /etc/apt/keyrings/docker.asc
chmod a+r /etc/apt/keyrings/docker.asc
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] \
https://download.docker.com/linux/debian $(. /etc/os-release && echo $VERSION_CODENAME) stable" \
> /etc/apt/sources.list.d/docker.list

# 3) 安装
apt update && apt install -y docker-ce docker-ce-cli containerd.io \
    docker-buildx-plugin docker-compose-plugin

# 4) 校验
systemctl enable --now docker
docker version
docker compose version
```

---

## 4. 运行 Oracle XE（docker compose）

创建 `/opt/oracle/docker-compose.yml`：

```yaml
services:
  oracle-xe:
    image: gvenzl/oracle-xe:21c
    container_name: oracle-xe
    environment:
      ORACLE_PASSWORD: "ChangeMe_Strong!1"      # SYS / SYSTEM 密码（必填）
      ORACLE_DATABASE: "ORCL"                    # 可选，默认 XE
      APP_USER: "appuser"                        # 可选，自动创建的普通用户
      APP_USER_PASSWORD: "AppPass_Strong!1"      # 可选
      ORACLE_RANDOM_PASSWORD: "false"
    ports:
      - "1521:1521"     # 数据库
      - "5500:5500"     # EM Express
    volumes:
      - oracle-data:/opt/oracle/oradata
    restart: unless-stopped
    healthcheck:
      test: ["CMD", "healthcheck.sh"]
      interval: 30s
      timeout: 10s
      retries: 10

volumes:
  oracle-data:
```

启动：

```bash
cd /opt/oracle
docker compose up -d
docker compose logs -f oracle-xe     # 首次启动会初始化数据库，约 2~5 分钟
```

> 首次运行镜像会自动建库（SID 默认 `XE`，或 `ORACLE_DATABASE` 指定的名）。**务必等日志出现 `DATABASE IS READY TO USE!` 再连接。**

---

## 5. 连接与初始化

**连接串（JDBC，供 k3s 内应用使用）**
```
jdbc:oracle:thin:@<ORACLE_VM_IP>:1521/<SERVICE_NAME>
# SERVICE_NAME 通常为 XE（或 ORACLE_DATABASE 的值），不是 SID
# 例：jdbc:oracle:thin:@192.168.31.x:1521/XE
```

**命令行连接（容器内）**
```bash
docker exec -it oracle-xe sqlplus sys/ChangeMe_Strong!1@//localhost:1521/XE as sysdba
```

**Web 管理**：浏览器打开 `https://<ORACLE_VM_IP>:5500/em`，用 `SYS` + `ORACLE_PASSWORD` 登录 EM Express。

**建业务表空间/用户（示例）**
```sql
CREATE TABLESPACE app_ts DATAFILE 'app_ts.dbf' SIZE 1G AUTOEXTEND ON NEXT 256M;
CREATE USER appuser IDENTIFIED BY "AppPass_Strong!1" DEFAULT TABLESPACE app_ts;
GRANT CONNECT, RESOURCE, CREATE VIEW, UNLIMITED TABLESPACE TO appuser;
```

---

## 6. 运维 Runbook

### 启停
```bash
cd /opt/oracle
docker compose stop      # 停
docker compose start     # 起
docker compose restart   # 重启
docker compose down      # 停并删容器（数据在卷里，不会丢）
```

### 升级镜像版本
```bash
docker compose pull          # 拉新镜像
docker compose up -d         # 滚动替换（数据卷不变，库结构保留）
# 注意：大版本升级（如 21c->23c）建议先备份再升
```

### 备份
**逻辑备份（推荐，可跨版本恢复）**
```bash
# 整库导出
docker exec -i oracle-xe expdp appuser/AppPass_Strong!1@//localhost:1521/XE \
  DIRECTORY=DATA_PUMP_DIR DUMPFILE=full_$(date +%F).dmp LOGFILE=exp_$(date +%F).log FULL=Y
# 取出 dump 文件到宿主机
docker exec oracle-xe cp /opt/oracle/oradata/dpdump/full_*.dmp /tmp/ 2>/dev/null || true
docker cp oracle-xe:/opt/oracle/admin/XE/dpdump/ ./backup/
```

**物理/卷备份**
- Proxmox 对 VM 做快照/备份（`vzdump`）。
- 或备份 Docker 卷：`docker run --rm -v oracle-data:/data -v $PWD/backup:/backup alpine tar czf /backup/oradata-$(date +%F).tgz -C /data .`

### 监控
- 容器健康：`docker ps`（看 STATUS 是否 `healthy`）。
- EM Express `:5500/em` 看实例/表空间/会话。
- 日志：`docker compose logs oracle-xe` 或 `docker logs oracle-xe`。
- 资源：宿主机 `htop` / `docker stats oracle-xe`。

### 排错
| 现象 | 可能原因 | 处理 |
|------|----------|------|
| 应用连不上 1521 | 防火墙/跨网段 | 检查 VM 防火墙、Proxmox 桥、k3s 节点路由 |
| 容器一直 `starting` | 首次建库慢 | 等 `DATABASE IS READY`；查日志有无 ORA- 报错 |
| 重启后库没了 | 没挂卷 | 确认 `oracle-data` 卷已挂载到 `/opt/oracle/oradata` |
| ORA-12514 | SERVICE_NAME 错 | 用 `XE` 作为服务名，而非 SID；`lsnrctl status` 查 |

---

## 7. 安全与注意事项
- 不要将 `1521` 暴露到公网；仅放行业务/管理网段。
- `ORACLE_PASSWORD` / `APP_USER_PASSWORD` 用强口令，建议后续改为从 secret/环境变量注入，不要写死进仓库。
- XE 不适合重负载生产；如业务增长，迁移到 SE/EE（官方镜像 + 许可）。
- 该 VM 与 k3s 集群是**独立故障域**：Oracle 挂了不影响 galaxy-mall（若 galaxy-mall 不依赖它的话）；反之亦然。

---

## 8. 后续待办（需执行时再操作）
- [ ] 在 Proxmox 上创建 Debian 12 VM（或 LXC+nesting）并配置静态 IP / 防火墙。
- [ ] 安装 Docker（第 3 节）。
- [ ] 部署 `docker-compose.yml` 并等待建库完成（第 4 节）。
- [ ] 创建业务表空间/用户，交付连接串给应用（第 5 节）。
- [ ] 配置定期备份（第 6 节）。
- [ ] （可选）将 Oracle 连接信息接入 k8s Secret / 应用配置。

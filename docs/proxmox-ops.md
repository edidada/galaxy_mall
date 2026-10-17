# Proxmox 运维文档：Ubuntu 26.04 LTS cloud-init 模板与虚拟机创建

> 适用场景：在 Proxmox VE 主机上制作 Ubuntu 26.04 LTS 云镜像模板，并按需克隆出编译/开发用虚拟机。
> 文档内容基于实测环境，命令可直接复制执行。**推荐用 cloud-init 模板，无需交互式安装系统。**

## 1. 环境信息

| 项目 | 值 |
|---|---|
| Proxmox 主机 | `192.168.31.171`（Web 管理台 https://192.168.31.171:8006 ，注意 8006 是 Web 界面，**不是 SSH 端口**） |
| SSH 端口 | 22（root 登录，Web 控制台上的 "node/MiWiFi-RD16-srv" 即本机） |
| 系统版本 | Proxmox VE 8.4.0，内核 6.8.12-9-pve |
| 硬件 | 2× Intel Xeon E5-2680 v3 @2.5GHz，共 48 逻辑核；内存 64 GiB |
| 主存储 | `local`（dir，镜像目录 `/var/lib/vz/template/iso/`）、`local-lvm`（lvmthin，虚拟机磁盘） |
| 网络桥接 | `vmbr0`（192.168.31.0/24，DHCP 由 MiWiFi 路由器分配） |

## 2. 现有镜像清单

```bash
ls -lh /var/lib/vz/template/iso/
```

| 镜像 | 大小 | 说明 |
|---|---|---|
| `CentOS-Stream-9-latest-x86_64-boot.iso` | 1.5G | ✅ 已有（102 work2 模板用） |
| `ubuntu-24.04.4-live-server-amd64.iso` | 3.2G | ✅ 已有（105 Ubuntu24+OracleXE 用） |
| `ubuntu-26.04-server-cloudimg-amd64.img` | 825M | ✅ 已有（**26.04 cloud 镜像，做模板用**） |

## 3. 下载 Ubuntu 26.04 cloud 镜像（aria2 多连接）

**重要**：直连 `cloud-images.ubuntu.com` 单连接仅 ~70 KB/s（会卡几小时）；用 `aria2c` 16 连接并行可达 **~1.5 MB/s**，825MiB 约 9 分钟。国内镜像（清华/阿里/华为）的 cloud-images 路径实测 404/403，不可用。

```bash
# Proxmox 主机上执行（若无 aria2: apt-get install -y aria2）
rm -f /var/lib/vz/template/iso/ubuntu-26.04-server-cloudimg-amd64.img
nohup aria2c -x 16 -s 16 -k 1M \
  -d /var/lib/vz/template/iso -o ubuntu-26.04-server-cloudimg-amd64.img \
  "https://cloud-images.ubuntu.com/releases/26.04/release/ubuntu-26.04-server-cloudimg-amd64.img" \
  > /tmp/aria.log 2>&1 &

# 监控进度（注意：ls 看到的是预分配大小，真实进度看日志）
tail -3 /tmp/aria.log    # 形如 [#53ee30 5.6MiB/825MiB(0%) CN:16 DL:1.5MiB ETA:9m2s]
```

> 302 会重定向到 `.../releases/resolute/release/...`（`resolute` 为 26.04 代号），aria2/wget 会自动跟随。

## 4. 制作 cloud-init 模板（VMID 106）

**关键顺序：cloud-init 相关配置必须在 `qm template` 之前设置**，否则 cloud-init 盘不会生成，克隆出的机器将没有密码/公钥/主机名（本次实测踩坑）。

```bash
IMG=/var/lib/vz/template/iso/ubuntu-26.04-server-cloudimg-amd64.img
TEMPLATE=106

# 1) 建 VM（模板规格 2C/4G）
qm create $TEMPLATE --name ubuntu-2604-tmpl --ostype l26 --cores 2 --memory 4096 \
  --net0 virtio,bridge=vmbr0 --agent enabled=1

# 2) 导入 cloud 镜像为磁盘并挂载
qm importdisk $TEMPLATE "$IMG" local-lvm --format qcow2
DISK=$(qm config $TEMPLATE | grep -oE "local-lvm:vm-$TEMPLATE-disk-[0-9]+" | head -1)
qm set $TEMPLATE --scsi0 "$DISK"

# 3) cloud-init 配置（注意 --sshkeys 传【文件路径】，不是公钥内容！）
qm set $TEMPLATE --ciuser ubuntu --cipassword "5Edidada" --sshkeys /tmp/proxmon.pub
qm set $TEMPLATE --ipconfig0 ip=dhcp
qm set $TEMPLATE --boot order=scsi0

# 4) 扩模板系统盘（cloud 镜像虚拟盘仅 3.5G，建议扩到 30G）
qm resize $TEMPLATE scsi0 +26G

# 5) 确认 cloud-init 盘已生成，再转模板
qm config $TEMPLATE | grep ide2      # 必须能看到 ide2: local-lvm:vm-106-cloudinit,media=cdrom
qm template $TEMPLATE
```

### 踩坑记录
| 坑 | 现象 | 正确做法 |
|---|---|---|
| `--sshkeys` 传公钥内容 | `can't open 'ssh-rsa AAAA...' - No such file or directory` | 传**文件路径**，如 `--sshkeys /tmp/proxmon.pub` |
| 转模板后才补 cloud-init 配置 | 克隆机无密码/无公钥/主机名仍为 `ubuntu`，sshd 可能未起 | **先配 cloud-init 再 `qm template`**；若模板已建，可 `qm set <tmpl> --ide2 local-lvm:cloudinit` 补盘后重新克隆 |
| cloud 镜像虚拟盘仅 3.5G | 克隆机根分区 96% 满 | 模板盘先 `qm resize`，克隆后重启由 cloud-init 自动 growpart+resize2fs |

## 5. 从模板克隆虚拟机

```bash
# 示例：克隆一台 12C/24G 编译机（VMID 107）
qm clone 106 107 --name ubuntu26-build --full
qm set 107 --cores 12 --memory 24576
qm resize 107 scsi0 +20G     # 模板 30G → 50G（按需）
qm start 107
```

编译机资源建议（C++/Rust）：
| 场景 | vCPU | 内存 |
|---|---|---|
| 小项目 | 4 | 8G |
| 中等项目（推荐） | 8 | 16G |
| 大型 workspace / LTO | 12–16 | 24–32G |

Rust 每个并行 rustc 作业约占 1–2G，LTO 链接峰值可达 8–16G；并行数建议 ≤ 内存/2G。

## 6. 克隆机首次启动（约 1–2 分钟）

cloud-init 自动完成：设主机名（=VM 名）、创建 `ubuntu` 用户、写入密码与公钥、DHCP、**growpart 扩根分区**。

验证（SSH 用 `~/.ssh/id_rsa_proxmon` 私钥，用户 `ubuntu`，密码 `5Edidada`）：

```bash
ssh -i ~/.ssh/id_rsa_proxmon ubuntu@<VM的IP> "df -h /; free -h; nproc"
```

### 获取 VM IP 的三种方式
1. **装了 qemu-guest-agent（推荐）**：`qm guest cmd 107 network-get-interfaces`
2. Proxmox Web UI 的 VM `Summary` 页直接显示
3. 未装 agent 时按 MAC 反查：先 `qm config 107 | grep net0` 拿 MAC，再
   `for i in $(seq 1 254); do ( ping -c1 -W1 192.168.31.$i >/dev/null 2>&1 ) & done; wait; ip neigh | grep -i <MAC>`

> ⚠️ DHCP 地址**重启后可能变化**（实测 .252 → .253），务必以 guest-agent 或 ARP 实查为准，不要记忆旧 IP。

### 安装 qemu-guest-agent（强烈建议）
```bash
# 先换阿里云源加速（26.04 用 deb822 格式）
sudo sed -i.bak "s|archive.ubuntu.com/ubuntu|mirrors.aliyun.com/ubuntu|g; s|security.ubuntu.com/ubuntu|mirrors.aliyun.com/ubuntu|g" /etc/apt/sources.list.d/ubuntu.sources
sudo apt-get update && sudo apt-get install -y qemu-guest-agent
sudo systemctl enable --now qemu-guest-agent
```

### 编译环境建议安装
```bash
sudo apt-get install -y build-essential ccache curl git
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh   # rustup
cargo install sccache; sudo apt-get install -y mold               # Rust 缓存+快速链接器
```

## 7. Netdata 监控（宿主机零配置监控所有 VM）

在 PVE 宿主机装 Netdata 即可**零配置自动发现并监控所有虚拟机**，无需在 guest 内装 agent（看的是"虚拟机边界"层指标）。

```bash
# Proxmox 主机（Debian 12）：apt 源只有 netdata 1.37.1
apt-get install -y netdata

# 建议升级到官方 2.x（kickstart 走 packagecloud）
curl -sSL -o /tmp/ks.sh https://my-netdata.io/kickstart.sh
bash /tmp/ks.sh --disable-telemetry --stable-channel     # → 2.12.1
```

> ⚠️ kickstart 若报 `Could not install using native binary packages` 而中止，是因为
> 宿主机 `apt-get update` 本身失败（kickstart 依赖它），需先修 apt 源：
> - 注释掉无订阅必然 401 的 `enterprise.proxmox.com`（`/etc/apt/sources.list.d/pve-enterprise.list`、`ceph.list`）
> - 把 `https://mirrors.aliyun.com/debian/ bookworm-security` 改为
>   `https://mirrors.aliyun.com/debian-security/ bookworm-security`（**注意 URL 结尾有斜杠**，漏掉则 sed 匹配不上）
> - 已存在旧 netdata 时 kickstart 会"用发行版包重装"而不升级，需先移除 netdata 各包再装
>   （`apt-get remove -y $(dpkg -l | grep -iE "^ii +netdata" | awk '{print $2}')`，**不要用 autoremove**，避免误删宿主机其它包）

- 面板：http://192.168.31.171:19999
- **自动发现已实测**：Netdata 通过 cgroups 采集器识别每个 `machine-qemu-<vmid>` 作用域，生成 per-VM 的 CPU/内存/磁盘IO/网络/pressure 图表（实测 147 个 `cgroup_*` 图表、132 个 qemu 相关图表）。
- **能力边界**：看到的是**虚拟机边界**指标（每台 VM 占多少 CPU/内存/IO/带宽）；**看不到 guest 内部**（进程、guest 磁盘剩余、guest load）。要这些需在 guest 内也跑 netdata（或 node_exporter）。

### 访问控制语法：simple pattern（**不支持 CIDR**）
netdata 的 `allow connections from` 用的是 **simple pattern**（源码 `daemon/main.c` 中
`simple_pattern_create(config_get(CONFIG_SECTION_WEB, "allow connections from", ...))`）。
它支持**空格分隔的多值 + 通配符 `*`**，但**完全不认 CIDR**。一旦写了 `/24`，整条 pattern 解析失效
→ **deny-all**（连 127.0.0.1 都被 reset，表现为面板彻底打不开）。

```bash
# 错误（含 CIDR → deny all，连 127.0.0.1 都会 reset）：
[web]
    allow connections from = 192.168.31.0/24

# 正确（空格分隔多值 + 通配符 *，实测通过）：
[web]
    bind to = *
    allow connections from = localhost 192.168.31.*
```

> 排查口诀：面板打不开时先 `curl -s -o /dev/null -w "%{http_code}" http://127.0.0.1:19999`
> —— 若**连本机自己**都返回 000，就是被这条白名单 deny 了（前提：服务 active、端口在 listen、防火墙 INPUT ACCEPT）。

### 子节点流式上报（把 VM 内部指标汇总到宿主机面板）
宿主机侧只能看到"虚拟机边界"指标；要看到 VM 内部，需在 VM 内也装 netdata 并流式上报到宿主机。

```bash
# 1) VM 内装 netdata（Ubuntu 26.04 的 apt 源没有 netdata 包，必须用官方 kickstart 走 packagecloud）
curl -sSL -o /tmp/ks.sh https://my-netdata.io/kickstart.sh
sudo bash /tmp/ks.sh --disable-telemetry --stable-channel
```

```bash
# 2) 子节点(VM)：写 /etc/netdata/stream.conf —— 注意是 stream.conf，不是 netdata.conf！
[stream]
    enabled = yes
    destination = 192.168.31.171:19999
    api key = <UUID>
    send charts matching = *
```

```bash
# 3) 父节点(宿主机)：必须在 /etc/netdata/stream.conf 登记该 API key，否则子节点推不进来
#    （父节点样本 stream.conf 原文：If the key is not listed here, it will not be able to push metrics）
[<第 2 步那个 UUID，两端要逐字符一致>]
    type = api
    enabled = yes
    allow from = 192.168.31.*
```

```bash
# 4) 父节点 /etc/netdata/netdata.conf：接收开关 + web 白名单放行子节点 IP
[stream]
    enabled = yes

[web]
    bind to = *
    allow connections from = localhost 192.168.31.*
```

两边 `systemctl restart netdata` 后验证：

```bash
# 父节点面板应出现 VM 主机名
curl -s http://192.168.31.171:19999/api/v1/info | jq .mirrored_hosts
# 期望：["Proxmox 宿主机", "ubuntu26-build"]

# 子节点侧确认连接已建立
ss -tn | grep 19999        # ESTAB  <VM_IP>:xxxxx  192.168.31.171:19999
```

> ⚠️ **两个最易踩的静默失败**（都无日志报错，极难排查）：
> 1. 父节点不登记 `[API_KEY]` → 子节点连不上，且**双方日志都是空的**。
> 2. 流式走 API 端口，**同一个 IP 必须也在 `netdata.conf [web].allow connections from` 里放行**
>    （父节点样本原文：the same IPs should also be matched at netdata.conf [web].allow connections from）。

> 注：PVE 宿主防火墙默认 INPUT ACCEPT（并未挡 19999）；Netdata 资源开销极小。
> 面板中文显示名可在 `/etc/netdata/netdata.conf` 的 `[global]` 段设 `hostname = Proxmox 宿主机`
> （**该值不要加引号**，netdata 会把引号并入字符串，序列化后变成 `""名称""`）。

## 8. 现有虚拟机清单

| VMID | 名称 | 规格 | 说明 |
|---|---|---|---|
| 100 | wdidada | — | k3s-master（192.168.31.77） |
| 101 | worker1 | — | k3s-worker1（192.168.31.211） |
| 102 | work2 | — | CentOS Stream 9 **模板** |
| 103 | work3 | — | k3s-worker2（192.168.31.251） |
| 104 | work4 | — | k3s-worker3（192.168.31.88） |
| 105 | — | — | **Ubuntu 24.04 + Oracle XE**（已占用，勿动） |
| 106 | ubuntu-2604-tmpl | 2C/4G/30G | **Ubuntu 26.04 cloud-init 模板**（勿启动） |
| 107 | ubuntu26-build | 12C/24G/50G | Ubuntu 26.04.1 编译机（DHCP） |

SSH 登录模板系克隆机：`ssh -i ~/.ssh/id_rsa_proxmon ubuntu@<IP>`（私钥 `id_rsa_proxmon` / 公钥注释 `proxmon@galaxy_mall`）。

## 9. 备注

- 本 Proxmox 为**社区版（无订阅）**，**不限制 vCPU/内存上限**（新建向导预填的 "2" 只是默认值，上限为宿主机 48 核）。付费订阅仅去弹窗/企业源/技术支持，不解锁资源。
- Proxmox 主机无代理，直连出网实测带宽约 0.5 MB/s（阿里云）；大文件下载一律用 **aria2c 多连接**。
- 无界面排障技巧：QMP 截屏看控制台 ——
  `printf '{"execute":"qmp_capabilities"}\n{"execute":"screendump","arguments":{"filename":"/tmp/s.ppm"}}\n' | socat - UNIX-CONNECT:/var/run/qemu-server/<VMID>.qmp`
  （PPM 可用纯 Python 标准库转 PNG 查看；`sendkey` 还可向 tty1 注入键盘登录排障。）
- 若需把克隆机加入 k3s 作为节点，步骤同 worker 扩容（参考集群运维记录）。

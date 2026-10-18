#!/usr/bin/env bash
# 给 galaxy-mall 补 requests/limits + 调度约束，并把业务 Pod 从 control-plane 挪走。
# 设计约束（都是 2026-10-18 实测出来的，别当风格问题看）：
#   1) 现网这 7 个 Deployment 全部没有 resources，`kubectl describe node` 里
#      ephemeral-storage  requests 是 0，调度器看到的压力和真实压力差一个量级
#      （master 宿主 free 62%、kubectl top 59%），所以 requests 必须补齐；
#   2) 只用 kubectl patch --type=strategic，不用 kubectl apply：
#      ci_cd/rendered/galaxy-mall.yaml 的 image 是渲染出来的 tag（现网是 git-<sha>，
#      由 GitHub Actions 推的），apply 会把 image 打回占位 tag，等于把线上版本回滚。
#      strategic patch 按容器 name 合并，只动我写的字段。
#   3) 不给 worker 打 taint（见文末 NOTE），只用 nodeSelector 做单向隔离。
set -euo pipefail

K="${K:-k3s kubectl}"
NS="${NS:-galaxy-mall}"
POOL="${POOL:-app}"

log() { printf '\033[36m[sched]\033[0m %s\n' "$*"; }

# 节点池标签：幂等，重复执行无副作用
label_nodes() {
  $K label node k3s-master    galaxy.io/pool=control-plane --overwrite
  $K label node k3s-worker1   galaxy.io/pool=app           --overwrite
  $K label node k3s-worker4   galaxy.io/pool=app           --overwrite
  $K label node k3s-worker2   galaxy.io/pool=data          --overwrite
  $K label node k3s-worker3   galaxy.io/pool=data          --overwrite
  log "节点池: master=control-plane, worker1/4=app, worker2/3=data"
}

# Java 服务：实测常驻 RSS 330~460Mi，limit 给 1Gi（JVM 默认 MaxRAMPercentage=25
# → 堆上限约 256Mi，这个 lab 的商品/订单数据量很小，够用且能压住 OOM 风险；
# 若后续接入 micrometer 直方图或订单分表数据变大，先加 -XX:MaxRAMPercentage 再调 limit）
# 反亲和只针对 doit 里的"重户"：Doris FE/BE 单 Pod 实测 1211m CPU / 1.5Gi，
# Flink jobmanager+taskmanager 实测 1.7Gi —— 这些才是不能和业务挤一个节点的。
# kafka/hbase（473Mi / 356Mi）故意不写进名单：第一轮把它们也加进去时，
# weight=100 直接把 worker1 判死，调度器把 8/10 个 Pod 全压到 worker4，
# 拓扑均分反而失效。"隔重户、和轻中间件共存"才是真能拉开 5/5 分布的组合。
# ⚠️ startupProbe 是这次加了 requests/limits 之后"被逼出来"的修正，不是顺手优化：
# 加了 cpu limit（1000m）以后，一次 rollout 里 5~6 个 JVM 同时在 4 核节点上启动，
# 被限流的 JVM 起 Spring Cloud Gateway 用了 40s+，而线上模板只有
# liveness(period 10s × failureThreshold 3)=30s 的容忍 → 容器被反复杀掉重启
# （实测 galaxy-mall-gateway-7975f89cd7-kzd9n "Container gateway failed liveness
# probe, will be restarted"）。补 startupProbe 让启动期不受 liveness 计时约束。
# 另有一条"同服务两个副本别挤同一节点"的自反亲和（weight 90）：只做整组
# topologySpread 会出现 order 两副本都在 worker4、stock 两副本都在 worker1 ——
# 数量 5/5 但 worker4 一挂就同时失去 gateway/order/payment 的全部副本，
# 自反亲和才是按服务维度的真 HA。（strategic merge 整体替换
# preferredDuringScheduling 数组，所以两条必须每次一起下发。）
patch_java() {
  local dep="$1" container="$2" port="$3"
  $K -n "$NS" patch deployment "$dep" --type=strategic -p "$(cat <<JSON
{
  "spec": {
    "template": {
      "metadata": { "labels": { "app.kubernetes.io/part-of": "galaxy-mall" } },
      "spec": {
        "nodeSelector": { "galaxy.io/pool": "${POOL}" },
        "topologySpreadConstraints": [
          {
            "maxSkew": 1,
            "topologyKey": "kubernetes.io/hostname",
            "whenUnsatisfiable": "ScheduleAnyway",
            "labelSelector": {
              "matchLabels": { "app.kubernetes.io/part-of": "galaxy-mall" }
            }
          }
        ],
        "affinity": {
          "podAntiAffinity": {
            "preferredDuringSchedulingIgnoredDuringExecution": [
              {
                "weight": 90,
                "podAffinityTerm": {
                  "topologyKey": "kubernetes.io/hostname",
                  "labelSelector": { "matchLabels": { "app": "${dep}" } }
                }
              },
              {
                "weight": 100,
                "podAffinityTerm": {
                  "topologyKey": "kubernetes.io/hostname",
                  "namespaces": ["doit"],
                  "labelSelector": {
                    "matchExpressions": [
                      { "key": "app", "operator": "In",
                        "values": ["business-doris-be", "business-doris-fe",
                                   "realtime-dw-etl-01"] }
                    ]
                  }
                }
              }
            ]
          }
        },
        "containers": [
          {
            "name": "${container}",
            "startupProbe": {
              "httpGet": { "path": "/actuator/health/liveness", "port": ${port} },
              "periodSeconds": 5,
              "failureThreshold": 36
            },
            "resources": {
              "requests": { "cpu": "200m", "memory": "512Mi", "ephemeral-storage": "512Mi" },
              "limits":   { "cpu": "1000m", "memory": "1Gi",  "ephemeral-storage": "2Gi" }
            }
          }
        ]
      }
    }
  }
}
JSON
)"
  log "patched ${NS}/${dep} (java)"
}

# 前端是 nginx + 静态资源，两个量级都小一号
patch_front() {
  $K -n "$NS" patch deployment front --type=strategic -p "$(cat <<JSON
{
  "spec": {
    "template": {
      "metadata": { "labels": { "app.kubernetes.io/part-of": "galaxy-mall" } },
      "spec": {
        "nodeSelector": { "galaxy.io/pool": "${POOL}" },
        "topologySpreadConstraints": [
          {
            "maxSkew": 1,
            "topologyKey": "kubernetes.io/hostname",
            "whenUnsatisfiable": "ScheduleAnyway",
            "labelSelector": {
              "matchLabels": { "app.kubernetes.io/part-of": "galaxy-mall" }
            }
          }
        ],
        "containers": [
          {
            "name": "front",
            "resources": {
              "requests": { "cpu": "25m", "memory": "32Mi", "ephemeral-storage": "128Mi" },
              "limits":   { "cpu": "200m", "memory": "128Mi", "ephemeral-storage": "512Mi" }
            }
          }
        ]
      }
    }
  }
}
JSON
)"
  log "patched ${NS}/front (nginx)"
}

# broker / redis 的 local-path PV 绑死在 master 上。
# 给 master 打 control-plane:NoSchedule 之前必须先加容忍，否则它们一旦重启
# 就永远 Pending（PV 的 nodeAffinity 只允许 master，taint 又不让上 master）。
patch_stateful_tolerations() {
  $K -n middleware patch statefulset rocketmq-broker --type=strategic -p '{
    "spec": {"template": {"spec": {"tolerations": [
      {"key": "node-role.kubernetes.io/control-plane", "operator": "Exists", "effect": "NoSchedule"}
    ]}}}}'
  $K -n middleware patch deployment redis --type=strategic -p '{
    "spec": {"template": {"spec": {"tolerations": [
      {"key": "node-role.kubernetes.io/control-plane", "operator": "Exists", "effect": "NoSchedule"}
    ]}}}}'
  log "middleware/rocketmq-broker + middleware/redis 已容忍 control-plane"
}

taint_master() {
  # 用 key:NoSchedule（operator Exists、无 value），和 kube-system 组件里已有的
  # {"key":"node-role.kubernetes.io/control-plane","operator":"Exists","effect":"NoSchedule"}
  # 容忍精确对应；写成 key=:NoSchedule 会变成 value="" 这条不同的污点。
  $K taint node k3s-master node-role.kubernetes.io/control-plane:NoSchedule --overwrite
  log "k3s-master 已打 control-plane:NoSchedule（不驱逐现存 Pod，只挡新调度）"
}

wait_rollout() {
  for d in galaxy-mall-gateway galaxy-mall-user galaxy-mall-product galaxy-mall-stock galaxy-mall-order galaxy-mall-payment front; do
    $K -n "$NS" rollout status "deployment/$d" --timeout=240s
  done
  $K -n middleware rollout status "statefulset/rocketmq-broker" --timeout=240s
  $K -n middleware rollout status "deployment/redis" --timeout=240s
}

case "${1:-all}" in
  labels)  label_nodes ;;
  patch)   label_nodes; patch_java galaxy-mall-gateway gateway 8080; patch_java galaxy-mall-user user 8081;
           patch_java galaxy-mall-product product 8082; patch_java galaxy-mall-stock stock 8083;
           patch_java galaxy-mall-order order 8084; patch_java galaxy-mall-payment payment 8085;
           patch_front ;;
  stateful) patch_stateful_tolerations ;;
  taint)   taint_master ;;
  wait)    wait_rollout ;;
  all)     "$0" patch; "$0" wait ;;
  *)       echo "用法: $0 [labels|patch|stateful|taint|wait|all]"; exit 1 ;;
esac

# NOTE（为什么不顺手给 worker 也打 taint 做双向隔离）：
#   a) kube-system/svclb-traefik-* 的 tolerations 里只有 control-plane 与常见内置污点，
#      没有自定义 key。给 worker1/worker4 打 galaxy.io/pool=app:NoSchedule，
#      这两个节点上的 svclb Pod 一旦重建就起不来 —— 那是 Traefik 的 NodePort 入口，
#      等于为了隔离把入口搞挂；
#   b) doit 的 doris/kafka/hbase/flink 都挂了 local-path PVC（8Gi/5Gi/5Gi/50Gi，
#      PV 带 nodeAffinity）。给 worker2/worker3 打 data:NoSchedule 同样会让它们
#      重建时卡 Pending。
#   真要双向硬隔离，正确顺序是：先给 svclb DaemonSet + traefik Deployment +
#   这些带卷的工作负载补 toleration（traefik/svclb 改 /var/lib/rancher/k3s/server/manifests/traefik.yaml
#   才不会被 reconcile 覆盖），确认 Pod 能重建，再打 taint。

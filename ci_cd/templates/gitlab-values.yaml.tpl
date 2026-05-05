global:
  edition: ce
  time_zone: __TIME_ZONE__
  hosts:
    domain: __BASE_DOMAIN__
    externalIP: __K3S_NODE_IP__
    https: false
    gitlab:
      name: __GITLAB_HOST__
      https: false
    registry:
      name: __GITLAB_REGISTRY_HOST__
      https: false
  ingress:
    class: traefik
    configureCertmanager: false
    provider: traefik
  psql:
    host: gitlab-postgresql.__GITLAB_NAMESPACE__.svc.cluster.local
    port: 5432
    database: __GITLAB_POSTGRES_DB__
    username: __GITLAB_POSTGRES_USER__
    password:
      secret: gitlab-postgres-secret
      key: postgres-password
  redis:
    host: gitlab-redis.__GITLAB_NAMESPACE__.svc.cluster.local
    port: 6379
    auth:
      enabled: true
      secret: gitlab-redis-secret
      key: redis-password
  initialRootPassword:
    secret: gitlab-initial-root-password
    key: password

installCertmanager: false

certmanager:
  installCRDs: false

nginx-ingress:
  enabled: false

postgresql:
  install: false

redis:
  install: false

prometheus:
  install: false

gitlab-runner:
  install: false

gitlab:
  webservice:
    minReplicas: 1
  sidekiq:
    minReplicas: 1
  gitlab-shell:
    minReplicas: 1
    service:
      type: NodePort
      nodePort: 32022
  kas:
    enabled: false

registry:
  enabled: true

minio:
  persistence:
    size: 30Gi

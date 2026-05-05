apiVersion: v1
kind: Secret
metadata:
  name: gitlab-postgres-secret
  namespace: __GITLAB_NAMESPACE__
type: Opaque
stringData:
  postgres-password: "__GITLAB_POSTGRES_PASSWORD__"
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: gitlab-postgresql
  namespace: __GITLAB_NAMESPACE__
spec:
  replicas: 1
  selector:
    matchLabels:
      app: gitlab-postgresql
  template:
    metadata:
      labels:
        app: gitlab-postgresql
    spec:
      containers:
        - name: postgresql
          image: postgres:16
          ports:
            - containerPort: 5432
              name: postgres
          env:
            - name: POSTGRES_DB
              value: "__GITLAB_POSTGRES_DB__"
            - name: POSTGRES_USER
              value: "__GITLAB_POSTGRES_USER__"
            - name: POSTGRES_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: gitlab-postgres-secret
                  key: postgres-password
            - name: TZ
              value: "__TIME_ZONE__"
          volumeMounts:
            - name: data
              mountPath: /var/lib/postgresql/data
          readinessProbe:
            tcpSocket:
              port: 5432
            initialDelaySeconds: 15
            periodSeconds: 10
      volumes:
        - name: data
          persistentVolumeClaim:
            claimName: gitlab-postgresql-pvc
---
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: gitlab-postgresql-pvc
  namespace: __GITLAB_NAMESPACE__
spec:
  accessModes:
    - ReadWriteOnce
  resources:
    requests:
      storage: 50Gi
---
apiVersion: v1
kind: Service
metadata:
  name: gitlab-postgresql
  namespace: __GITLAB_NAMESPACE__
spec:
  selector:
    app: gitlab-postgresql
  ports:
    - port: 5432
      targetPort: 5432

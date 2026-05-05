apiVersion: v1
kind: Secret
metadata:
  name: redis-secret
  namespace: __MIDDLEWARE_NAMESPACE__
type: Opaque
stringData:
  redis-password: "__REDIS_PASSWORD__"
---
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: redis-pvc
  namespace: __MIDDLEWARE_NAMESPACE__
spec:
  accessModes:
    - ReadWriteOnce
  resources:
    requests:
      storage: 20Gi
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: redis
  namespace: __MIDDLEWARE_NAMESPACE__
spec:
  replicas: 1
  selector:
    matchLabels:
      app: redis
  template:
    metadata:
      labels:
        app: redis
    spec:
      containers:
        - name: redis
          image: redis:7.2-alpine
          command:
            - sh
            - -c
            - exec redis-server --appendonly yes --requirepass "$REDIS_PASSWORD"
          env:
            - name: REDIS_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: redis-secret
                  key: redis-password
          ports:
            - containerPort: 6379
              name: redis
          volumeMounts:
            - name: data
              mountPath: /data
      volumes:
        - name: data
          persistentVolumeClaim:
            claimName: redis-pvc
---
apiVersion: v1
kind: Service
metadata:
  name: redis
  namespace: __MIDDLEWARE_NAMESPACE__
spec:
  selector:
    app: redis
  ports:
    - port: 6379
      targetPort: 6379

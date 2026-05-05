apiVersion: v1
kind: Secret
metadata:
  name: mysql-secret
  namespace: __MIDDLEWARE_NAMESPACE__
type: Opaque
stringData:
  mysql-root-password: "__MYSQL_ROOT_PASSWORD__"
  mysql-app-password: "__MYSQL_APP_PASSWORD__"
---
apiVersion: v1
kind: ConfigMap
metadata:
  name: mysql-initdb
  namespace: __MIDDLEWARE_NAMESPACE__
data:
  init.sql: |
    CREATE DATABASE IF NOT EXISTS galaxy_order_0 DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
    CREATE DATABASE IF NOT EXISTS galaxy_order_1 DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
    CREATE USER IF NOT EXISTS '__MYSQL_APP_USER__'@'%' IDENTIFIED BY '__MYSQL_APP_PASSWORD__';
    GRANT ALL PRIVILEGES ON galaxy_order_0.* TO '__MYSQL_APP_USER__'@'%';
    GRANT ALL PRIVILEGES ON galaxy_order_1.* TO '__MYSQL_APP_USER__'@'%';
    FLUSH PRIVILEGES;
---
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: mysql-pvc
  namespace: __MIDDLEWARE_NAMESPACE__
spec:
  accessModes:
    - ReadWriteOnce
  resources:
    requests:
      storage: 100Gi
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: mysql
  namespace: __MIDDLEWARE_NAMESPACE__
spec:
  replicas: 1
  selector:
    matchLabels:
      app: mysql
  template:
    metadata:
      labels:
        app: mysql
    spec:
      containers:
        - name: mysql
          image: mysql:8.0
          args:
            - --default-authentication-plugin=mysql_native_password
          ports:
            - containerPort: 3306
              name: mysql
          env:
            - name: MYSQL_ROOT_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: mysql-secret
                  key: mysql-root-password
            - name: TZ
              value: "__TIME_ZONE__"
          volumeMounts:
            - name: data
              mountPath: /var/lib/mysql
            - name: initdb
              mountPath: /docker-entrypoint-initdb.d
          readinessProbe:
            tcpSocket:
              port: 3306
            initialDelaySeconds: 20
            periodSeconds: 10
      volumes:
        - name: data
          persistentVolumeClaim:
            claimName: mysql-pvc
        - name: initdb
          configMap:
            name: mysql-initdb
---
apiVersion: v1
kind: Service
metadata:
  name: mysql
  namespace: __MIDDLEWARE_NAMESPACE__
spec:
  selector:
    app: mysql
  ports:
    - port: 3306
      targetPort: 3306

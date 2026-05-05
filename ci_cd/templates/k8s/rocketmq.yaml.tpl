apiVersion: v1
kind: Namespace
metadata:
  name: __MIDDLEWARE_NAMESPACE__
---
apiVersion: v1
kind: ConfigMap
metadata:
  name: rocketmq-broker-config
  namespace: __MIDDLEWARE_NAMESPACE__
data:
  broker.conf: |
    brokerClusterName=galaxy-mall-cluster
    brokerName=broker-a
    brokerId=0
    deleteWhen=04
    fileReservedTime=48
    brokerRole=ASYNC_MASTER
    flushDiskType=ASYNC_FLUSH
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: rocketmq-namesrv
  namespace: __MIDDLEWARE_NAMESPACE__
spec:
  replicas: 1
  selector:
    matchLabels:
      app: rocketmq-namesrv
  template:
    metadata:
      labels:
        app: rocketmq-namesrv
    spec:
      containers:
        - name: namesrv
          image: apache/rocketmq:5.3.4
          command:
            - sh
            - -c
            - exec ./mqnamesrv
          ports:
            - containerPort: 9876
              name: namesrv
---
apiVersion: v1
kind: Service
metadata:
  name: rocketmq-namesrv
  namespace: __MIDDLEWARE_NAMESPACE__
spec:
  selector:
    app: rocketmq-namesrv
  ports:
    - port: 9876
      targetPort: 9876
      name: namesrv
---
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: rocketmq-broker
  namespace: __MIDDLEWARE_NAMESPACE__
spec:
  serviceName: rocketmq-broker
  replicas: 1
  selector:
    matchLabels:
      app: rocketmq-broker
  template:
    metadata:
      labels:
        app: rocketmq-broker
    spec:
      containers:
        - name: broker
          image: apache/rocketmq:5.3.4
          env:
            - name: POD_IP
              valueFrom:
                fieldRef:
                  fieldPath: status.podIP
          command:
            - sh
            - -c
            - |
              cp /etc/rocketmq/broker.conf /tmp/broker.conf
              echo "brokerIP1=${POD_IP}" >> /tmp/broker.conf
              exec ./mqbroker -n rocketmq-namesrv:9876 -c /tmp/broker.conf
          ports:
            - containerPort: 10911
              name: remoting
            - containerPort: 10909
              name: ha
            - containerPort: 10912
              name: fast-remoting
          volumeMounts:
            - name: broker-config
              mountPath: /etc/rocketmq
            - name: broker-store
              mountPath: /home/rocketmq/store
      volumes:
        - name: broker-config
          configMap:
            name: rocketmq-broker-config
  volumeClaimTemplates:
    - metadata:
        name: broker-store
      spec:
        accessModes:
          - ReadWriteOnce
        resources:
          requests:
            storage: 20Gi
---
apiVersion: v1
kind: Service
metadata:
  name: rocketmq-broker
  namespace: __MIDDLEWARE_NAMESPACE__
spec:
  clusterIP: None
  selector:
    app: rocketmq-broker
  ports:
    - name: remoting
      port: 10911
      targetPort: 10911
    - name: ha
      port: 10909
      targetPort: 10909
    - name: fast-remoting
      port: 10912
      targetPort: 10912
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: rocketmq-dashboard
  namespace: __MIDDLEWARE_NAMESPACE__
spec:
  replicas: 1
  selector:
    matchLabels:
      app: rocketmq-dashboard
  template:
    metadata:
      labels:
        app: rocketmq-dashboard
    spec:
      containers:
        - name: dashboard
          image: apacherocketmq/rocketmq-dashboard:2.1.0
          env:
            - name: JAVA_OPTS
              value: "-Drocketmq.namesrv.addr=rocketmq-namesrv.__MIDDLEWARE_NAMESPACE__.svc.cluster.local:9876"
          ports:
            - containerPort: 8082
              name: http
---
apiVersion: v1
kind: Service
metadata:
  name: rocketmq-dashboard
  namespace: __MIDDLEWARE_NAMESPACE__
spec:
  type: NodePort
  selector:
    app: rocketmq-dashboard
  ports:
    - name: http
      port: 8082
      targetPort: 8082
      nodePort: 30808

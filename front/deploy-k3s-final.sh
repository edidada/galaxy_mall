
#!/bin/bash
export KUBECONFIG=~/.kube/config
echo "Deploying front..."

# Cleanup
rm -rf /tmp/front-deploy-final
mkdir -p /tmp/front-deploy-final
cd /tmp/front-deploy-final
tar -xzf /tmp/front-dist.tar.gz
cp /tmp/nginx.conf /tmp/front-deploy-final/

# Create ConfigMap
kubectl create configmap front-static --from-file=./ --dry-run=client -o yaml | kubectl apply -f -

# Create Deployment
cat << 'EOF' | kubectl apply -f -
apiVersion: apps/v1
kind: Deployment
metadata:
  name: front
  labels:
    app: front
spec:
  replicas: 1
  selector:
    matchLabels:
      app: front
  template:
    metadata:
      labels:
        app: front
    spec:
      containers:
        - name: front
          image: docker.io/library/nginx:alpine
          ports:
            - containerPort: 80
          volumeMounts:
            - name: static-files
              mountPath: /usr/share/nginx/html
            - name: nginx-conf
              mountPath: /etc/nginx/conf.d/default.conf
              subPath: nginx.conf
      volumes:
        - name: static-files
          configMap:
            name: front-static
        - name: nginx-conf
          configMap:
            name: front-static
EOF

# Create Service
cat << 'EOF' | kubectl apply -f -
apiVersion: v1
kind: Service
metadata:
  name: front
spec:
  selector:
    app: front
  ports:
    - protocol: TCP
      port: 80
      targetPort: 80
      nodePort: 30080
  type: NodePort
EOF

echo "=== Deployed! ==="
kubectl get pods,svc

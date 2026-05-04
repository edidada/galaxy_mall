
#!/bin/bash
echo "Creating front-static ConfigMap..."
rm -rf /tmp/front-deploy
mkdir -p /tmp/front-deploy
cd /tmp/front-deploy
tar -xzf /tmp/front-dist.tar.gz

echo "kubectl create configmap front-static --from-file=./"
# Create the configmap
kubectl create configmap front-static --from-file=./ --dry-run=client -o yaml > configmap.yaml
cat << 'EOF' > deployment.yaml
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

cat << 'EOF' > service.yaml
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

cat << 'EOF' > nginx.conf
server {
    listen 80;
    server_name localhost;
    root /usr/share/nginx/html;
    index index.html;
    location / {
        try_files $uri $uri/ =404;
    }
}
EOF

# Now apply
echo "Applying configs..."
kubectl apply -f configmap.yaml
kubectl apply -f deployment.yaml
kubectl apply -f service.yaml
echo "Done! Checking deployment..."
kubectl get pods,svc

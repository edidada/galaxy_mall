#!/bin/bash

IMAGE_NAME="galaxy-mall/front:latest"

echo "Building Docker image..."
docker build -t $IMAGE_NAME .

echo "Save image..."
OUTPUT_DIR="../deploy/docker/output"
mkdir -p $OUTPUT_DIR
docker save -o $OUTPUT_DIR/front-latest.tar $IMAGE_NAME

echo "Done. Image saved to $OUTPUT_DIR/front-latest.tar"
echo "To import to k3s: sudo k3s ctr images import $OUTPUT_DIR/front-latest.tar"

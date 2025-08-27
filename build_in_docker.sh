#!/usr/bin/env bash
set -e
IMAGE_NAME=adblock-builder:latest
docker build -t ${IMAGE_NAME} .
mkdir -p out
docker run --rm -v $(pwd)/out:/out ${IMAGE_NAME}
ls -l out
echo "APK(s) are in ./out"

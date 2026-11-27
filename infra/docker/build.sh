#!/usr/bin/env bash
# 从项目根目录 ai-cabinet/ 构建镜像
set -euo pipefail
TAG="${1:-latest}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

docker build -f infra/docker/trade-service.Dockerfile -t "ai-cabinet/trade-service:${TAG}" .
docker build -f infra/docker/device-service.Dockerfile -t "ai-cabinet/device-service:${TAG}" .
docker build -f infra/docker/postgres.Dockerfile -t "ai-cabinet/postgres:16-gosu-flat" .

echo "Built ai-cabinet/*:${TAG} and ai-cabinet/postgres:16-gosu-flat"

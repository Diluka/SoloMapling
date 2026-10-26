#!/bin/sh
set -eu

integration_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
repo_dir=$(dirname -- "$integration_dir")
cache_dir=${SOLO_MAVEN_CACHE:-${XDG_CACHE_HOME:-$HOME/.cache}/solomapling-maven}
mkdir -p "$cache_dir"

# 只挂载 Solo 仓库和 Maven 缓存，不把真实数据库或部署配置交给构建容器。
exec docker run --rm \
    --user "$(id -u):$(id -g)" \
    --volume "$repo_dir:$repo_dir" \
    --volume "$cache_dir:/maven-cache" \
    --workdir "$PWD" \
    --env MAVEN_CONFIG=/maven-cache \
    --env HOME=/tmp \
    maven:3.9.11-eclipse-temurin-21 \
    mvn -Dmaven.repo.local=/maven-cache/repository "$@"

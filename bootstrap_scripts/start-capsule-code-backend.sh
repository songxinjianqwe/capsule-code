#!/usr/bin/env bash
# 启动 capsule-code 后端（端口 8082）
# - mvn package（确保加载最新代码）
# - kill 掉 8082 LISTEN 进程
# - nohup 启动 + 日志到 $CAPSULE_ROOT/logs/capsule-code.log
#
# 脚本自定位：从 bootstrap_scripts/ 推断 capsule-code 根目录
set -euo pipefail

CAPSULE_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BACKEND_DIR="$CAPSULE_ROOT/backend"
LOG_DIR="$CAPSULE_ROOT/logs"

# MVN：优先系统 PATH；找不到再试常见安装位置
if command -v mvn >/dev/null 2>&1; then
  MVN="$(command -v mvn)"
elif [ -x "$HOME/dev/apache-maven-3.9.14/bin/mvn" ]; then
  MVN="$HOME/dev/apache-maven-3.9.14/bin/mvn"
else
  echo "[cc-backend] ERROR: mvn not found. Install Maven 3.9+ or set it in PATH." >&2
  exit 1
fi

mkdir -p "$LOG_DIR"

PORT=8082
PID=$(lsof -ti "tcp:$PORT" -sTCP:LISTEN || true)
if [ -n "$PID" ]; then
  echo "[cc-backend] killing existing pid=$PID on port $PORT"
  kill -9 "$PID"
  sleep 1
fi

cd "$BACKEND_DIR"
echo "[cc-backend] mvn package ..."
"$MVN" package -DskipTests -q

JAR=$(ls target/capsule-code-backend-*.jar | head -1)
if [ -z "$JAR" ]; then
  echo "[cc-backend] ERROR: no jar produced under target/" >&2
  exit 1
fi

nohup java -jar "$JAR" > "$LOG_DIR/capsule-code.log" 2>&1 &
NEW_PID=$!
echo "[cc-backend] started pid=$NEW_PID jar=$JAR"
echo "[cc-backend] log: $LOG_DIR/capsule-code.log"

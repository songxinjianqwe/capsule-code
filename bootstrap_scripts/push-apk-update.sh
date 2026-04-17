#!/usr/bin/env bash
# 给所有在线的 capsule-code app 推送 OTA 提示。
# 手机端 PushForegroundService 收到 body=="UPDATE_AVAILABLE" 后会自动调
# /app/version 对比 + 下载 /app/apk + 系统安装器。
#
# 用法：
#   bash push-apk-update.sh                     # 广播给所有在线设备
#   bash push-apk-update.sh <deviceId>          # 指向单台
set -euo pipefail
DEVICE_ID="${1:-all}"
BASE_URL="${CC_BASE_URL:-http://localhost:8082}"

curl --noproxy '*' -fsS -X POST "$BASE_URL/push/send" \
  -H "Content-Type: application/json" \
  -d "{\"deviceId\":\"$DEVICE_ID\",\"title\":\"Capsule Code\",\"body\":\"UPDATE_AVAILABLE\"}"
echo

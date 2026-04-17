#!/usr/bin/env bash
# 从主库 ai_playground 迁移 Claude 相关表到 capsule_code。
# 可重复执行（用 REPLACE，ID 冲突时覆盖）。
#
# 用法：
#   bash capsule_code_migrate.sh
#   bash capsule_code_migrate.sh --dry-run          # 只打印行数，不迁移
set -euo pipefail

DB_USER=${DB_USER:-root}
DB_PASS=${DB_PASS:-123456qwe}
SRC_DB=${SRC_DB:-ai_playground}
DST_DB=${DST_DB:-capsule_code}
TABLES=(claude_conversations claude_messages claude_sessions claude_invocation claude_invocation_round claude_usage_snapshot chat_file device_info device_heartbeat_log)

export MYSQL_PWD="$DB_PASS"

count() { mysql -u"$DB_USER" -N -B -e "SELECT COUNT(*) FROM $1.$2;"; }

echo "[migrate] source=$SRC_DB target=$DST_DB"
for t in "${TABLES[@]}"; do
  printf "[migrate] %-28s src=%8s dst=%8s" "$t" "$(count "$SRC_DB" "$t")" "$(count "$DST_DB" "$t" 2>/dev/null || echo '(missing)')"
  if [[ "${1:-}" == "--dry-run" ]]; then
    echo " [dry-run]"
    continue
  fi
  # --replace 覆盖主键相同的记录，保证幂等；--no-create-info 保留目标表结构
  mysqldump -u"$DB_USER" --no-create-info --skip-comments --replace \
    --default-character-set=utf8mb4 "$SRC_DB" "$t" | mysql -u"$DB_USER" "$DST_DB"
  echo " -> dst=$(count "$DST_DB" "$t")"
done
echo "[migrate] done"

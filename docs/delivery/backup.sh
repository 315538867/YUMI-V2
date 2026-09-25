#!/usr/bin/env bash
# 全量备份（任务 9.7）：MySQL 逻辑备份 + 文件目录快照 + 校验清单，可选异地加密。
#
# 用法：BACKUP_ROOT=/path/to/backups bash docs/delivery/backup.sh [标签]
#   口令来源：环境变量 YUMI_DB_PASSWORD，未设置时读 macOS 钥匙串 `yumi-v2-local-test`。
#   文件目录：环境变量 YUMI_FILES_DIR，默认 /tmp/yumi-files。
#   加密：设置 BACKUP_ENC_KEY_FILE 指向密钥文件时，产物额外做 aes-256-cbc 加密（异地保存用）。
set -euo pipefail

DB_NAME=${YUMI_DB_NAME:-yumi_v2_test}
DB_HOST=${YUMI_DB_HOST:-127.0.0.1}
DB_USER=${YUMI_DB_USER:-yumi_v2_test}
FILES_DIR=${YUMI_FILES_DIR:-/tmp/yumi-files}
BACKUP_ROOT=${BACKUP_ROOT:-/tmp/yumi-backups}
LABEL=${1:-$(date +%Y%m%d-%H%M%S)}
TARGET="$BACKUP_ROOT/$LABEL"

if [ -z "${YUMI_DB_PASSWORD:-}" ]; then
  YUMI_DB_PASSWORD=$(security find-generic-password -s yumi-v2-local-test -w)
fi
export MYSQL_PWD=$YUMI_DB_PASSWORD

mkdir -p "$TARGET"
echo "=== 备份 $DB_NAME@$DB_HOST → $TARGET ==="

# 1) 数据库一致性逻辑备份：--single-transaction 保证 InnoDB 同恢复点（不加锁、不需要 RELOAD 权限）；
#    需要 binlog 位点时设置 BACKUP_SOURCE_DATA=1（该选项要求 RELOAD 权限）。
SOURCE_DATA_FLAG=""
[ "${BACKUP_SOURCE_DATA:-0}" = "1" ] && SOURCE_DATA_FLAG="--source-data=2"
mysqldump --single-transaction --skip-lock-tables --no-tablespaces --quick --routines --triggers $SOURCE_DATA_FLAG \
  --default-character-set=utf8mb4 -h "$DB_HOST" -u "$DB_USER" "$DB_NAME" > "$TARGET/db.sql"
echo "db.sql $(wc -c < "$TARGET/db.sql" | tr -d ' ') bytes"

# 2) 文件快照：与数据库同一次备份批次（不一致的窗口由发布流程的停写步骤消除）
if [ -d "$FILES_DIR" ]; then
  tar -czf "$TARGET/files.tar.gz" -C "$(dirname "$FILES_DIR")" "$(basename "$FILES_DIR")"
  echo "files.tar.gz $(wc -c < "$TARGET/files.tar.gz" | tr -d ' ') bytes"
fi

# 3) 校验清单：版本、表数、关键表行数、文件 sha256
{
  echo "label=$LABEL"
  echo "db=$DB_NAME"
  echo "schema_version=$(mysql -h "$DB_HOST" -u "$DB_USER" -N -B "$DB_NAME" \
      -e 'SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success = 1')"
  echo "tables=$(mysql -h "$DB_HOST" -u "$DB_USER" -N -B -e \
      "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$DB_NAME'")"
  echo "orders=$(mysql -h "$DB_HOST" -u "$DB_USER" -N -B "$DB_NAME" -e 'SELECT COUNT(*) FROM orders')"
  echo "inventory_batches=$(mysql -h "$DB_HOST" -u "$DB_USER" -N -B "$DB_NAME" \
      -e 'SELECT COUNT(*) FROM inventory_batches')"
  echo "production_plans=$(mysql -h "$DB_HOST" -u "$DB_USER" -N -B "$DB_NAME" \
      -e 'SELECT COUNT(*) FROM production_plans')"
  echo "after_sales_cases=$(mysql -h "$DB_HOST" -u "$DB_USER" -N -B "$DB_NAME" \
      -e 'SELECT COUNT(*) FROM after_sales_cases')"
} > "$TARGET/manifest.txt"
cat "$TARGET/manifest.txt"

# 3b) 备份指标（Prometheus 文本文件，供 node_exporter textfile collector 采集 → 备份新鲜度/失败告警）
TEXTFILE_DIR=${BACKUP_TEXTFILE_DIR:-}
if [ -n "$TEXTFILE_DIR" ]; then
  mkdir -p "$TEXTFILE_DIR"
  NOW=$(date +%s)
  cat > "$TEXTFILE_DIR/yumi_backup.prom" <<EOF
# HELP yumi_backup_last_success_timestamp_seconds 最近一次成功备份的 Unix 时间戳
# TYPE yumi_backup_last_success_timestamp_seconds gauge
yumi_backup_last_success_timestamp_seconds $NOW
# HELP yumi_backup_failures_total 备份失败次数（成功执行本脚本即保持不增）
# TYPE yumi_backup_failures_total counter
yumi_backup_failures_total 0
EOF
  echo "textfile=$TEXTFILE_DIR/yumi_backup.prom"
fi

# 4) 可选：加密后异地保存（异地路径由 BACKUP_OFFSITE_DIR 指定）
if [ -n "${BACKUP_ENC_KEY_FILE:-}" ]; then
  openssl enc -aes-256-cbc -pbkdf2 -salt -pass "file:$BACKUP_ENC_KEY_FILE" \
    -in "$TARGET/db.sql" -out "$TARGET/db.sql.enc"
  [ -f "$TARGET/files.tar.gz" ] && openssl enc -aes-256-cbc -pbkdf2 -salt \
    -pass "file:$BACKUP_ENC_KEY_FILE" -in "$TARGET/files.tar.gz" -out "$TARGET/files.tar.gz.enc"
  rm -f "$TARGET/db.sql" "$TARGET/files.tar.gz"
  if [ -n "${BACKUP_OFFSITE_DIR:-}" ]; then
    mkdir -p "$BACKUP_OFFSITE_DIR"
    cp -p "$TARGET"/*.enc "$TARGET/manifest.txt" "$BACKUP_OFFSITE_DIR/"
    echo "offsite=$BACKUP_OFFSITE_DIR"
  fi
fi

# 5) 保留策略：只保留最近 BACKUP_KEEP 份（默认 14），按目录名（时间标签）倒序删除更旧的
BACKUP_KEEP=${BACKUP_KEEP:-14}
KEPT=0
for dir in $(ls -1d "$BACKUP_ROOT"/*/ 2>/dev/null | sort -r); do
  KEPT=$((KEPT + 1))
  if [ "$KEPT" -gt "$BACKUP_KEEP" ]; then
    rm -rf "$dir"
    echo "pruned=$(basename "$dir")"
  fi
done
echo "retained=$((KEPT < BACKUP_KEEP ? KEPT : BACKUP_KEEP))"

echo "=== 备份完成：$TARGET ==="

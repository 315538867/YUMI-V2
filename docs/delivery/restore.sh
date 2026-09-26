#!/usr/bin/env bash
# 恢复演练 / 真实恢复（任务 9.7）：把一份备份恢复到**目标库**，再逐项验证
# 「迁移版本、事实与投影一致性、关键只读路径」。默认恢复到演练库，不动生产库。
#
# 用法：
#   bash docs/delivery/restore.sh <备份目录> [目标库名]
#   目标库默认 yumi_v2_restore_drill（演练用；恢复生产请显式传生产库名并确认已停写）。
#   加密备份：设置 BACKUP_ENC_KEY_FILE，脚本会先解密到临时文件。
set -euo pipefail

BACKUP_DIR=${1:?用法: restore.sh <备份目录> [目标库名]}
TARGET_DB=${2:-yumi_v2_restore_drill}
DB_HOST=${YUMI_DB_HOST:-127.0.0.1}
DB_USER=${YUMI_DB_USER:-yumi_v2_test}
FILES_RESTORE_DIR=${YUMI_FILES_RESTORE_DIR:-/tmp/yumi-files-restored}

if [ -z "${YUMI_DB_PASSWORD:-}" ]; then
  YUMI_DB_PASSWORD=$(security find-generic-password -s yumi-v2-local-test -w)
fi
export MYSQL_PWD=$YUMI_DB_PASSWORD

SQL="$BACKUP_DIR/db.sql"
TMP_SQL=""
if [ ! -f "$SQL" ] && [ -f "$BACKUP_DIR/db.sql.enc" ]; then
  TMP_SQL=$(mktemp)
  openssl enc -d -aes-256-cbc -pbkdf2 -pass "file:${BACKUP_ENC_KEY_FILE:?需要 BACKUP_ENC_KEY_FILE}" \
    -in "$BACKUP_DIR/db.sql.enc" -out "$TMP_SQL"
  SQL="$TMP_SQL"
fi
[ -f "$SQL" ] || { echo "找不到备份的 db.sql / db.sql.enc"; exit 1; }

echo "=== 恢复到 ${TARGET_DB}（源备份 ${BACKUP_DIR}）==="
# 目标库名只允许字母/数字/下划线，避免标识符注入
case "$TARGET_DB" in
  *[!A-Za-z0-9_]*) echo "目标库名只允许字母/数字/下划线：$TARGET_DB"; exit 1 ;;
esac
mysql -h "$DB_HOST" -u "$DB_USER" -e "DROP DATABASE IF EXISTS $TARGET_DB;
  CREATE DATABASE $TARGET_DB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"
mysql --default-character-set=utf8mb4 -h "$DB_HOST" -u "$DB_USER" "$TARGET_DB" < "$SQL"
[ -n "$TMP_SQL" ] && rm -f "$TMP_SQL"

echo "--- ① 迁移版本与校验和（恢复库应含全部成功迁移）---"
mysql -h "$DB_HOST" -u "$DB_USER" -N -B "$TARGET_DB" -e \
  "SELECT CONCAT('installed=', COUNT(*), ' max_version=', MAX(CAST(version AS UNSIGNED)), ' failed=',
                 SUM(success = 0)) FROM flyway_schema_history;"

echo "--- ② 事实与投影一致性（三项，全部应为 0）---"
mysql -h "$DB_HOST" -u "$DB_USER" -N -B "$TARGET_DB" -e "
  SELECT CONCAT('履约余额不一致=', COUNT(*)) FROM order_item_fulfillment_balances b
   WHERE b.shippable_quantity <> COALESCE((SELECT SUM(CASE WHEN e.direction = 'IN' THEN e.quantity
        ELSE -e.quantity END) FROM fulfillment_entries e WHERE e.order_item_id = b.order_item_id
        AND e.entry_type IN ('INVENTORY_INFLOW', 'PRODUCTION_QUALIFIED')), 0)
     - COALESCE((SELECT SUM(e.quantity) FROM fulfillment_entries e WHERE e.order_item_id = b.order_item_id
        AND e.entry_type = 'SHIPMENT_CONSUME' AND e.direction = 'OUT'), 0)
     + COALESCE((SELECT SUM(e.quantity) FROM fulfillment_entries e WHERE e.order_item_id = b.order_item_id
        AND e.entry_type = 'SHIPMENT_VOID' AND e.direction = 'IN'), 0);
  SELECT CONCAT('库存批次不一致=', COUNT(*)) FROM inventory_batches b
   WHERE b.quantity <> COALESCE((SELECT SUM(CASE WHEN l.direction = 'IN' THEN l.quantity ELSE -l.quantity END)
        FROM inventory_movement_lines l WHERE l.batch_id = b.id), 0);
  SELECT CONCAT('售后已补发不一致=', COUNT(*)) FROM (
    SELECT i.id, COALESCE((SELECT SUM(e.quantity) FROM after_sales_fulfillment_entries e
        WHERE e.after_sales_item_id = i.id AND e.entry_type = 'REPLACEMENT_CONSUME'
          AND e.direction = 'OUT'), 0) AS shipped,
      COALESCE((SELECT SUM(l.quantity) FROM after_sales_shipment_links l
        WHERE l.after_sales_item_id = i.id), 0) AS linked
    FROM after_sales_items i) t WHERE t.shipped <> t.linked;"

echo "--- ③ 关键只读路径（恢复库可读到的核心事实计数）---"
mysql -h "$DB_HOST" -u "$DB_USER" -N -B "$TARGET_DB" -e "
  SELECT CONCAT('orders=', (SELECT COUNT(*) FROM orders),
                ' order_items=', (SELECT COUNT(*) FROM order_items),
                ' inventory_batches=', (SELECT COUNT(*) FROM inventory_batches),
                ' production_tasks=', (SELECT COUNT(*) FROM production_tasks),
                ' shipments=', (SELECT COUNT(*) FROM shipments),
                ' payments=', (SELECT COUNT(*) FROM payments),
                ' after_sales_cases=', (SELECT COUNT(*) FROM after_sales_cases));"

echo "--- ④ 文件快照（恢复到 ${FILES_RESTORE_DIR}）---"
if [ -f "$BACKUP_DIR/files.tar.gz" ]; then
  mkdir -p "$FILES_RESTORE_DIR"
  tar -xzf "$BACKUP_DIR/files.tar.gz" -C "$FILES_RESTORE_DIR"
  echo "files_restored=$(find "$FILES_RESTORE_DIR" -type f | wc -l | tr -d ' ')"
elif [ -f "$BACKUP_DIR/files.tar.gz.enc" ]; then
  mkdir -p "$FILES_RESTORE_DIR"
  openssl enc -d -aes-256-cbc -pbkdf2 -pass "file:${BACKUP_ENC_KEY_FILE:?需要 BACKUP_ENC_KEY_FILE}" \
    -in "$BACKUP_DIR/files.tar.gz.enc" | tar -xzf - -C "$FILES_RESTORE_DIR"
  echo "files_restored=$(find "$FILES_RESTORE_DIR" -type f | wc -l | tr -d ' ')"
else
  echo "无文件快照（该批次未包含）"
fi

echo "=== 恢复演练完成：${TARGET_DB}（演练库可在核对后 DROP）==="

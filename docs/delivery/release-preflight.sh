#!/usr/bin/env bash
# 发布前置检查（任务 9.6）：配置 / 磁盘 / 连接 / 迁移对齐 / 只读冒烟。
# 在**正式迁移之前**运行；任一项失败即退出非 0，发布流程应中止。
#
# 用法：bash docs/delivery/release-preflight.sh [最小空闲磁盘 MB，默认 512]
set -euo pipefail

DB_NAME=${YUMI_DB_NAME:-yumi_v2_test}
DB_HOST=${YUMI_DB_HOST:-127.0.0.1}
DB_USER=${YUMI_DB_USER:-yumi_v2_test}
FILES_DIR=${YUMI_FILES_DIR:-/tmp/yumi-files}
MIN_FREE_MB=${1:-512}
MIGRATIONS_DIR=$(cd "$(dirname "$0")/../../backend/src/main/resources/db/migration" && pwd)

fail() { echo "FAIL: $1"; exit 1; }

if [ -z "${YUMI_DB_PASSWORD:-}" ]; then
  YUMI_DB_PASSWORD=$(security find-generic-password -s yumi-v2-local-test -w)
fi
export MYSQL_PWD=$YUMI_DB_PASSWORD

echo "=== ① 配置检查 ==="
[ -n "$DB_NAME" ] || fail "缺少 YUMI_DB_NAME"
[ -n "$DB_USER" ] || fail "缺少 YUMI_DB_USER"
[ -n "${YUMI_DB_PASSWORD:-}" ] || fail "缺少数据库口令（环境变量或钥匙串 yumi-v2-local-test）"
[ -d "$(dirname "$FILES_DIR")" ] || fail "文件目录父路径不存在：$(dirname "$FILES_DIR")"
[ -n "${YUMI_OFFSITE_BACKUP_DIR:-}" ] || echo "WARN: 未设置 YUMI_OFFSITE_BACKUP_DIR（异地备份路径），备份将只留在本机"
echo "ok: db=$DB_NAME@$DB_HOST user=$DB_USER files=$FILES_DIR"

echo "=== ② 磁盘检查（要求空闲 ≥ ${MIN_FREE_MB}MB）==="
FREE_MB=$(df -m "$(dirname "$FILES_DIR")" | awk 'NR==2 {print $4}')
[ "$FREE_MB" -ge "$MIN_FREE_MB" ] || fail "空闲磁盘 ${FREE_MB}MB < ${MIN_FREE_MB}MB"
echo "ok: free=${FREE_MB}MB"

echo "=== ③ 连接检查 ==="
mysql -h "$DB_HOST" -u "$DB_USER" -N -B -e "SELECT 1" >/dev/null || fail "数据库不可连接"
echo "ok: connected"

echo "=== ④ 迁移对齐检查（代码迁移文件 vs 库内历史）==="
CODE_LATEST=$(ls "$MIGRATIONS_DIR"/V*__*.sql | sed -E 's|.*/V([0-9]+)__.*|\1|' | sort -n | tail -1)
CODE_COUNT=$(ls "$MIGRATIONS_DIR"/V*__*.sql | wc -l | tr -d ' ')
DB_STATE=$(mysql -h "$DB_HOST" -u "$DB_USER" -N -B "$DB_NAME" -e \
  "SELECT CONCAT(COUNT(*), '/', COALESCE(MAX(CAST(version AS UNSIGNED)), 0), '/', COALESCE(SUM(success = 0), 0))
   FROM flyway_schema_history" 2>/dev/null) \
  || fail "无法读取库内迁移历史（库不存在、权限不足或尚未执行过迁移）：$DB_NAME" 
DB_COUNT=$(echo "$DB_STATE" | cut -d/ -f1)
DB_LATEST=$(echo "$DB_STATE" | cut -d/ -f2)
DB_FAILED=$(echo "$DB_STATE" | cut -d/ -f3)
[ "$DB_FAILED" = "0" ] || fail "存在失败迁移：$DB_FAILED 条"
[ "$DB_LATEST" -le "$CODE_LATEST" ] || fail "库内版本 $DB_LATEST 高于代码版本 ${CODE_LATEST}（代码落后于库）"
echo "ok: code_latest=V$CODE_LATEST (${CODE_COUNT} 个文件), db_latest=V$DB_LATEST (${DB_COUNT} 条记录)"
if [ "$DB_LATEST" -lt "$CODE_LATEST" ]; then
  echo "待执行迁移：V$((DB_LATEST + 1)) … V$CODE_LATEST —— 启动时由 Flyway 执行，失败即中止发布"
fi

echo "=== ⑤ 只读冒烟（关键表可读且投影可查）==="
mysql -h "$DB_HOST" -u "$DB_USER" -N -B "$DB_NAME" -e "
  SELECT CONCAT('orders=', (SELECT COUNT(*) FROM orders),
                ' balances=', (SELECT COUNT(*) FROM order_item_fulfillment_balances),
                ' batches=', (SELECT COUNT(*) FROM inventory_batches),
                ' production_tasks=', (SELECT COUNT(*) FROM production_tasks),
                ' settlements=', (SELECT COUNT(*) FROM order_settlement_balances),
                ' after_sales=', (SELECT COUNT(*) FROM after_sales_cases));"

echo "=== 前置检查全部通过（可进入备份 → 迁移 → 启动 → 健康检查 → 恢复流量）==="

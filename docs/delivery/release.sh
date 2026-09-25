#!/usr/bin/env bash
# 发布编排（任务 9.6）：前置检查 → 一致备份 →（可选）临时库迁移演练 → 启动/迁移 → 健康检查 → 只读冒烟 → go/no-go。
# 任一步失败立即以非 0 退出（**no-go**），已完成的步骤不回滚（回滚依赖备份恢复，见 runbook §1）。
#
# 用法：
#   YUMI_ADMIN_USERNAME=admin YUMI_ADMIN_PASSWORD=… bash docs/delivery/release.sh
# 选项：
#   --skip-backup        跳过备份（仅用于演练/复跑核对）
#   --skip-start         跳过启动（应用已在运行，只做健康检查与冒烟）
#   --start-cmd '<cmd>'  自定义启动命令（默认 ./backend/mvnw -f backend/pom.xml spring-boot:run）
#   --api <url>          应用地址（默认 http://127.0.0.1:18090）
set -euo pipefail

SKIP_BACKUP=0
SKIP_START=0
START_CMD=""
API=http://127.0.0.1:18090
while [ $# -gt 0 ]; do
  case "$1" in
    --skip-backup) SKIP_BACKUP=1 ;;
    --skip-start) SKIP_START=1 ;;
    --start-cmd) START_CMD=$2; shift ;;
    --api) API=$2; shift ;;
    *) echo "未知参数：$1"; exit 2 ;;
  esac
  shift
done

# 默认启动命令必须带上与 --api 一致的端口，否则应用会起在默认端口（实测：起在 8080 导致健康检查 NO-GO）
PORT=$(echo "$API" | sed -E 's|^https?://[^:/]+:([0-9]+).*|\1|')
case "$PORT" in
  ''|*[!0-9]*) echo "无法从 --api 解析端口：$API"; exit 2 ;;
esac
if [ -z "$START_CMD" ]; then
  START_CMD="./backend/mvnw -q -f backend/pom.xml spring-boot:run -Dspring-boot.run.arguments=--server.port=$PORT"
fi

STEP=0
step() { STEP=$((STEP + 1)); echo; echo "=== [$STEP] $1 ==="; }
fail() { echo; echo "!!! NO-GO：$1"; echo "回滚口径见 docs/delivery/release-and-backup-runbook.md §1（停服 → 用发布前备份恢复 → 启动上一版本 → 健康检查）"; exit 1; }

COOKIE_JAR=$(mktemp)
trap 'rm -f "$COOKIE_JAR"' EXIT

# 启动步骤会继承本脚本的环境：这里先把数据库口令等必需变量解析并导出，
# 否则子进程会以空口令启动、Flyway/连接失败（实测表现为「管理员登录失败」的 NO-GO）。
if [ -z "${YUMI_DB_PASSWORD:-}" ]; then
  YUMI_DB_PASSWORD=$(security find-generic-password -s yumi-v2-local-test -w 2>/dev/null || true)
fi
if [ -z "${YUMI_DB_PASSWORD:-}" ]; then
  fail "缺少 YUMI_DB_PASSWORD（环境变量或钥匙串 yumi-v2-local-test），无法启动应用"
fi
export YUMI_DB_PASSWORD
export YUMI_DB_URL="${YUMI_DB_URL:-jdbc:mysql://localhost:3306/yumi_v2_test?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC}"
export YUMI_DB_USERNAME="${YUMI_DB_USERNAME:-yumi_v2_test}"
export YUMI_FILES_DIR="${YUMI_FILES_DIR:-/tmp/yumi-files}"

step "前置检查（配置/磁盘/连接/迁移对齐/只读冒烟）"
bash docs/delivery/release-preflight.sh || fail "前置检查未通过"

if [ "$SKIP_BACKUP" = "0" ]; then
  step "一致备份（数据库 + 文件快照）"
  bash docs/delivery/backup.sh "release-$(date +%Y%m%d-%H%M%S)" || fail "备份失败（禁止在无备份的情况下迁移）"
else
  step "一致备份 —— 已按 --skip-backup 跳过"
fi

if [ "$SKIP_START" = "0" ]; then
  step "启动应用（Flyway 在启动时执行迁移；失败即中止）"
  nohup bash -c "$START_CMD" > /tmp/yumi-release-start.log 2>&1 &
  echo "started: $START_CMD → /tmp/yumi-release-start.log"
  echo "等待就绪（最多 120s）…"
  for i in $(seq 1 60); do
    CODE=$(curl -s -o /dev/null -w '%{http_code}' "$API/api/actuator/health/liveness" || true)
    [ "$CODE" != "000" ] && break
    sleep 2
  done
else
  step "启动应用 —— 已按 --skip-start 跳过"
fi

step "健康检查（就绪含 db；文件存储可写性必须 UP）"
if [ -z "${YUMI_ADMIN_USERNAME:-}" ] || [ -z "${YUMI_ADMIN_PASSWORD:-}" ]; then
  fail "缺少 YUMI_ADMIN_USERNAME / YUMI_ADMIN_PASSWORD（用于认证后的健康与冒烟检查）"
fi
curl -s -c "$COOKIE_JAR" -X POST "$API/api/session" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$YUMI_ADMIN_USERNAME\",\"password\":\"$YUMI_ADMIN_PASSWORD\"}" \
  | grep -q '"code":"OK"' || fail "管理员登录失败"
HEALTH=$(curl -s -b "$COOKIE_JAR" "$API/api/actuator/health/readiness")
echo "$HEALTH" | grep -q '"status":"UP"' || fail "就绪检查未通过：$HEALTH"
FILE_STORAGE=$(curl -s -b "$COOKIE_JAR" "$API/api/actuator/health" | grep -o '"fileStorage":{"status":"[A-Z]*"')
echo "readiness=UP $FILE_STORAGE"
echo "$FILE_STORAGE" | grep -q '"status":"UP"' || fail "文件存储不可写（上传与附件读取会失败）"

step "只读冒烟（六类台账可查 + 事实/投影一致）"
for TYPE in ORDER_FULFILLMENT INVENTORY PRODUCTION SHIPMENT SETTLEMENT AFTER_SALES; do
  CODE=$(curl -s -o /dev/null -w '%{http_code}' -b "$COOKIE_JAR" "$API/api/reports/$TYPE?page=1&size=1")
  [ "$CODE" = "200" ] || fail "台账 $TYPE 查询失败（HTTP $CODE）"
done
CONSISTENCY=$(curl -s -b "$COOKIE_JAR" "$API/api/reports/consistency")
echo "$CONSISTENCY" | grep -q '"allConsistent":true' || fail "一致性检查未通过：$CONSISTENCY"

echo
echo "=== GO：前置检查、备份、迁移启动、健康检查、只读冒烟全部通过 ==="
echo "后续：恢复流量 → 观察 yumi_http_server_errors_total 与 5xx/连接池/磁盘告警 → 记录发布版本与备份标签"

#!/usr/bin/env bash
# 告警规则核对（任务 9.5）：解析 alert-rules.yml 的 expr，逐个校验引用的指标名是否真实存在，
# 避免「规则引用不存在的指标」导致告警静默失效。
#   ① 应用指标：与运行中应用的 GET /api/actuator/prometheus 比对（需要会话 Cookie 文件）
#   ② 代码声明：计数器类指标在首次自增前不会出现在抓取结果里，改查后端源码中的指标名
#   ③ 外部指标：必须在白名单中显式声明来源（mysqld_exporter / backup.sh 文本文件 / Prometheus 自身）
#
# 用法：COOKIE_JAR=/tmp/cookies.txt bash docs/delivery/check-alert-rules.sh
set -euo pipefail
API=${API:-http://127.0.0.1:18090}
COOKIE_JAR=${COOKIE_JAR:-/tmp/dbg-cookies.txt}
RULES=${RULES:-docs/delivery/alert-rules.yml}
SRC_DIR=${SRC_DIR:-backend/src/main/java}

EXTERNAL_RE='^(mysql_|yumi_backup_|up$)'

# 注意：管道中 grep 无匹配会返回非 0，配合 pipefail+set -e 会让脚本在打印诊断前就退出，故显式兜底
LIVE=$(curl -s -b "$COOKIE_JAR" "$API/api/actuator/prometheus" | grep -oE '^[a-z_][a-z0-9_]*' | sort -u || true)
if [ -z "$LIVE" ]; then
  echo "FAIL: 无法从 $API 取得指标（会话 Cookie 失效或应用未就绪）：$COOKIE_JAR"
  echo "      重新登录：curl -s -c $COOKIE_JAR -X POST $API/api/session -H 'Content-Type: application/json' -d '{\"username\":\"admin\",\"password\":\"…\"}'"
  exit 1
fi

# 只取 expr 及其多行续行（以 | 开头），不扫描注释与 annotations，避免把说明文字当成指标
EXPR=$(awk '
  /expr:/ { flag = 1; sub(/.*expr:/, ""); print; next }
  flag && /^[[:space:]]*\|/ { sub(/^[[:space:]]*\|/, ""); print; next }
  { flag = 0 }
' "$RULES")

# 指标名几乎都含下划线；剔除 PromQL 函数与聚合关键字，`up` 是例外单独保留
PROMQL_FUNCS='^(sum|rate|increase|time|clamp_min|clamp_max|avg|min|max|count|count_values|histogram_quantile|irate|delta|idelta|abs|ceil|floor|round|deriv|predict_linear|label_replace|vector|scalar|on|ignoring|group_left|group_right|without|by|offset|bool|and|or|unless)$'
CANDIDATES=$(echo "$EXPR" | grep -oE '[a-z_][a-z0-9_]*' | { grep '_' || true; } \
  | grep -vE "$PROMQL_FUNCS" | sort -u)
echo "$EXPR" | grep -qE '(^|[^a-z_])up([^a-z0-9_]|$)' && CANDIDATES=$(printf '%s\nup\n' "$CANDIDATES" | sort -u)

# 结构校验（无第三方依赖）：规则成组、alert 名唯一、且含 expr/for/severity/summary
python3 docs/delivery/check-alert-rules.py "$RULES"

MISSING=0
for metric in $CANDIDATES; do
  if echo "$LIVE" | grep -qx "$metric"; then
    echo "ok(app)      $metric"
  elif grep -rqF "$metric" "$SRC_DIR"; then
    echo "ok(declared) $metric"
  elif echo "$metric" | grep -qE "$EXTERNAL_RE"; then
    echo "ok(external) $metric"
  else
    echo "MISSING      $metric"
    MISSING=$((MISSING + 1))
  fi
done
[ "$MISSING" -eq 0 ] || { echo "FAIL: $MISSING 个指标既不在应用端点、也不在源码声明或外部白名单"; exit 1; }
echo "=== 告警规则引用的 $(echo "$CANDIDATES" | wc -l | tr -d ' ') 个指标全部可追溯 ==="

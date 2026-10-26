#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

if [[ ! -f .env ]]; then
    echo "缺少 .env，请先按部署手册创建。" >&2
    exit 1
fi

set -a
# shellcheck disable=SC1091
source .env
set +a

stamp=$(date +%Y%m%d-%H%M%S)
mkdir -p backups

docker compose exec -T -e MYSQL_PWD="$YUMI_DB_PASSWORD" mysql \
    mysqldump -u"$YUMI_DB_USER" --single-transaction --routines --triggers --no-tablespaces "$YUMI_DB_NAME" \
    | gzip > "backups/db-$stamp.sql.gz"

if [[ -d data/files ]]; then
    # 附件由容器内 uid 10001 写入，权限可能为 600；用 sudo 读取，避免打包失败。
    sudo tar -czf "backups/files-$stamp.tar.gz" -C data files
fi

find backups -type f -mtime +14 -delete

echo "备份完成：backups/db-$stamp.sql.gz"
if [[ -f "backups/files-$stamp.tar.gz" ]]; then
    echo "附件：backups/files-$stamp.tar.gz"
fi

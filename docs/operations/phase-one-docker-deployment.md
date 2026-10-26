# 一期 Docker Compose 部署手册（192.168.2.42）

- 目标主机：`192.168.2.42`（hostname `chw-server`，Ubuntu，Docker 已安装）
- 访问方式：局域网 `http://192.168.2.42`（或 `.env` 中配置的端口）
- 部署形态：Nginx（前端静态文件 + `/api` 反代）→ Spring Boot → MySQL 8.4，三者在同一台主机、不同容器内
- 数据位置：`./data/mysql` 与 `./data/files` 绑定到宿主机，不放在容器可写层
- 不在本手册范围：公网域名、HTTPS 证书、多实例、托管 MySQL、监控告警

## 1. 拓扑

```text
局域网浏览器
   │  http://192.168.2.42:${YUMI_HTTP_PORT}
   ▼
frontend（Nginx，唯一对外端口）
   │  /api/ 反向代理
   ▼
backend（Spring Boot，容器内 8080，不对外发布）
   ├── MySQL 8.4（容器内 3306，不对外发布）
   └── /var/lib/yumi/files → 宿主机 ./data/files
```

只有 `frontend` 的端口映射到宿主机；MySQL 和后端只在 Compose 内部网络可达。

## 2. 发布前

1. 确认要发布的代码已形成干净提交，并记录 commit：
   ```bash
   git status
   git rev-parse HEAD
   ```
2. 后端全量门禁和前端构建至少各跑一次（或确认已有新鲜证据）。部署镜像内只跳过测试，不跳过类型检查。
3. 本手册中的服务器命令都在 `.42` 上执行；代码同步和首次连接在 Mac 上执行。Mac 到 `.42` 没有免密登录，需要输入 `chw` 的密码。

## 3. 服务器前置检查（在 .42 上）

```bash
uname -m                  # 需要 x86_64
docker version            # 需要 Docker Engine
docker compose version    # 需要 Compose v2（docker compose，而不是 docker-compose）
command -v rsync          # 没有就 sudo apt install -y rsync
df -h /srv                # 预留至少 10 GB
sudo ss -ltnp | grep -E ':(80|8080|3306)\b' || true
groups                    # 当前用户需要在 docker 组
```

- 如果 80 已被占用，在 `.env` 里把 `YUMI_HTTP_PORT` 改成其他端口（例如 8088）。
- 8080 和 3306 不需要在宿主机监听；如果已有服务占用，不影响本方案，因为容器不发布这两个端口。
- 当前用户不在 `docker` 组时，先执行 `sudo usermod -aG docker "$USER"` 并重新登录。
- `docker compose version` 不存在时，先按 Docker 官方文档安装 Compose v2 插件，不要用旧的 `docker-compose`。

## 4. 同步代码（在 Mac 上执行）

```bash
rsync -av \
  --exclude '/.git' \
  --exclude '/.idea' \
  --exclude '/.superpowers' \
  --exclude '/build/' \
  --exclude '/backend/target/' \
  --exclude '/frontend/node_modules/' \
  --exclude '/frontend/dist/' \
  --exclude '/frontend/test-results/' \
  --exclude '/frontend/playwright-report/' \
  --exclude '/.env' \
  --exclude '/data/' \
  --exclude '/backups/' \
  /Volumes/code/YUMIV2/ chw@192.168.2.42:/srv/yumi/app/
```

不要加 `--delete`：服务器上的 `.env`、`data/`、`backups/` 不在源码树里，误删代价很高。

## 5. 初始化数据目录与配置（在 .42 上）

```bash
cd /srv/yumi/app

mkdir -p data/mysql data/files backups
sudo chown -R 10001:10001 data/files

cp .env.example .env
chmod 600 .env
```

生成两个互不相同的随机口令：

```bash
openssl rand -base64 24
openssl rand -base64 24
```

编辑 `.env`，填入：

- `YUMI_DB_PASSWORD`：应用连接业务库的口令
- `MYSQL_ROOT_PASSWORD`：MySQL root 口令，只用于运维
- `YUMI_MYSQL_TAG`：MySQL 镜像标签，默认 `8.4.11`；无法拉取固定小版本时可改为已缓存的 `8.4`
- `YUMI_HTTP_PORT`：默认 80；被占用时改掉
- `YUMI_VERSION`：本次发布标记，例如 `20261026`

确认没有残留占位口令：

```bash
grep -n 'replace-with' .env && echo "仍有占位口令，先替换" || true
```

不要把 `.env` 提交到 Git，也不要把初始管理员口令写进 `.env`。

MySQL 的库名、用户和口令（包括 root）只在首次初始化 `data/mysql` 时生效；之后只改 `.env` 不会修改库内账号。需要轮换口令时，先在 MySQL 内执行 `ALTER USER`，再同步修改 `.env` 并重启 backend。

## 6. 构建与启动（在 .42 上）

```bash
cd /srv/yumi/app
docker compose config >/dev/null
docker compose build
docker compose up -d
docker compose ps
```

`docker compose config` 只校验变量和 YAML；输出里包含口令，不要写到日志或贴到聊天里。

首次构建会在容器内下载 Maven 依赖和 pnpm 依赖，N100 上通常需要 5–15 分钟。

等待状态变为 `healthy`，并确认 Flyway 已执行迁移：

```bash
docker compose logs --tail=200 backend
```

全新数据库首次启动应看到 45 个迁移全部成功；如果出现连接失败或迁移校验错误，先停在这里排查，不要继续初始化管理员。

访问检查：

```bash
set -a; source .env; set +a
curl -I "http://127.0.0.1:${YUMI_HTTP_PORT:-80}/"
```

返回 200 后，浏览器打开 `http://192.168.2.42`（或配置的端口）。

## 7. 首次管理员（只执行一次，在 .42 上）

系统不提供网页注册，也没有默认口令。只在系统尚无账号时允许创建；初始口令至少 8 位：

```bash
cd /srv/yumi/app
read -r -s -p "初始管理员口令: " YUMI_INITIAL_ADMIN_PASSWORD; echo
export YUMI_INITIAL_ADMIN_PASSWORD
YUMI_INITIAL_ADMIN_LOGIN=admin docker compose --profile tools run --rm admin-bootstrap
unset YUMI_INITIAL_ADMIN_PASSWORD
```

成功时输出 `已创建初始管理员：admin（accountBusinessId=...）`。系统非空时命令会拒绝，不会重置已有账号。

登录后立即在「账号与角色」中创建日常账号；初始管理员口令只用于首次进入。

## 8. 验收清单

- [ ] `docker compose ps` 中 `mysql`、`backend`、`frontend` 均为 healthy
- [ ] 浏览器能打开登录页，管理员能登录
- [ ] 客户、商品、人员、生产、订单、财务、报表各入口可打开，无空白页或 502
- [ ] 上传一个附件成功，下载可打开
- [ ] `docker compose restart` 后仍能登录（会话存数据库，不因重启失效），附件仍可下载
- [ ] `docker compose stop backend` 后日志没有 `eventPublicationRegistry` 或 `BadSqlGrammar` 报错
- [ ] `docker compose down && docker compose up -d` 后业务数据仍存在（不要加 `-v`）
- [ ] `sudo ./deploy/backup.sh` 能生成数据库和附件备份
- [ ] 服务器重启后容器自动恢复（`restart: unless-stopped`）

## 9. 日常运维

### 9.1 备份

```bash
cd /srv/yumi/app
sudo ./deploy/backup.sh
```

附件文件由容器内 uid `10001` 写入，脚本会用 `sudo` 读取并打包；直接运行时需要输入 sudo 口令。脚本用 sudo 运行时，`backups/` 里的文件属 root，同步到 NAS 时同样用 `sudo rsync` 或按需调整属主。

备份写入 `backups/`：数据库为一致性快照，附件为 `data/files` 的压缩包；保留 14 天。

默认是在线备份，数据库与附件不是同一瞬间。需要严格一致时，先停应用再备份：

```bash
docker compose stop frontend backend
sudo ./deploy/backup.sh
docker compose start frontend backend
```

建议每天定时执行，并把 `backups/` 同步到 NAS 或其他机器（不要只留在同一块盘）。定时任务放到 root 的 crontab（`sudo crontab -e`），避免非交互环境无法输入 sudo 口令：

```cron
# .42 的系统时区是 UTC；18:00 UTC 等于次日 02:00 北京时间
0 18 * * * cd /srv/yumi/app && ./deploy/backup.sh >> backups/backup.log 2>&1
```

备份文件名按 `Asia/Shanghai` 生成，与业务日期一致。

### 9.2 恢复

恢复会覆盖现有业务数据，只在新库演练或确认要回到备份点时执行；执行前先备份当前状态，并停止应用写入。

```bash
cd /srv/yumi/app
set -a; source .env; set +a

# 1. 先保留当前状态
sudo ./deploy/backup.sh

# 2. 停止应用，避免恢复过程中继续写入
docker compose stop frontend backend

# 3. 重建空库（会删除现有业务表，务必确认库名和备份）
docker compose exec -T -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql \
  mysql -uroot -e "DROP DATABASE IF EXISTS \`$YUMI_DB_NAME\`; CREATE DATABASE \`$YUMI_DB_NAME\` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"

# 4. 导入数据库备份
gunzip -c backups/db-<时间戳>.sql.gz | \
  docker compose exec -T -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql \
  mysql -uroot "$YUMI_DB_NAME"

# 5. 恢复附件（目标目录属主是 10001，需用 sudo 写入）
sudo tar -xzf backups/files-<时间戳>.tar.gz -C data
sudo chown -R 10001:10001 data/files

# 6. 重新启动
docker compose start frontend backend
```

恢复后按第 8 节重新验收，并核对订单、生产需求、现金合计与备份前一致。

### 9.3 升级

```bash
# 1. 先备份
cd /srv/yumi/app
sudo ./deploy/backup.sh

# 2. 在 .env 中把 YUMI_VERSION 改成新值，例如 20261027
# 3. 从 Mac 重新 rsync 代码
# 4. 在 .42 上重建并启动
docker compose build
docker compose up -d

# 5. 观察迁移和启动
docker compose ps
docker compose logs --tail=200 backend
```

Flyway 只向前迁移。升级后不要直接改已经执行过的迁移文件。

### 9.4 回滚

代码回滚：

```bash
cd /srv/yumi/app
docker compose stop frontend backend
# 把 .env 里的 YUMI_VERSION 改回上一版
docker compose up -d
```

前提是上一版镜像还在（`docker images | grep yumi-`）。如果新版本已经执行了不兼容的数据库迁移，必须先用升级前的备份恢复数据库，再启动旧版本；不要只回滚代码。

### 9.5 日志

```bash
docker compose logs -f --tail=200 backend
docker compose logs -f --tail=200 frontend
docker compose logs -f --tail=200 mysql
```

容器日志已限制为单文件 10 MB、保留 5 份。

## 10. 故障排查

| 现象 | 先查什么 | 处理 |
|---|---|---|
| 浏览器 502 | `docker compose ps`、`docker compose logs backend` | 后端未 healthy；看数据库连接和 Flyway 错误 |
| 后端反复重启 | 日志中的数据库连接错误 | 核对 `.env` 的库名、用户、口令；确认 `mysql` healthy |
| 上传返回 500 | `ls -ld data/files` | 目录属主必须是 `10001:10001`；`sudo chown -R 10001:10001 data/files` |
| MySQL 起不来 | `docker compose logs mysql` | 数据目录不干净或版本不一致；先备份，不要直接删 `data/mysql` |
| 80 端口被占用 | `sudo ss -ltnp \| grep :80` | 改 `.env` 的 `YUMI_HTTP_PORT` 后 `docker compose up -d` |
| 后端重建后仍 502 | `docker compose logs frontend` | `docker compose restart frontend` 让 Nginx 重新解析后端地址 |
| 时间差 8 小时 | `docker compose exec backend date` | 三个服务都应设置 `TZ=Asia/Shanghai`；不要单独改某个容器 |
| 拉取基础镜像超时 | `docker pull mysql:8.4.11` | 配置 Docker 镜像加速器或重试；失败时不要继续 `up` |
| 构建被 OOM 杀掉（退出 137） | `docker compose ps`、`free -h` | 先 `docker compose stop backend frontend` 再构建，或临时加 swap |
| Flyway 校验失败 | 日志中的 migration 名称 | 不要手改已执行迁移；恢复备份或新增迁移修正 |

不要执行的命令：

- `docker compose down -v`：会删除 Compose 管理的卷；本项目数据虽用绑定目录，仍应避免误操作
- 直接删除 `data/mysql` 或 `data/files`
- 在业务库上手工改表、插种子订单或导入原型 localStorage 数据

## 11. 安全与限制

- 只在局域网使用；不要在路由器上给 80/8080/3306 做端口映射，也不要直接暴露到公网。
- 会话令牌保存在浏览器 `localStorage`，当前是 HTTP 访问；局域网内可用，但不要把系统放到不可信网络。需要远程访问时，先加 HTTPS 或隧道，再开放。
- `.env` 权限保持 `600`，备份文件同样只给运维账号读取。
- 一期是单实例部署：会话在数据库中，重启不掉线，但不支持多实例横向扩容。
- 附件默认上限 10 MB；Nginx 已放宽到 12 MB。修改上限时要同时改后端配置和 Nginx 配置。
- 生产备份、保留周期和异地副本由发布负责人确认；本手册只提供可执行步骤。

## 12. 本次部署记录

- 日期：2026-10-27
- 主机：`192.168.2.42`（`chw-server`，x86_64，4 核 / 7.5 GiB）
- 访问地址：`http://192.168.2.42`
- 发布版本：`20261027-4ddd8f9`（后端）、前端同标签
- MySQL：复用服务器已缓存的 `mysql:8.4`（实际 8.4.13）；镜像加速器无法拉取 `mysql:8.4.11`，因此 `YUMI_MYSQL_TAG=8.4`
- 首个管理员凭据：保存在服务器 `/root/yumi-admin-credentials.txt`（权限 600）
- 服务器系统时区：UTC；容器内为 `Asia/Shanghai`，业务日期按北京时间
- 已验证：45 个迁移、管理员登录、业务接口鉴权、附件上传/删除、`restart` 与 `down/up` 后会话和附件保持、停机无 `eventPublicationRegistry` 报错、备份脚本生成数据库与附件包
- 服务器上另有独立 `mysql` 容器占用 3306；本系统使用 Compose 内部 MySQL，不发布 3306，两者互不影响

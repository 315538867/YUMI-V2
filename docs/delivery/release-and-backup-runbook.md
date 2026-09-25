# 发布与备份恢复手册（任务 9.6 / 9.7）

本手册给出**可执行的**发布流程与备份/恢复流程，以及一次真实的恢复演练记录。
脚本位于 `docs/delivery/`；口令一律来自环境变量或 macOS 钥匙串（`yumi-v2-local-test`），**不写入仓库**。

## 1. 发布流程（9.6）

```bash
# ① 前置检查：配置 / 磁盘 / 连接 / 迁移对齐 / 只读冒烟（任一失败即中止发布）
bash docs/delivery/release-preflight.sh          # 退出码 0 才继续；可选参数为最小空闲磁盘 MB

# ② 一致备份：数据库 + 文件快照（发布前必须做，见 §2）
BACKUP_ROOT=/var/backups/yumi YUMI_OFFSITE_BACKUP_DIR=/mnt/offsite \
  BACKUP_ENC_KEY_FILE=/etc/yumi/backup.key bash docs/delivery/backup.sh

# ③ 临时库演练迁移（在隔离库上先跑一遍 Flyway，避免直接改正式库）
#    做法：把备份恢复到临时库 → 用临时库地址启动一次应用 → Flyway validate/migrate 必须成功
bash docs/delivery/restore.sh /var/backups/yumi/<标签> yumi_v2_release_drill
YUMI_DB_URL='jdbc:mysql://127.0.0.1:3306/yumi_v2_release_drill?...' ./backend/mvnw -q -f backend/pom.xml spring-boot:run

# ④ 正式迁移 + 启动（Flyway 在启动时执行；失败即中止）
YUMI_DB_PASSWORD=… ./backend/mvnw -f backend/pom.xml spring-boot:run

# ⑤ 健康检查：就绪必须 UP（含 db），文件存储组件必须 UP
curl -s -b cookies.txt http://127.0.0.1:18090/api/actuator/health/readiness
curl -s -b cookies.txt http://127.0.0.1:18090/api/actuator/health | grep -o '"fileStorage":{[^}]*}'

# ⑥ 只读冒烟：六类台账与一致性检查
curl -s -b cookies.txt 'http://127.0.0.1:18090/api/reports/ORDER_FULFILLMENT?page=1&size=1'
curl -s -b cookies.txt http://127.0.0.1:18090/api/reports/consistency   # allConsistent 必须 true

# ⑦ 恢复流量（反向代理放行）；观察 yumi_http_server_errors_total 与 5xx 告警
```

**编排脚本 `release.sh` 实测（2026-09-25，端到端）**：`BACKUP_ROOT=… YUMI_ADMIN_USERNAME=… YUMI_ADMIN_PASSWORD=… bash docs/delivery/release.sh` 退出码 **0**、
输出 `GO`——五步全跑通（备份 `db.sql` 833400 bytes、保留 3 份；应用起在 `18090`；`readiness=UP` + `fileStorage` UP；六类台账 200 + `allConsistent=true`）。
**实跑发现并修掉两个会让发布失败的缺陷**：①默认启动命令没有把数据库口令传给子进程（应用以空口令启动、Flyway/连接失败，表现为「管理员登录失败」的 NO-GO）→ 脚本改为解析并 `export` `YUMI_DB_PASSWORD`/`YUMI_DB_URL`/`YUMI_DB_USERNAME`/`YUMI_FILES_DIR`；②默认启动命令没带端口，应用起在默认 8080 而非 `--api` 指定端口 → 改为从 `--api` 解析端口并注入 `--server.port`。这两个缺陷只有在真跑编排时才会暴露（单跑 `--skip-start` 检查不出来）。

回滚：保留上一版本产物与 §2 的发布前备份；回滚即「停服 → 用发布前备份恢复 → 启动上一版本 → 健康检查 → 恢复流量」。
**注意**：迁移是**只前进**的（Flyway 无 down 脚本），回滚依赖备份恢复，故 ② 不可跳过。

## 2. 备份与恢复（9.7）

```bash
# 全量备份：mysqldump --single-transaction（同恢复点、不加锁）+ 文件目录快照 + manifest
BACKUP_ROOT=/var/backups/yumi bash docs/delivery/backup.sh [标签]
#   产物：db.sql（或 db.sql.enc）、files.tar.gz（或 .enc）、manifest.txt（版本/表数/关键表行数）
#   异地加密：设置 BACKUP_ENC_KEY_FILE（aes-256-cbc/pbkdf2）与 BACKUP_OFFSITE_DIR
#   需要 binlog 位点：BACKUP_SOURCE_DATA=1（该选项要求 RELOAD 权限）

# 恢复（演练或真实恢复）
bash docs/delivery/restore.sh /var/backups/yumi/<标签> [目标库名]   # 默认 yumi_v2_restore_drill
#   脚本会：解密（如需）→ 重建目标库 → 导入 → ①迁移版本 ②三项事实/投影一致性 ③关键只读计数 ④文件快照恢复
```

**恢复点目标**：以最近一次成功备份为准（本手册不承诺 PITR；需要 binlog 位点时用 `BACKUP_SOURCE_DATA=1` 并在恢复时 `--start-position`）。
**权限要求**：备份账号需 `SELECT, LOCK TABLES, SHOW VIEW, TRIGGER, EVENT`；恢复账号需目标库 `CREATE/DROP/INSERT`。本地开发账号无 `RELOAD/PROCESS`，故脚本默认 `--skip-lock-tables --no-tablespaces`（不影响 InnoDB 一致性）。
**加密密钥**：密钥文件必须在异地备份之外单独保管（丢失即无法恢复），并与备份目录权限隔离。

## 3. 恢复演练记录（2026-09-25，本机 MySQL 8.4）

| 步骤 | 命令 | 退出码 | 结果 |
| --- | --- | --- | --- |
| 前置检查 | `bash docs/delivery/release-preflight.sh` | 0 | 配置/磁盘(97631MB)/连接/迁移对齐(V14 vs 库)/只读冒烟全通过 |
| 加密备份 | `BACKUP_ENC_KEY_FILE=… BACKUP_OFFSITE_DIR=… bash docs/delivery/backup.sh` | 0 | `db.sql.enc` 833KB、`files.tar.gz.enc` 1KB、`manifest.txt`（schema_version=14、tables=56）；异地目录已落盘 |
| 恢复 | `bash docs/delivery/restore.sh <备份> yumi_v2_test` | 0 | ①`installed=14 failed=0`；②一致性三项：库存 0 不一致、售后 0 不一致、履约 2 条不一致（**发现缺陷，见下**）；③`orders=1 order_items=2 batches=1 plans=5 shipments=1 payments=1`；④文件快照恢复 5 个文件 |
| 应用只读路径 | 恢复后登录 + `GET /api/orders`、`GET /api/reports/consistency` | 0 | 均 200，会话与读模型在库被重建后仍可用 |

### 失败路径演练（2026-09-25）

| 场景 | 命令 | 退出码 | 结果 |
| --- | --- | --- | --- |
| 发布时 API 不可达 | `release.sh --skip-backup --skip-start --api http://127.0.0.1:19999` | **1** | **NO-GO：管理员登录失败** + 打印回滚口径（停服 → 发布前备份恢复 → 启动上一版本 → 健康检查） |
| 前置检查指向不存在的库 | `YUMI_DB_NAME=yumi_nonexistent_db release-preflight.sh` | **1** | 迁移对齐检查 **FAIL：无法读取库内迁移历史（库不存在、权限不足或尚未执行过迁移）**（原为原始 MySQL 报错，已改为友好诊断）；正常库复测退出码 0 |

### 演练发现并修复的缺陷（任务 9.4 一致性检查）

- **现象**：恢复后一致性检查报「不一致明细 [127,128]」（应用接口与脚本重建 SQL 一致报错）。
- **定位**：`ReportService.fulfillmentBalanceCheck` 的重建子查询把三列各**错位一个节点**（把 PACKING_BAG 的合格量当成 `making_inflow`、SEAM_CUTTING 的当成 `packing_inflow`、SHIPPABLE 的当成 `seam_inflow`），并且**完全没有校验 `shippable_quantity`**。
- **判定**：投影本身正确（`packing_inflow 10 = 库存接入 4 + 上游合格 6`、`seam_inflow 4`、`shippable 4−4=0`），**是检查写错了**；`ORDER_DEMAND` 是需求登记，不得计入可发货。
- **修复**：按节点列正确对应重写重建（MAKING/PACKING_BAG/SEAM_CUTTING 各取本节点 `IN`；可发货 = SHIPPABLE 的 `IN`（排除 `ORDER_DEMAND`）− `OUT`），并新增 `shippable_quantity` 校验；补回归用例 `ReportApiTest.consistencyCheckMapsNodeColumnsAndShippable`（多工序流入一致 → true；列错位 → false；只改可发货 → false），并修正原用例夹具（原先 `shippable_quantity=6` 却没有对应流入事实，属夹具自身不自洽）。
- **验证**：真实铺数数据上 `GET /api/reports/consistency` → `allConsistent=true`（三项全一致）；后端全量 **304 测试 0 失败**。

## 4. 告警规则与指标核对（9.5）

```bash
# 加载告警规则（运维侧）：docs/delivery/alert-rules.yml → Prometheus rules 目录
# 核对规则引用的指标是否真实存在（应用端点 / 源码声明 / 外部白名单三选一）
COOKIE_JAR=/tmp/cookies.txt bash docs/delivery/check-alert-rules.sh
```

规则覆盖：HTTP 5xx 速率与占比、就绪探针不可抓取、连接池等待/使用率/获取超时、磁盘剩余 15%/5%、JVM 堆 90%、
**文件不可写**（`yumi_file_storage_writable == 0`）、**备份过期/失败**（`yumi_backup_last_success_timestamp_seconds`、
`yumi_backup_failures_total`，由 `backup.sh` 写文本文件经 node_exporter textfile collector 采集）、
MySQL 慢查询与连接数（需 `mysqld_exporter`）。

**核对记录（2026-09-25）**：`check-alert-rules.sh` 退出码 **0**，规则引用的 **14 个指标全部可追溯**——
其中 `ok(app)`（应用实时端点）10 个、`ok(declared)`（计数器首次自增前不出现，改查源码声明）2 个、`ok(external)` 2 个。
实测 `yumi_file_storage_writable 1.0`（`GET /api/actuator/prometheus`）。
MySQL 侧慢查询日志需在服务端开启（`slow_query_log=ON`、`long_query_time`），应用侧不接管该配置。

**结构校验**：`check-alert-rules.sh` 先用 `check-alert-rules.py` 做无依赖结构校验——规则必须成组、`alert` 名唯一、
且含 `expr`/`for`/`labels.severity`/`annotations.summary`（缺项会让规则静默失效）。实测 5 个分组、14 条规则结构完整；
去掉一条规则的 `for` 后实测报错 `FAIL(结构): YumiDiskCritical: 缺少 for`（断言非空转）。

**定时备份（9.7）**：`docs/delivery/backup-schedule.plist` 为 launchd 计划任务模板（每天 02:30 全量备份，
`plutil -lint` 校验通过）；Linux 环境用等价 crontab：

```cron
30 2 * * * cd /srv/yumi && BACKUP_ROOT=/var/backups/yumi BACKUP_OFFSITE_DIR=/mnt/offsite/yumi \
  BACKUP_ENC_KEY_FILE=/etc/yumi/backup.key BACKUP_KEEP=14 BACKUP_TEXTFILE_DIR=/var/lib/node_exporter/textfile \
  /bin/bash docs/delivery/backup.sh >> /var/log/yumi/backup.log 2>&1
```

**备份保留与指标实测**：`BACKUP_KEEP=2` 连跑 3 次 → 自动 `pruned=run1`、`retained=2`；`BACKUP_TEXTFILE_DIR` 产出的
`yumi_backup.prom` 含 `yumi_backup_last_success_timestamp_seconds` 与 `yumi_backup_failures_total`。

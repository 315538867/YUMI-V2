# 一期业务系统实施记录

本文件记录 `openspec/changes/implement-phase-one-business-system` 的实际实施状态。
**提案与任务清单本身不代表实现完成**；只有真实执行的命令输出与通过的测试才算证据。

- 业务基线：`a714ede433b37d9d987e7229d2fdead52d723ac1`（`docs/一期/` 16 份文档）
- 提案版本：1.3
- 实施开始：2026-10-20

## 1. 工作区布局

```text
YUMIV2/
├─ backend/          Java 21 + Spring Boot 3.5 模块化单体（本文件 §3）
├─ frontend/         Vue 3 + TypeScript + Vite 管理端（本文件 §4）
├─ docs/一期/        业务基线方案（只回写工程决策，不改业务口径）
├─ openspec/        提案、规范、任务与交付清单
└─ *.html, *.js      历史交互原型（保留只读参考，不是数据源）
```

原型文件不删除、不改造成正式入口，也不作为任何权威数据来源。

## 2. 固定技术版本

### 后端

| 组件 | 版本 |
|---|---|
| Java | 21（`openjdk 21.0.10`） |
| Spring Boot | 3.5.16 |
| Spring Modulith | 1.4.13（与 Boot 3.5 对齐） |
| Flyway | 由 Boot BOM 管理（`flyway-core` + `flyway-mysql`） |
| MySQL | 8.4（Testcontainers `mysql:8.4`） |
| MySQL 驱动 | 由 Boot BOM 管理（`mysql-connector-j`） |
| ArchUnit | 1.4.2 |
| Testcontainers | 1.21.3 |
| Maven | 3.9.14（Maven Wrapper 固定） |

### 前端

| 组件 | 版本 |
|---|---|
| Node.js | `>= 22.12.0`（实测 22.14.0） |
| pnpm | 11.23.0 |
| Vue / Vue Router / Pinia | 3.5.44 / 5.4.0 / 4.1.0 |
| Element Plus | 2.14.7 |
| Vite / TypeScript / vue-tsc | 8.3.4 / 5.9.3 / 3.3.13 |
| Vitest / Playwright | 5.0.5 / 1.64.1 |

精确版本以 `backend/pom.xml`、`backend/.mvn/wrapper/maven-wrapper.properties`、
`frontend/package.json` 与 `frontend/pnpm-lock.yaml` 为准。

## 3. 后端命令

```bash
cd backend

# 单元测试（纯契约、边界、计算）
./mvnw -Dtest=<测试类> test
./mvnw test

# 真实 MySQL 集成测试（Testcontainers，禁止连开发/生产库）
./mvnw -Dit.test=<集成测试类> verify

# 全量：模块边界 + 迁移 + 集成测试
./mvnw verify
```

集成测试基类为 `com.yumi.support.AbstractMySqlIntegrationTest`，每个以 `IT` 结尾的类都由
Failsafe 执行；容器不可用时测试必须失败，不退化到内存数据库。

### macOS + Colima

Colima 只提供用户级 Docker socket。`backend/pom.xml` 内的 `colima-docker` profile 在检测到
`~/.colima/default/docker.sock` 时自动激活，为 Surefire/Failsafe 注入：

```text
DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
```

Docker Desktop 或其他 CI 环境不会激活该 profile。

## 4. 前端命令

```bash
cd frontend

pnpm run typecheck        # vue-tsc --noEmit
pnpm run test:unit        # Vitest
pnpm run build            # 类型检查 + 生产构建
pnpm exec playwright test # 页面测试（自动启动 Vite，结束清理）
```

页面测试必须通过正式 API 准备数据，不直接写数据库。

## 5. 模块边界

九个顶层模块位于 `backend/src/main/java/com/yumi/`：`platform`、`calculation`、`customer`、
`product`、`workforce`、`production`、`orders`、`finance`、`reporting`。每个模块都有
`package-info.java` 声明所有权，且只在 `api` 包对外暴露类型。

`ModuleBoundaryTest` 机械校验：

- 模块集合与基线 03 第 3 节一致；
- 跨模块只能访问对方 `api` 包；
- 依赖方向与基线 03 第 6 节一致（`orders` 不依赖 `finance`，`production` 不依赖 `orders`/`finance`）；
- 根包只保留应用入口。

## 6. 当前实施状态

| 任务 | 状态 | 证据 |
|---|---|---|
| 1.1 基线漂移校验 | 完成 | 见 §7 |
| 1.2 后端工程骨架 | 完成 | `./mvnw test`、`SkeletonMigrationIT` |
| 1.3 前端工程骨架 | 完成 | `typecheck`、`test:unit`、`build`、`playwright test` |
| 1.4 契约红测试 | 完成 | 先红后绿，见 §7 |
| 1.5 模块与值对象 | 完成 | `ModuleBoundaryTest`、`ValueContractTest` |
| 1.6 命令批次契约 | 完成 | `CommandBatchContractTest` |
| 1.7 回写架构文档 7.1 与 README | 完成 | 本文件与 `docs/一期/03` §7.1 |
| 1.8 骨架全绿验证 | 完成 | 见 §7 |
| 1.9 HTTP 契约红测试 | 完成 | `HttpContractBoundaryTest`、`HttpWireContractTest` |
| 1.10 平台 HTTP 上下文与前端 apiClient | 完成 | `PlatformHttpWireTest`、`apiClient.test.ts` |

第 1 组（工程与边界基线）已全部完成并通过验证。后续组（2—15）尚未实施。

## 7. 已执行验证与结果

以下命令均在 2026-10-20 于本仓库真实执行；结果摘录为实际输出。

```text
$ cd backend && ./mvnw test
Tests run: 62, Failures: 0, Errors: 0, Skipped: 0  → BUILD SUCCESS

$ cd backend && ./mvnw -Dit.test=SkeletonMigrationIT verify
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0  → BUILD SUCCESS
（容器 MySQL 8.4.11，Flyway 迁移执行；数据库名 yumi_it）

$ cd frontend && pnpm run typecheck
（无输出，退出码 0）

$ cd frontend && pnpm run test:unit
Test Files 3 passed (3) / Tests 15 passed (15)

$ cd frontend && pnpm run build
✓ built（骨架 chunk，无业务页面）

$ cd frontend && pnpm exec playwright test
1 passed (3.8s)

$ openspec validate implement-phase-one-business-system --strict
Change 'implement-phase-one-business-system' is valid
```

基线漂移校验：

```text
$ git diff a714ede -- docs/一期/
仅 00-文档总览.md 的“提案关联”章节变化（提案创建时记录），
其余 15 份业务文档与基线逐字一致。
```

红测试证据：契约测试在 `platform/api/value`、`platform/aftercommit/api` 与九个模块
`package-info.java` 尚未实现时，先以“程序包不存在 / 缺少 package-info / 未实现”失败，
实现后才转绿；此前未把编译失败当作业务断言红。

## 8. 与业务基线的边界

本仓库当前只完成工程骨架与纯契约。以下内容**尚未实现**，不得对外宣称可用：

- 任何业务命令、Query、HTTP 路由与页面；
- 登录、权限、幂等、审计、文件与提交后任务的真实行为；
- 计算中心的预算、工作量、成本与胶水公式；
- 数据库业务表与 Flyway 业务迁移（`db/migration` 目前为空）。

业务口径仍以 `docs/一期/` 与 `openspec/` 中的规范为准；本文件只记录工程事实。

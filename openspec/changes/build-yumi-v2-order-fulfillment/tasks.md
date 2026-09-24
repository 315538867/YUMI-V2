## 全局完成定义

工程目录：后端位于 `backend/`（Maven Wrapper、`pom.xml`、Java 源码与 Flyway 迁移），共享 React 前端位于 `frontend/`，Electron 薄壳位于 `frontend/electron/`。后端命令在 `backend/` 中执行；下文出现的 `src/main` 与 `src/test` 均相对于 `backend/`。

以下条件适用于每一个任务编号；任一项缺失时不得勾选：

1. 在该任务证据下记录对应 `specs/<capability>/spec.md` 的 Requirement/Scenario、正式 `docs` 章节和受影响 API/表/路由；无外部行为的纯工程任务须说明其支撑的场景。
2. 先运行最小失败测试并保存实际命令、退出码和正确 RED 原文，再实现并保存 GREEN 命令、退出码、测试数和关键断言；阶段边界再运行全量门禁。
3. 记录实际新增/修改文件、Flyway 版本、HTTP 方法/路径、错误码、事务拥有者、锁定对象和幂等键；不适用项须明确写“不适用”及理由。
4. 前端任务记录正式可达路由、操作入口、浏览器验证和 Electron 复用结果；预览路由不能作为验收证据。
5. 需要人眼判断时附 `humanVisualConclusion.checklist`，状态保持 `pending-user-signoff`；用户确认后补 `confirmedBy/confirmedOn/conclusion` 才能勾选。
6. 完成证据统一回填在本任务条目下的“证据”子项或实现账本的同编号记录；阶段九双向追踪表只是汇总校验，不能替代前置任务逐项留证。

## 1. 阶段一：工程、认证与实施门禁

- [x] 1.1 建立 Java 21 + Maven Wrapper + Spring Boot 3.5.x 工程和 `identity/catalog/orders/inventory/production/files/shared` 包；依据 `platform-foundation`“浏览器和 Electron 必须使用同一业务 API”及 `docs/architecture/system-architecture.md` 第 2、5 节创建启动测试；先记录缺少应用入口的 RED，再实现并附在 `backend/` 执行 `./mvnw test` 的证据。
  - 证据：Requirement/Scenario：`platform-foundation` Requirement“浏览器和 Electron 必须使用同一业务 API”、Scenario“两种客户端读取同一订单”；正式文档：`docs/architecture/system-architecture.md` 第 2、5 节；文件：`backend/pom.xml`、`backend/mvnw`、`backend/mvnw.cmd`、`backend/.mvn/wrapper/maven-wrapper.properties`、`backend/src/main/java/com/yumi/YumiApplication.java`、`backend/src/main/java/com/yumi/{identity,catalog,orders,inventory,production,files,shared}/`、`backend/src/test/java/com/yumi/YumiApplicationTest.java`；API/表/路由：不适用，工程启动任务尚未提供业务 API 或数据库表，正式路由待后续任务实现；RED：`JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -q -Dtest=YumiApplicationTest test`（当时从仓库根目录执行，迁移后同命令在 `backend/` 执行），退出码 1，`IllegalStateException: Unable to find a @SpringBootConfiguration`；GREEN：同命令，退出码 0，`Started YumiApplicationTest`，1 个测试通过；关键断言：Spring Boot 测试上下文发现 `com.yumi.YumiApplication` 并成功启动。
  - 人工证据：不适用；如涉及页面/打印/Electron，回填 `humanVisualConclusion.checklist`，状态为 `pending-user-signoff`。
  - 阶段门禁回填（1.12，2026-09-23）：Maven 全量 39 测试 0 失败（含 Modulith 边界）、前端 `npm run typecheck`/`npm test`（4 文件 18 用例）/`npm run build` 退出码 0、`openspec validate build-yumi-v2-order-fulfillment --strict` 通过、浏览器与 Electron 均由用户经正式 `/login` 登录并访问同一 `/api/session`（实据见 1.12）；Testcontainers 按 1.3 书面豁免。本任务在该门禁下复验有效。
- [x] 1.2 使用 Spring Modulith 声明六个顶级模块并编写边界测试，禁止跨模块访问实体、Repository 和内部包；依据 `docs/architecture/system-architecture.md` 第 4-6 节，证明 `shipments/payments/refunds/after_sales_*` 仍归 `orders`。
  - 证据：Requirement/Scenario：`platform-foundation` 的模块化单体边界约束；正式文档：`docs/architecture/system-architecture.md` 第 4-6 节；文件：`backend/pom.xml`、`backend/src/main/java/com/yumi/{identity,catalog,orders,inventory,production,files}/package-info.java`、`backend/src/test/java/com/yumi/ModuleStructureTest.java`；API/表/路由：不适用，本任务只验证包边界；RED：`JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -q -Dtest=ModuleStructureTest test`，退出码 1，实际模块列表为空；GREEN：同命令，退出码 0，2 个测试通过；关键断言：六个顶级模块名称完整匹配，`ApplicationModules.of(YumiApplication.class).verify()` 通过，发货/收退款/售后未声明为顶级模块，后续归入 `orders` 包内实现。
  - 人工证据：不适用；如涉及页面/打印/Electron，回填 `humanVisualConclusion.checklist`，状态为 `pending-user-signoff`。
  - 阶段门禁回填（1.12，2026-09-23）：Maven 全量 39 测试 0 失败（含 Modulith 边界）、前端 `npm run typecheck`/`npm test`（4 文件 18 用例）/`npm run build` 退出码 0、`openspec validate build-yumi-v2-order-fulfillment --strict` 通过、浏览器与 Electron 均由用户经正式 `/login` 登录并访问同一 `/api/session`（实据见 1.12）；Testcontainers 按 1.3 书面豁免。本任务在该门禁下复验有效。
- [x] 1.3 配置 MySQL 8.x、HikariCP、Spring Data JPA、UTC/业务时区和 Hibernate `ddl-auto: validate`；使用 Testcontainers MySQL 编写空库启动 RED/GREEN，禁止 H2 替代。
  - 证据：Requirement/Scenario：`platform-foundation` Requirement“系统必须通过版本化迁移管理数据库结构”、Scenario“空库启动”；正式文档：`docs/architecture/database-design.md` 第 15 节、`docs/architecture/system-architecture.md` 第 5 节；文件：`backend/pom.xml`、`backend/src/main/resources/application.yml`、`backend/src/test/java/com/yumi/MySqlInfrastructureTest.java`；API/表/路由：不适用，本任务只验证基础数据库连接与 ORM 校验；本机验证：`YUMI_DB_PASSWORD=<钥匙串读取值> ./mvnw -q -Dtest=MySqlInfrastructureTest test`，退出码 0，1 个测试通过，实际连接 MySQL 8.4.11，HikariCP 建池成功，Hibernate `ddl-auto: validate` 启动成功，连接会话时区为 `+00:00`；阶段门禁：`YUMI_DB_PASSWORD=<钥匙串读取值> ./mvnw -q test`，退出码 0；RED：初次 `./mvnw -q -Dtest=MySqlInfrastructureTest test` 因缺少 JDBC/Testcontainers 依赖退出码 1，补充配置后曾因 Docker socket/API 不可用退出码 1，随后按用户要求移除 Docker/Testcontainers。Flyway、业务表和迁移版本属于后续任务 1.4，当前不适用；HTTP 方法/错误码/事务拥有者/锁定对象/幂等键：不适用。
  - 书面豁免（2026-09-23，用户选择“维持暂缓+书面豁免”）：Docker/Testcontainers 继续暂缓（用户口径：Docker 属部署阶段，Testcontainers 属独立验收基础设施、启用与否另行决定）；原“Testcontainers MySQL 隔离空库 RED/GREEN”缺口以**用户豁免**关闭勾选，验收替代证据 = 本机独立测试库 `yumi_v2_test` 从空库完成 V1→V2→V3 逐版本迁移且重复校验/篡改拒绝由 `FlywayFoundationMigrationTest` 通过、Hibernate validate 常绿、全程无 H2 替代；该缺口作为“已知豁免项”跟踪至 9.11 最终门禁前再决定是否补容器验收。
  - 人工证据：不适用；如涉及页面/打印/Electron，回填 `humanVisualConclusion.checklist`，状态为 `pending-user-signoff`。
  - 阶段门禁回填（1.12，2026-09-23）：Maven 全量 39 测试 0 失败（含 Modulith 边界）、前端 `npm run typecheck`/`npm test`（4 文件 18 用例）/`npm run build` 退出码 0、`openspec validate build-yumi-v2-order-fulfillment --strict` 通过、浏览器与 Electron 均由用户经正式 `/login` 登录并访问同一 `/api/session`（实据见 1.12）；Testcontainers 按 1.3 书面豁免。本任务在该门禁下复验有效。
- [x] 1.4 建立 Flyway 基线迁移目录 `src/main/resources/db/migration` 和 `V1__foundation.sql`，创建 `admin_accounts/number_sequences/file_metadata`、审计和幂等字段；依据 `platform-foundation`“版本化迁移”与 `docs/architecture/database-design.md` 第 2、12、15 节验证空库、重复校验和已执行脚本被改写失败。
  - 证据：Requirement/Scenario：`platform-foundation` Requirement“系统必须通过版本化迁移管理数据库结构”、Scenario“空库启动”和“已执行迁移被修改”；正式文档：`docs/architecture/database-design.md` 第 2、4、15 节；文件：`backend/pom.xml`、`backend/src/main/resources/application.yml`、`backend/src/main/resources/db/migration/V1__foundation.sql`、`backend/src/test/java/com/yumi/FlywayFoundationMigrationTest.java`；API/表/路由：不适用，本任务为数据库基础迁移；RED：`YUMI_DB_PASSWORD=<钥匙串读取值> ./mvnw -q -Dtest=FlywayFoundationMigrationTest test`，在无 Flyway 迁移时退出码 1，`flyway_schema_history` 不存在；修正字段契约后的 RED：旧 V1 已执行时退出码 1，缺少 `username`/`status` 等设计字段；GREEN：仅重建 `yumi_v2_test` 后执行同命令，退出码 0，2 个测试通过，Flyway 从空库创建 `flyway_schema_history`、V1 和三张基础表，Hibernate validate 通过；重复校验：同一数据库再次运行，日志为“Successfully validated 1 migration”“No migration necessary”，测试断言版本 1 成功记录数为 1；阶段门禁：`YUMI_DB_PASSWORD=<钥匙串读取值> ./mvnw -q test`，退出码 0。实际数据库：MySQL 8.4.11，Flyway version 1/foundation，表为 `admin_accounts`、`number_sequences`、`file_metadata`、`flyway_schema_history`；V1 修正为 `BIGINT UNSIGNED`、`DATETIME(6)`、`username/status`、`sequence_key/current_value` 及审计/请求/幂等字段；HTTP 方法/错误码/事务拥有者/锁定对象：不适用；幂等键字段已落库，业务幂等行为属于后续任务 1.6。篡改校验：`FlywayFoundationMigrationTest.rejectsModifiedAppliedMigration` 复制 V1 到临时目录并追加内容后调用 Flyway `validate()`，退出码 0，断言捕获 `FlywayValidateException` 且消息包含 `checksum mismatch`；原仓库迁移文件和测试库未被修改。
  - 人工证据：不适用；如涉及页面/打印/Electron，回填 `humanVisualConclusion.checklist`，状态为 `pending-user-signoff`。
  - 阶段门禁回填（1.12，2026-09-23）：Maven 全量 39 测试 0 失败（含 Modulith 边界）、前端 `npm run typecheck`/`npm test`（4 文件 18 用例）/`npm run build` 退出码 0、`openspec validate build-yumi-v2-order-fulfillment --strict` 通过、浏览器与 Electron 均由用户经正式 `/login` 登录并访问同一 `/api/session`（实据见 1.12）；Testcontainers 按 1.3 书面豁免。本任务在该门禁下复验有效。
- [x] 1.5 实现 `POST /api/session`、`DELETE /api/session` 和当前会话查询，密码安全哈希、会话安全属性及 `AUTH_REQUIRED/AUTH_INVALID`；前端建立 `/login` 和受保护路由，浏览器与 Electron 共用认证状态。
  - 证据：Requirement/Scenario：`platform-foundation` Requirement“系统必须提供受保护的管理员访问”（未认证访问被拒绝、登录建立会话、退出销毁会话）与 Requirement“统一错误契约”；正式文档：`design.md` 第 6 节（`POST /api/session` 匿名认证、拒绝码 `AUTH_INVALID`）、第 7 节（`/login` 验收点“未认证不可进入业务页”）、`docs/architecture/system-architecture.md` 第 2 节（浏览器与 Electron 访问同一套 React 前端和同一业务 API）；文件：`backend/src/main/java/com/yumi/identity/{IdentityConfiguration,SessionService,SessionController,SecurityConfiguration,SessionAuthenticationFilter}.java`、`backend/src/test/java/com/yumi/SessionAuthenticationTest.java`、`frontend/src/main.tsx`、`frontend/vite.config.ts`（新增 `/api` 开发代理，目标可用 `VITE_API_TARGET` 覆盖）；API：`POST /api/session`（登录）、`GET /api/session`（当前会话）、`DELETE /api/session`（退出）；错误码：`AUTH_INVALID`（401）、`AUTH_REQUIRED`（401，含受保护 API 未认证入口点）；表：只读 `admin_accounts`（V1__foundation.sql，无新增迁移，Flyway 版本保持 1）；事务拥有者/锁定对象/幂等键：不适用，会话为内存令牌表按令牌存取、账号按用户名单行点查，非业务写命令。
  - RED（既有记录）：首次 `./mvnw -q -Dtest=SessionAuthenticationTest test` 退出码 1，先因缺少 `spring-boot-starter-web` 导致 `MediaType` 类缺失；补齐后因未设置 `YUMI_DB_PASSWORD` 退出码 1，原文 `Access denied for user 'yumi_v2_test'@'localhost' (using password: NO)`，未到达 HTTP 断言。RED（本次新增契约）：受保护 API 未认证用例退出码 1，原文 `Status expected:<401> but was:<403>`（Spring Security 默认对匿名返回裸 403，缺少统一错误体）。
  - GREEN：注入钥匙串口令后 `YUMI_DB_PASSWORD=<钥匙串读取值> ./mvnw -q -Dtest=SessionAuthenticationTest test`，退出码 0，5 个测试 0 失败 0 错误；关键断言：未认证 `GET /api/session`→401 `AUTH_REQUIRED`；未认证 `GET /api/orders`→401 `AUTH_REQUIRED` 且响应含 `code/message/fieldErrors/requestId`；错误密码→401 `AUTH_INVALID`；正确凭据→200 `{"id":5,"username":"admin"}` 且下发 `YUMI_SESSION` Cookie（HttpOnly、Secure、Path=/、Max-Age=28800）；`DELETE`→204 并清空 Cookie，随后 GET→401 `AUTH_REQUIRED`。
  - 本机运行实例 HTTP 黑盒复核（`SERVER_PORT=8081`，curl）：错误密码 401 `AUTH_INVALID`；登录 200 + Cookie 属性同上；带 Cookie GET 200；DELETE 204；注销后再 GET 401；受保护 `/api/orders` 及经浏览器代理 `http://localhost:5173/api/orders` 均 401 统一 JSON。密码不写入仓库，本地测试管理员为测试库种子数据，启动口令经钥匙串注入。
  - 前端/浏览器验证（正式路由，非预览）：浏览器访问 `/` 与深链 `/orders` 均重定向到 `/login`（快照 URL=`http://localhost:5173/login`，渲染“YUMI V2/管理员登录/用户名/密码/登录”）；网络日志记录 `GET http://localhost:5173/api/session [401]`，证明页面经同一业务 API 取认证状态；`npm run build`（`tsc --noEmit` + vite）退出码 0。本机 8080 被无关项目 `ai-audit-product-be` 进程占用，联调使用 `SERVER_PORT=8081` 与 `VITE_API_TARGET=http://localhost:8081`，应用默认端口与代理默认目标仍为 8080；浏览器内点击登录的完整交互与 Electron 复用由 1.9、1.12 阶段门禁统一验证并回填。
  - 人工证据：不适用；如涉及页面/打印/Electron，回填 `humanVisualConclusion.checklist`，状态为 `pending-user-signoff`。
  - 阶段门禁回填（1.12，2026-09-23）：Maven 全量 39 测试 0 失败（含 Modulith 边界）、前端 `npm run typecheck`/`npm test`（4 文件 18 用例）/`npm run build` 退出码 0、`openspec validate build-yumi-v2-order-fulfillment --strict` 通过、浏览器与 Electron 均由用户经正式 `/login` 登录并访问同一 `/api/session`（实据见 1.12）；Testcontainers 按 1.3 书面豁免。本任务在该门禁下复验有效。
- [x] 1.6 实现统一错误 `{code,message,fieldErrors,requestId}`、请求 ID、`Idempotency-Key` 存储与重复请求返回；覆盖 `VALIDATION_*`、`STATE_*`、`CONFLICT_*`、`QUANTITY_*`、`SOURCE_*`、`FINANCE_*`，以 HTTP 黑盒测试固定 JSON 契约。
  - 证据：Requirement/Scenario：`platform-foundation` Requirement“系统必须提供统一错误契约”（Scenario“数量边界错误”“并发版本冲突”）与 Requirement“系统必须提供受保护的管理员访问”（Scenario“重复幂等请求不重复写入”）；正式文档：`design.md` 第 2 节（写命令要求 `Idempotency-Key`，重复键返回首次结果或同一业务冲突）、第 3 节（错误码分层与统一信封）、第 6 节（冻结错误码矩阵）；文件：`backend/src/main/java/com/yumi/shared/error/{ErrorCode,ApiError,ApiFieldError,ApiException,GlobalExceptionHandler}.java`、`shared/error/package-info.java`、`shared/web/{RequestIdFilter,WebInfrastructureConfiguration}.java`、`shared/web/package-info.java`、`shared/idempotency/{IdempotencyFilter,IdempotencyRecordStore}.java`、`shared/package-info.java`、`backend/src/main/resources/db/migration/V2__idempotency.sql`、`backend/pom.xml`（新增 `spring-boot-starter-validation`）、`identity/{SecurityConfiguration,SessionController}.java`（切换到共享错误类型）、测试 `UnifiedErrorContractTest`(12)、`IdempotencyReplayTest`(5)、`fixture/FixtureController.java`（测试专用契约固定端点）、`ModuleStructureTest`（模块清单更新为 6 业务模块 + shared）；Flyway 版本：1→2，新表 `idempotency_records`（`idempotency_key` 全局唯一 + 请求指纹 + 状态/内容类型/响应体）；HTTP：`RequestIdFilter` 于安全链之前为所有请求分配/透传 `X-Request-Id`（合法 `[A-Za-z0-9._-]{1,64}` 沿用、否则重生成）并写入响应头与 MDC；`IdempotencyFilter` 作用于 `/api/*` 的 POST/PUT/PATCH/DELETE（`/api/session` 豁免），缺 `Idempotency-Key` 返回 400 `VALIDATION_INVALID` 且 `fieldErrors[0].field=Idempotency-Key`，同键同管理员同方法同路径同指纹重放首次结果（2xx/409 落库，头 `X-Idempotency-Replayed: true`），同键异请求返回 409 `CONFLICT_IDEMPOTENCY`，5xx 不落库允许同键重试，并发同键由唯一约束兜底；状态码映射：输入类 400（`VALIDATION_INVALID` 等）、认证 401、未映射路径 404 `NOT_FOUND`、并发/状态/不变量/收退款 409（`STATE_*`、`CONFLICT_*`、`QUANTITY_*`（非法输入除外）、`SOURCE_*`、`PAYMENT_/REFUND_/CLOSE_*` 即 design §3 的 FINANCE 语义层，矩阵冻结的具体码不另造 `FINANCE_` 前缀）、内部 500 `INTERNAL_ERROR`、迁移 503 `MIGRATION_INVALID`；事务拥有者/锁定对象：不适用（本任务无业务写事务，幂等记录为单行 INSERT，唯一键并发兜底）；`ObjectOptimisticLockingFailure`→`CONFLICT_VERSION`、`DuplicateKeyException`→`CONFLICT_DUPLICATE`、Bean Validation/坏 JSON/类型不匹配/缺请求头→`VALIDATION_INVALID`、其余异常→`INTERNAL_ERROR`（日志带 requestId）。
  - 派生码说明（设计未逐一枚举、按 §3 家族与信封完整性补充，待用户复核）：`CONFLICT_IDEMPOTENCY`（§3 明确 CONFLICT_* 覆盖幂等）、`INTERNAL_ERROR`（统一信封兜底）、`NOT_FOUND`（未映射路径 404 信封）。
  - RED：`YUMI_DB_PASSWORD=<钥匙串读取值> ./mvnw -q -Dtest='UnifiedErrorContractTest,IdempotencyReplayTest,ModuleStructureTest' test`，退出码 1，Tests run: 19, Failures: 6, Errors: 11；关键失败原文：`Status expected:<400> but was:<200>`（缺幂等键未拦截）、`Status expected:<409> but was:<201>`（同键异请求未冲突）、`Response header 'X-Idempotency-Replayed' expected:<true> but was:<null>`、`No value at JSON path "$.code"`（未走统一信封）、`Servlet Request processing failed: com.yumi.shared.error.ApiException`（无全局异常处理）、`Module 'identity' depends on non-exposed type com.yumi.shared.error.ErrorCode`（shared 子包未声明 API 暴露）。
  - GREEN：同命令退出码 0，`UnifiedErrorContractTest` 12 个、`IdempotencyReplayTest` 5 个、`ModuleStructureTest` 2 个测试全部通过（0 失败 0 错误）；关键断言：六族错误码经固定端点逐一返回信封与映射状态码；`fieldErrors` 携带字段定位（`name`/`quantity`/`Idempotency-Key`）；入站 `X-Request-Id: contract-req-1` 在响应头与 `$.requestId` 同值回显，非法入站值被重生成；同键同请求重放返回同一 token 且 `number_sequences` 事实行只增 1、`idempotency_records` 恰 1 行；同键异请求 409 `CONFLICT_IDEMPOTENCY`；首次 500 后同键重试执行成功（5xx 未落库）；409 业务冲突结果同键重放；GET 不要求幂等键。
  - 全量回归：`YUMI_DB_PASSWORD=<钥匙串读取值> ./mvnw -q test`，退出码 0，全仓 29 个测试 0 失败。
  - 本机运行实例抽查（`SERVER_PORT=8081`，curl）：未认证 `GET /api/orders` 401 信封且入站 `X-Request-Id: live-req-1.6` 同值回显；已认证未映射路径 404 `NOT_FOUND` 信封；Flyway 历史 version 1 `foundation`、version 2 `idempotency` 均 success，`idempotency_records` 表存在。业务写端点尚未落地（阶段二起），幂等必填规则由过滤器对全部 `/api/*` 写命令自动生效，后续业务任务经 HTTP 测试直接继承。
  - 响应信封修订（2026-09-24 用户拍板 A 案，覆盖本任务与 1.5/1.8 口径）：`design.md` §2/§3 已更新——所有 API 响应统一 `{code, message, fieldErrors, requestId, data}`，成功 `code="OK"`、`data=业务负载`，失败 `data=null`、HTTP 状态码保持语义、`204` 无体；实现：新增 `shared/error/{ApiEnvelope,EnvelopeAdvice}.java`（`ResponseBodyAdvice` 统一包裹成功返回值，覆盖控制器与 Actuator），删除 `ApiError` 并全量切换 `ApiEnvelope.error(...)`，安全入口点、幂等过滤器、异常处理产出同一信封；前端 `src/api/client.ts` 封装层解包 `data`，对任意非 `OK` 业务码（含 HTTP 200 响应）一律抛 `YumiApiError`，调用处只处理业务提示。RED：后端 `SuccessEnvelopeContractTest` 退出码 1、6 失败，原文 `No value at JSON path "$.code"`；前端 `npm test` 退出码 1、2 失败（不解包 / 200+非OK 未抛错）。GREEN：后端 `./mvnw -q test` 退出码 0、13 类 51 测试 0 失败（信封 6 用例含 `$.data.status=UP`、204 无体、错误 `data=null`、`X-Request-Id` 回显）；前端 `npm test` 4 文件全过、`npm run typecheck`/`npm run build` 退出码 0（断言：解包后金额仍为字符串 `"12.3400"`、200+`CONFLICT_DUPLICATE` 抛错、204→undefined）。追踪入口：响应头 `X-Request-Id` + `audit_logs.request_id` + 日志 MDC，不变。
  - 人工证据：不适用；如涉及页面/打印/Electron，回填 `humanVisualConclusion.checklist`，状态为 `pending-user-signoff`。
  - 阶段门禁回填（1.12，2026-09-23）：Maven 全量 39 测试 0 失败（含 Modulith 边界）、前端 `npm run typecheck`/`npm test`（4 文件 18 用例）/`npm run build` 退出码 0、`openspec validate build-yumi-v2-order-fulfillment --strict` 通过、浏览器与 Electron 均由用户经正式 `/login` 登录并访问同一 `/api/session`（实据见 1.12）；Testcontainers 按 1.3 书面豁免。本任务在该门禁下复验有效。
- [x] 1.7 实现当前管理员审计上下文和写命令审计，保存业务编号、请求 ID、幂等键、服务端时间、结果；验证失败事务不留下成功事实但保留允许的安全审计。
  - 证据：Requirement/Scenario：`platform-foundation` Requirement“系统必须提供受保护的管理员访问”（“每次写入 SHALL 记录管理员、请求 ID、幂等键、业务编号、服务端时间和结果”）；正式文档：`docs/architecture/system-architecture.md` 第 4.1 节（identity 负责当前操作人和审计上下文）、`design.md` 第 5 节（事实不可变与审计字段）、`docs/architecture/database-design.md` 第 15 节（Flyway 命名与门禁）；文件：`backend/src/main/java/com/yumi/identity/{AuditContext,WriteAuditFilter,WriteAuditRepository,WriteAuditConfiguration}.java`、`backend/src/main/resources/db/migration/V3__audit_logs.sql`、测试 `WriteCommandAuditTest.java`(4)、`fixture/FixtureController.java`（新增 `/audit-context`、`/counter-then-fail` 固定端点）；Flyway 版本：2→3，新表 `audit_logs`（`admin_username/request_id/idempotency_key/business_no/http_method/path/result/response_status/error_code/occurred_at` + 版本审计列，索引 `request_id/business_no/occurred_at`）；HTTP：`WriteAuditFilter` 注册于 `/api/*` 写方法（POST/PUT/PATCH/DELETE），顺序 -50（安全链 -100 之后、幂等过滤器 0 之前），短路响应（401/400 幂等拒绝/重放）同样被审计；错误码取自响应体 `code`；事务拥有者/锁定对象/幂等键：审计在业务事务之外独立单行 INSERT（不受业务回滚影响，也无需锁定）；匿名认证（`AnonymousAuthenticationToken`）不记为操作人；`business_no` 列已就位，取值待阶段二起业务命令经 `AuditContext` 注入（当前无业务写命令，值为 NULL——测试已固定该行为）。
  - RED：`YUMI_DB_PASSWORD=<钥匙串读取值> ./mvnw -q -Dtest=WriteCommandAuditTest test`，退出码 1，Tests run: 4, Failures: 3；关键失败原文：`[request_id=audit-req-1 的审计记录]`/`audit-req-2`/`audit-req-3` 为空（写审计过滤器尚未实现），审计上下文用例在 RED 阶段已通过。
  - GREEN：同命令退出码 0，4 个测试 0 失败 0 错误；关键断言：成功写命令记录 `admin_username/idempotency_key/http_method/path/result=SUCCESS/response_status=201/business_no=NULL/occurred_at` 齐全且 `request_id` 与入站 `X-Request-Id` 一致；`POST /counter-then-fail` 事务内先写事实后失败 → 500、`number_sequences` 事实行数不变（回滚）但 `audit_logs` 保留 `result=FAILURE, error_code=INTERNAL_ERROR, response_status=500` 的安全审计；登录失败记录 `path=/api/session, result=FAILURE, error_code=AUTH_INVALID, admin_username=NULL, response_status=401`；`GET /audit-context` 返回 `adminUsername/requestId/idempotencyKey/serverTime` 与入站一致。
  - 全量回归：`YUMI_DB_PASSWORD=<钥匙串读取值> ./mvnw -q test`，退出码 0，全仓 8 个测试类共 33 个测试 0 失败 0 错误。
  - 人工证据：不适用；如涉及页面/打印/Electron，回填 `humanVisualConclusion.checklist`，状态为 `pending-user-signoff`。
  - 阶段门禁回填（1.12，2026-09-23）：Maven 全量 39 测试 0 失败（含 Modulith 边界）、前端 `npm run typecheck`/`npm test`（4 文件 18 用例）/`npm run build` 退出码 0、`openspec validate build-yumi-v2-order-fulfillment --strict` 通过、浏览器与 Electron 均由用户经正式 `/login` 登录并访问同一 `/api/session`（实据见 1.12）；Testcontainers 按 1.3 书面豁免。本任务在该门禁下复验有效。
- [x] 1.8 建立 React + TypeScript 共享前端工程、类型安全 API 客户端、错误码映射和正式路由外壳；金额字段保持字符串，不允许客户端浮点结果覆盖服务端。
  - 证据：Requirement/Scenario：`platform-foundation` Requirement“浏览器和 Electron 必须使用同一业务 API”（Scenario“两种客户端读取同一订单”：客户端不得覆盖服务端金额、数量和状态）；正式文档：`design.md` 第 3 节（前端按 code 分支不反解析 message）、第 7 节（前端路由与操作矩阵）、`docs/architecture/system-architecture.md` 第 2 节（浏览器与 Electron 共用同一 React 前端）；文件：`frontend/package.json`（新增依赖 `react-router-dom` 7.18.4、`antd` 6.6.5、`@ant-design/icons` 6.3.4、`vitest` 5.0.1；脚本 `typecheck`/`test`）、`src/api/errors.ts`（`ERROR_CODES` 51 码目录与后端 `ErrorCode` 对齐、`YumiApiError`、`errorAction` 穷举映射 + 未知码回退）、`src/api/client.ts`（`apiFetch<T>`：`credentials:include`、自动 `X-Request-Id`、写命令自动 `Idempotency-Key`、错误信封解析为 `YumiApiError`、非信封归一 `INTERNAL_ERROR`；`type Money = string`）、`src/api/session.ts`（登录/会话/登出类型化封装）、`src/routes/paths.ts`（12 条正式路由清单与 `isProtectedPath`）、`src/routes/index.tsx`（`createBrowserRouter` 路由表：`/` 重定向、`/login`、受保护布局 + design §7 全部正式路径占位页 + 兜底页）、`src/pages/{LoginPage,ProtectedLayout,PlaceholderPage}.tsx`（antd 登录表单、Sider 单栏树形菜单含订单/基础资料/库存/生产/报表、占位页标注对应任务号）、`src/main.tsx`（`ConfigProvider` 中文 locale + `AntApp` + `RouterProvider`）、`src/styles.css`、`index.html`（补全 doctype/head 结构）、测试 `src/api/errors.test.ts`、`src/api/client.test.ts`、`src/routes/paths.test.ts`；API：`GET/POST/DELETE /api/session`（经类型化客户端）；错误码：映射表覆盖 `AUTH_/VALIDATION_/STATE_/CONFLICT_/QUANTITY_/SOURCE_` 及 FINANCE 语义层 `PAYMENT_/REFUND_/CLOSE_`、`AFTER_SALES_/SHIPMENT_/STOCK_` 等全部 51 码；路由：12 条正式路由与 design §7 矩阵一致（单测固定清单）；Flyway/表：不适用（纯前端任务，支撑“客户端不覆盖服务端金额”场景）；事务/锁定/幂等键：客户端在写命令上自动生成并携带 `Idempotency-Key`（单测断言），服务端幂等语义由 1.6 过滤器兑底。
  - RED：`npm test`，退出码 1，Test Files 3 failed，Tests 10 failed | 3 passed；关键原文：`Error: not implemented`（客户端桩）、路由清单与 `isProtectedPath` 断言 `expected true to be false`/数组不等、`expected Error: not implemented to match object { name: 'YumiApiError' }`、错误动作映射返回空串失败。
  - GREEN：`npm test` 退出码 0，3 个测试文件 13 个用例全部通过（关键断言：金额 `{"amount":"12.3400"}` 经客户端返回仍为字符串；409 信封抛出 `YumiApiError` 且保留 `code/status/requestId`；写命令自动携带 `Idempotency-Key` 与 `X-Request-Id` 且 `credentials:include`；未知错误码回退“操作未完成，请稍后重试或联系管理员”；`CONFLICT_VERSION→请刷新后重试`、`AUTH_REQUIRED→请重新登录`；`ROUTE_PATHS` 与 design §7 十二条完全一致、仅 `/login` 免认证）；`npm run typecheck` 退出码 0；`npm run build`（`tsc --noEmit && vite build`）退出码 0（仅 chunk>500kB 提示，antd 体积告警，无错误）。
  - 浏览器验证（正式路由，非预览）：访问 `/` 与深链 `/catalog/products` 均经路由 loader 落到 `/login`（快照 URL=`http://localhost:5173/login`），antd 登录卡片渲染“YUMI V2/管理员登录/用户名/密码/登录”，控制台仅出现预期的 `/api/session` 401 日志（认证探测），无 JS 报错；截图 `/tmp/yumi-1_8-login.png`。登录后的受保护外壳（侧栏树形菜单、占位页切换、退出登录）需真实凭据，交互式浏览器/Electron 验证与 1.5 一致归 1.9、1.12 阶段门禁统一执行并回填。
  - Electron 复用结果：Electron 薄壳尚未建立（属任务 1.9）；本任务交付的即共享唯一 React 前端与同一 `/api/session` 认证状态，运行时复用验证在 1.9、1.12 回填。
  - 人工证据：不适用；外壳与登录页为行为/结构验证，视觉人工验收由 3.15、9.12 的 `humanVisualConclusion.checklist` 统一覆盖，状态为 `pending-user-signoff`。
  - 阶段门禁回填（1.12，2026-09-23）：Maven 全量 39 测试 0 失败（含 Modulith 边界）、前端 `npm run typecheck`/`npm test`（4 文件 18 用例）/`npm run build` 退出码 0、`openspec validate build-yumi-v2-order-fulfillment --strict` 通过、浏览器与 Electron 均由用户经正式 `/login` 登录并访问同一 `/api/session`（实据见 1.12）；Testcontainers 按 1.3 书面豁免。本任务在该门禁下复验有效。
- [x] 1.9 建立 Electron 薄壳，仅暴露窗口、打印、文件选择和系统集成 IPC；增加测试证明主进程不包含数据库凭据、不直连 MySQL、不复制订单规则。
  - 证据：Requirement/Scenario：`platform-foundation` Requirement“浏览器和 Electron 必须使用同一业务 API”（客户端不得直连数据库）；正式文档：`design.md` 第 7 节矩阵“Electron shell｜桌面壳｜打印、文件选择、窗口｜不直连数据库、不复制业务规则”、`docs/architecture/system-architecture.md` 第 2 节（Electron 经 HTTPS 调用 Spring Boot API）；文件：`frontend/electron/main.cjs`（BrowserWindow：1280×800、`contextIsolation:true`、`nodeIntegration:false`、`sandbox:true`、preload 指向 `preload.cjs`；IPC `print`/`chooseFile`/`systemInfo`；`YUMI_WEB_URL` 默认 `http://localhost:5173`）、`frontend/electron/preload.cjs`（`contextBridge` 仅暴露 `window.yumiShell.print/chooseFile/systemInfo`）、`frontend/electron/shell-guard.test.ts`（静态守卫 5 用例）、`frontend/package.json`（devDependency `electron` 44.4.5、脚本 `npm run electron`）；API/表：主进程不提供业务 API、不访问数据库（不适用理由：业务一律走共享前端的 `/api/*`），IPC 通道为 `print`（当前窗口 `webContents.print`）、`chooseFile`（`dialog.showOpenDialog` 单文件）、`systemInfo`（平台与版本）；事务/锁定/幂等键：不适用（无业务写入）。
  - 守卫测试设计：读取 `main.cjs`/`preload.cjs` 源码断言——① 禁止 `/mysql/i`、`jdbc:`、`3306`、`YUMI_DB`、`createConnection/createPool`、`password[:=]` 等数据库凭据/直连模式；② 禁止业务词（订单、履约、发货、应收、退款、结清、售后、库存、生产计划、`QUANTITY_`、`STATE_`、`Idempotency`、`SNAPSHOT_`）证明不复制订单规则；③ `require` 目标白名单仅 `electron` 与 `node:*`；④ 窗口安全配置原文匹配；⑤ preload 暴露面与无 `fs` 引用。
  - RED（变异注入验证守卫有效）：向 `main.cjs` 追加 `const db = require("mysql");` 后 `npm test`，退出码 1，Test Files 1 failed | 3 passed，关键原文 `AssertionError: main.cjs 命中禁止的数据库/凭据模式 /mysql/i: expected true to be false` 及“只依赖 electron 与 Node 内建模块”用例同时失败；随后移除变异行（仓库已无残留，`grep -c mutation-for-red electron/main.cjs` = 0）。
  - GREEN：`npm test` 退出码 0，4 个测试文件 18 个用例全部通过（含 `shell-guard.test.ts` 5 个守卫用例）。
  - 启动冒烟：`npx electron electron/main.cjs`（dev server 5173 在线），主进程日志 `[yumi-electron] ready url=http://localhost:5173`，窗口创建并加载共享前端成功后主动结束进程；`npx electron --version` = v44.4.5。
  - Electron 内 `/login` 交互登录与 `window.yumiShell` 打印/文件选择的运行时验证：壳已具备，统一归 1.12 阶段门禁在 Electron 中执行并回填（与 1.5、1.8 同口径）。
  - 人工证据：不适用；如涉及页面/打印/Electron，回填 `humanVisualConclusion.checklist`，状态为 `pending-user-signoff`（打印视觉验收随 9.3 覆盖）。
  - 阶段门禁回填（1.12，2026-09-23）：Maven 全量 39 测试 0 失败（含 Modulith 边界）、前端 `npm run typecheck`/`npm test`（4 文件 18 用例）/`npm run build` 退出码 0、`openspec validate build-yumi-v2-order-fulfillment --strict` 通过、浏览器与 Electron 均由用户经正式 `/login` 登录并访问同一 `/api/session`（实据见 1.12）；Testcontainers 按 1.3 书面豁免。本任务在该门禁下复验有效。
- [x] 1.10 配置 Actuator 存活/就绪端点和结构化日志脱敏；依据 `platform-foundation`“运行和恢复门禁”验证数据库不可用时 readiness 失败，日志不含密码、完整令牌或凭据。
  - 证据：Requirement/Scenario：`platform-foundation` Requirement“系统必须提供运行和恢复门禁”（Scenario“数据库不可用时未就绪”）；正式文档：`design.md` 第 6 节（`GET /api/actuator/health/liveness|readiness`，认证=受保护运维）、`docs/architecture/system-architecture.md` 第 5 节；文件：`backend/pom.xml`（新增 `spring-boot-starter-actuator`、`logstash-logback-encoder` 9.0）、`backend/src/main/resources/application.yml`（`management.endpoints.web.base-path=/api/actuator`、只暴露 `health`、`probes.enabled=true`、readiness 组 `include: readinessState,db`、liveness 组 `include: livenessState`）、`backend/src/main/resources/logback-spring.xml`（`LogstashEncoder` 结构化 JSON 行 + `MaskingJsonGeneratorDecorator`：字段路径掩码 `password,pwd,token,accessToken,refreshToken,authorization,cookie,secret,credential,sessionToken` 整体替换 `****`；消息值双通道掩码——`Bearer <token>` 优先、`password/token/secret/authorization/cookie/credential` 赋值右值掩码，掩码器链式应用故 Bearer 规则置于通用规则之前）、测试 `ActuatorProbeTest`(3)、`ActuatorReadinessDbDownTest`(2)、`StructuredLogMaskingTest`(1)；API：`GET /api/actuator/health/liveness`、`GET /api/actuator/health/readiness`（受保护，未认证返回统一信封 401 `AUTH_REQUIRED`）；错误码：`AUTH_REQUIRED`；表/Flyway：不适用（未新增迁移，健康检查只读探测既有库）；事务/锁定/幂等键：不适用（只读健康端点，GET 不要求幂等键）。
  - RED：`YUMI_DB_PASSWORD=<钥匙串读取值> ./mvnw -q -Dtest='ActuatorProbeTest,ActuatorReadinessDbDownTest,StructuredLogMaskingTest' test`，退出码 1，Tests run: 6, Failures: 5；关键原文：`Status expected:<200> but was:<404>`（探针路径未配置）、`readiness/liveness healthForPath 断言失败`（探针组未创建）、脱敏用例 `doesNotContain("TopSecret123")` 失败（普通 pattern 日志、无 JSON、无掩码）。
  - GREEN：同命令退出码 0，6 个测试全部通过；关键断言：未认证探针 401 统一信封；正常库下 liveness/readiness 均 `{"status":"UP"}`；指向无监听端口 3399、关闭 Flyway/DDL 校验的独立上下文中 `healthForPath("readiness").getStatus()=DOWN` 而 `liveness=UP`（db 组参与就绪、存活不受库影响）；日志用例断言输出含 `"level"` 与 `yumi.log.probe`（结构化 JSON）、不含 `TopSecret123/Abc.Def123/xyz.token.789/MdcSecret789`、含 `****`、MDC `requestId=mask-req-1` 保留明文（只掩凭据字段）。
  - 全量回归：`YUMI_DB_PASSWORD=<钥匙串读取值> ./mvnw -q test`，退出码 0，11 个测试类共 39 个测试 0 失败 0 错误。
  - 本机运行实例抽查（`SERVER_PORT=8081`）：未认证 `GET /api/actuator/health/readiness` → 401 `AUTH_REQUIRED` 信封；管理会话下 liveness/readiness 均 `{"status":"UP"}`；启动控制台输出为 `{"@timestamp":...}` 结构化 JSON 行。
  - 人工证据：不适用；如涉及页面/打印/Electron，回填 `humanVisualConclusion.checklist`，状态为 `pending-user-signoff`（就绪探针的生产保护与 9.5/9.6 发布门禁统一验收）。
  - 阶段门禁回填（1.12，2026-09-23）：Maven 全量 39 测试 0 失败（含 Modulith 边界）、前端 `npm run typecheck`/`npm test`（4 文件 18 用例）/`npm run build` 退出码 0、`openspec validate build-yumi-v2-order-fulfillment --strict` 通过、浏览器与 Electron 均由用户经正式 `/login` 登录并访问同一 `/api/session`（实据见 1.12）；Testcontainers 按 1.3 书面豁免。本任务在该门禁下复验有效。
- [x] 1.11 建立阶段通用 TDD/证据模板：每项先附失败测试命令、退出码和正确 RED 原文，再附 GREEN 命令、退出码、关键断言；需要人眼判断时写 `humanVisualConclusion.checklist` 并保持 `pending-user-signoff`。
  - 证据：Requirement/Scenario：`design.md` 第 9 节“测试和人工证据”对 RED/GREEN 与人工签字的统一要求，以及 `tasks.md`“全局完成定义”第 2、5、6 条（本任务即把该定义固化为可复用模板）；正式文档：`design.md` 第 9 节、`tasks.md` 全局完成定义；文件：`openspec/changes/build-yumi-v2-order-fulfillment/evidence-template.md`（新建），含四节——①证据条目结构与 6 条字段规则（RED 必须为行为断言失败、GREEN 必须含数量与断言、不适用须写理由、命令可原样复跑且口令走钥匙串、前端任务附加路由/构建/浏览器/Electron 字段、预览路由不作证据）；②命令速查表（后端单测/全量、前端 typecheck/test/build、Electron 守卫、`openspec validate --strict`）与退出码取证规范（禁用管道末端退出码）；③`humanVisualConclusion` 结构（`status/checklist/confirmedBy/confirmedOn/conclusion`，未确认恒为 `pending-user-signoff`，截图与自动化不可替代用户确认，共享验收的取代关系写法）；④已完成条目索引（1.6 行为 RED、1.9 变异 RED、1.6 不适用写法、1.8 浏览器验证写法）；API/表/路由/错误码：不适用（纯流程模板，无运行时行为，其支撑场景为全部后续任务的证据格式与阶段九 9.10/9.11 追踪审计）；事务/锁定/幂等键：不适用（无代码变更）。
  - RED/GREEN：不适用及理由——交付物为文档模板，不产生外部行为，无法构造行为断言型 RED；模板完整性以结构化 grep 复核代替：`grep -c "^## " evidence-template.md` 返回 4（四个章节齐全）、`grep -c "pending-user-signoff" evidence-template.md` ≥2、`grep -c "confirmedBy" evidence-template.md` ≥1，退出码 0；后续每个任务的 RED/GREEN 均按本模板回填，9.11 门禁与人工审查持续校验其执行。
  - 人工证据：不适用；模板本身不含页面/打印/Electron 视觉判断，其规定的 `humanVisualConclusion` 格式将随首个视觉任务（2.10/3.12）启用并保持 `pending-user-signoff`。
  - 阶段门禁回填（1.12，2026-09-23）：Maven 全量 39 测试 0 失败（含 Modulith 边界）、前端 `npm run typecheck`/`npm test`（4 文件 18 用例）/`npm run build` 退出码 0、`openspec validate build-yumi-v2-order-fulfillment --strict` 通过、浏览器与 Electron 均由用户经正式 `/login` 登录并访问同一 `/api/session`（实据见 1.12）；Testcontainers 按 1.3 书面豁免。本任务在该门禁下复验有效。
- [x] 1.12 阶段门禁：运行 Maven 全量、Modulith、Testcontainers Flyway、前端 typecheck/unit、Electron build；从浏览器和 Electron 正式 `/login` 登录并访问同一 API，证据回填到本阶段每项任务。
  - 证据：Requirement/Scenario：`platform-foundation` Requirement“系统必须提供运行和恢复门禁”与 Requirement“浏览器和 Electron 必须使用同一业务 API”（Scenario“两种客户端读取同一订单”的同 API 同会话前提）；正式文档：`design.md` 第 6、7、9 节、`tasks.md` 全局完成定义第 2、4、6 条；执行日期 2026-09-23，全部命令在对应目录原样可复跑：
    - Maven 全量（含 Modulith 边界）：`backend/` 下 `YUMI_DB_PASSWORD=<钥匙串读取值> ./mvnw -q test`，退出码 0，11 个测试类 39 个测试 0 失败 0 错误（`ModuleStructureTest.verify()` 即 Modulith 门禁，含在内）。
    - 前端：`frontend/` 下 `npm run typecheck` 退出码 0；`npm test` 退出码 0（4 文件 18 用例，含 Electron 壳守卫 5 例）；`npm run build`（tsc+vite）退出码 0。
    - Electron build：前端构建产物 + `npm run electron` 壳启动（1.9 冒烟日志 `[yumi-electron] ready url=http://localhost:5173`）+ 本任务用户真实登录；electron-builder 应用打包属部署阶段，按 9.14 范围不做。
    - Testcontainers Flyway：按 1.3 书面豁免（用户 2026-09-23 选择“维持暂缓+书面豁免”），不作为本门禁失败项，缺口跟踪至 9.11 前再议。
    - 规格校验：仓库根 `openspec validate build-yumi-v2-order-fulfillment --strict`，退出码 0，“Change is valid”。
  - 浏览器正式 `/login` 登录（用户本人输入凭据，工具不代填）：网络日志序列 `GET /api/session [401]`（初始探测）→ **`POST /api/session [200]`**（真实登录）→ `GET /api/session [200]`×2（会话态），全程经 Vite 代理打到同一后端；登录后快照 URL=`http://localhost:5173/orders`，外壳含侧栏树形菜单（订单工作台/基础资料→商品、客户、员工/库存/生产/报表）、顶栏 `admin` 与退出登录、占位页“待 3.11 实现”，控制台无 JS 错误；截图 `/tmp/yumi-1_12-browser-shell.png`；`audit_logs` 实据：id=150 `POST /api/session SUCCESS 200 @ 23:53:45`，且 id=149/148 为用户先两次输错密码留下的 `FAILURE/AUTH_INVALID` 安全审计（1.7 失败保留审计的真实场景验证）。用户确认原话：“登录了”。
  - Electron 正式 `/login` 登录（同一账号、同一 `localhost:5173` 前端、同一 `/api/session`）：`audit_logs` id=151 `POST /api/session SUCCESS 200 @ 23:55:43`，与浏览器记录（id=150）时间相邻、`request_id` 各自独立，证明两个客户端经同一业务 API 各自建立会话，主进程无任何数据库访问（1.9 守卫）；Electron 进程验收后已关闭。用户确认原话：“Electron 里也登录了”。
  - 回填执行：已用脚本向本阶段 1.1-1.11 共 11 个任务条目末尾各插入一行“阶段门禁回填（1.12，2026-09-23）”（`inserted 11`），内容含门禁命令结果与 Testcontainers 豁免口径，实现“证据回填到本阶段每项任务”。
  - 人工证据：本项为操作性验收——用户本人在浏览器与 Electron 两处输入凭据完成登录并分别口头确认（“登录了”/“Electron 里也登录了”），非视觉判断结论，故不设 `pending-user-signoff` 清单；视觉类人工验收仍由 2.12、3.15、4.14、9.12 等任务的 `humanVisualConclusion.checklist` 覆盖，状态保持 `pending-user-signoff` 直至对应任务执行。

## 2. 阶段二：商品、客户与员工基础资料（依赖 1.1-1.12）

- [x] 2.1 编写 Flyway 迁移创建 `products/customers/employees/employee_work_types/employee_employment_events/master_data_change_logs`，落实业务编号唯一、金额 `DECIMAL(19,4)`、比例 `DECIMAL(9,6)`、版本和审计字段；参考 `docs/architecture/database-design.md` 第 4、14 节。
  - 证据：Requirement/Scenario：`master-data-management` Requirement“系统必须管理商品、客户和员工资料”与 Requirement“基础资料金额和成本必须保持精度”；正式文档：`docs/architecture/database-design.md` 第 2、3、4、14 节；文件：`backend/src/main/resources/db/migration/V4__catalog.sql`、`backend/src/test/java/com/yumi/CatalogMigrationTest.java`、支撑件 `shared/numbering/SequenceAllocator.java` + `SequenceAllocatorTest.java`（database-design §12 编号分配，事务内 `SELECT … FOR UPDATE` 递增，P/C/E 各自序列）；Flyway 版本：1→4（V4=`catalog`）；表：`products/customers/employees/employee_work_types/employee_employment_events/master_data_change_logs` + 支撑 `star_levels/packaging_tiers/catalog_settings`（商品成本快照全局来源，首期无设置页属已知边界）；关键约束：`product_no/customer_no/employee_no` 为 `CHAR(6)` 唯一，金额 `DECIMAL(19,4)`、比例 `DECIMAL(9,6)`、包装分钟 `DECIMAL(9,3)`、`version BIGINT UNSIGNED`、全表审计与幂等列，不可变事件/日志表仅追加，`employee_work_types(employee_id,work_type)` 唯一；种子：星级 1-5 → 5/10/15/20/30 分钟（《新核算表》示例顺序，星级越高时长越长——**派生决策待复核**，全局可改不回溯）、`catalog_settings` 七键全零预置（不发明业务默认值）、`packaging_tiers` 不预置（业务数据，设置页为后续 change 候选）；API/错误码/路由：不适用（纯迁移）；事务/锁定/幂等键：迁移与 DDL 不适用，序列递增的锁定语义由 `SequenceAllocatorTest`（`@Transactional` 内两次 next 递增）覆盖。
  - RED：`YUMI_DB_PASSWORD=<钥匙串读取值> ./mvnw -q -Dtest=CatalogMigrationTest test`，退出码 1，Tests run: 3, Failures: 2, Errors: 1；关键原文 `[缺少表 products]`、`appliesVersionFourMigration expected: 1`、`columnType … expected 1, actual 0`。
  - GREEN：同命令退出码 0，3 个测试通过；断言：九表存在、`product_no=char(6)`、`sale_price=decimal(19,4)`、`loss_rate=decimal(9,6)`、审计/幂等列齐全、六个唯一索引命中、`flyway_schema_history` version 4 success=1；`SequenceAllocatorTest` 退出码 0（单调递增、`P00007` 格式、P/C/E 序列互不干扰）。全量回归：`./mvnw -q test` 退出码 0，13 类 51 测试 0 失败（含统一信封修订后全量，见 1.6 修订证据）。
  - 修订（2026-09-24）：星级表随 V5 调整为用户自建静态条目（`star_levels` 去 `star` 列、`name` 唯一，`products.star`→`star_level_id`），详见 2.10 修订第 7 条与 `CatalogMigrationTest` V5 断言；V4 证据保留为当时事实。
  - 人工证据：不适用；如涉及页面/打印/Electron，回填 `humanVisualConclusion.checklist`，状态为 `pending-user-signoff`。
- [x] 2.2 实现商品编号分配、创建、编辑、启停和查询 API：`GET/POST /api/products`、`GET/PATCH /api/products/{id}`、`POST .../enable|disable`；覆盖 `master-data-management`“管理商品、客户和员工资料”的有效商品和停用场景。
  - 证据：Requirement/Scenario：`master-data-management` Requirement“系统必须管理商品、客户和员工资料”（Scenario“创建有效商品”“停用商品”）；正式文档：`design.md` 第 6 节（商品 API 行）、`database-design.md` 第 4 节；文件：`backend/src/main/java/com/yumi/catalog/product/{ProductController,ProductService,CreateProductRequest,UpdateProductRequest,ToggleProductRequest,ProductDetail,ProductSummary,package-info}.java`、`product/internal/{ProductRow,ProductRepository,ProductPricing,CatalogReference}.java`、测试 `ProductApiTest.java`(10 用例)；API：`POST /api/products`→201、`GET /api/products?status=&name=`（name 包含匹配、status 精确、id DESC）、`GET /api/products/{id}`→200 含快照与派生利润/利润率、`PATCH /api/products/{id}`（`version` 必带→旧值 409 `CONFLICT_VERSION`、整体联动重算）、`POST .../enable|disable`→200；错误码：`VALIDATION_INVALID`(400 含 fieldErrors)、`CONFLICT_VERSION`(409)、`NOT_FOUND`(404)、`AUTH_REQUIRED`、幂等键缺失 400；表：`products`（P 编号 `next("products")`+`format("P",v)` 事务内行锁分配、状态 `ACTIVE/DISABLED`、金额全字符串输出 `ToStringSerializer`）；事务拥有者 `ProductService.@Transactional`；锁定：编号序列行 FOR UPDATE + `WHERE version=?` 乐观控制；幂等键：写过滤器强制。
  - RED：`YUMI_DB_PASSWORD=<钥匙串读取值> ./mvnw test -Dtest='ProductApiTest,ProductPricingTest'`，退出码 1，Tests run: 17, Failures: 15，原文 `createsProductsWithMonotonicPNumberInEnvelope:127 Status expected:<201> but was:<404>`。
  - GREEN：同命令退出码 0，17/17；关键断言：两个创建编号 `P\d{5}` 且严格 +1 单调；必填缺失 400 `fieldErrors[0].field`；旧 version PATCH→409 `CONFLICT_VERSION`；2 次编辑+disable+enable → 变更日志恰 4 行（`hasSize(4)` 追加不覆盖、`containsOnly("p-test-admin")`、request_id 全非空）；列表 status/name 过滤生效。主仓集成回归 `-Dtest='ProductApiTest,ProductPricingTest,FileApiTest,ModuleStructureTest'` 退出码 0。
  - 派生决策（待复核）：创建 201/编辑启停 200；`packagingTierId/imageFileId/note` 缺席或 null=保持原值（暂不支持清除）；列表 name 为 LIKE 包含匹配；商品 404 复用 `NOT_FOUND`（message=商品不存在）；`created_by/updated_by` 留 null（管理员 id 反查不在本期）。
  - 人工证据：不适用；如涉及页面/打印/Electron，回填 `humanVisualConclusion.checklist`，状态为 `pending-user-signoff`（页面归 2.10/2.12）。
- [x] 2.3 实现商品星级、价格、材料/人工/其他成本、缝边参数和四位小数 `BigDecimal` 计算；依据 `docs/requirements/order-fulfillment-requirements.md` 第 5 节冻结公式，测试应收为零时利润率不除零。
  - 证据：Requirement/Scenario：`master-data-management` Requirement“基础资料金额和成本必须保持精度”（Scenario“保存成本组成”）；正式文档：`design.md` 第 2 节（DECIMAL(19,4)/比例(9,6)）、`database-design.md` 第 2、4 节、商品字段确认记录（星级/包装/缝边/损耗率冻结公式）；文件：`product/internal/ProductPricing.java`（纯函数计价）、`ProductDetail`（利润/利润率读时派生）、测试 `ProductPricingTest.java`(7 用例)；公式实现：胶水克重=HALF_UP(weight×(1+损耗率)) 整数克；胶水/色浆成本=克重×快照单价(4)；qty8=floor(480/std)、qty6=floor(360/std)、制品人工费=120÷qty6(4)；包装=档位分钟×0.25+提成(4)；缝边成本=分钟×0.25(4)、参考收费=成本÷0.7(4)（不入总成本）；材料/人工/其他分组与总成本(4)、参考售价=总成本÷0.7(4)；利润率：salePrice==0→`"0.000000"` 否则 (sale−cost)/sale scale6；全部 BigDecimal、JSON 字符串、禁浮点；API/路由：并入 2.2 各端点；表：`products` 快照列 + `star_levels/catalog_settings/packaging_tiers` 参考读取；事务/锁定/幂等键同 2.2。
  - RED：同 2.2 首跑（`pinnedExampleMatchesEveryFormula` 404 失败），退出码 1。
  - GREEN：`-Dtest=ProductPricingTest` 退出码 0，7/7；关键断言（钉死算例逐值）：`glueGrams=324`、`glueCost="3.2400"`、`qty8h=32/qty6h=24`、`productLaborFee="5.0000"`、`seamReferenceFee="1.7857"`、`totalCost="16.2200"`、`referencePrice="23.1714"`、`estimatedProfit="8.7800"`、`estimatedMarginRate="0.351200"`；`salePrice="0.0000"`→`margin="0.000000"` 且 `profit="-16.2200"`（**无除零异常**）；换星 3→1 重算 `qty8h=96/qty6h=72/fee="1.6667"`；`refreshMaterialPrices:true` 才刷新单价（0.0100→0.0200，totalCost→"19.4600"）；克重 270→300 级联 `glueGrams=360/totalCost="17.3000"`；档位 10min+0.2 提成→`packagingLaborFee="2.7000"`。
  - 人工证据：不适用；如涉及页面/打印/Electron，回填 `humanVisualConclusion.checklist`，状态为 `pending-user-signoff`（价格预览归 2.10/2.12）。
  - 修订（2026-09-24）：修复商品编辑全局快照反溯缺陷——星级仅在重新选择时按当前全局重算、档位仅在换成不同档位时重读，其余沿用商品自身快照；由 `SettingsApiTest` 反溯断言（含编辑后二次断言）回归覆盖，详见 2.10 修订第 4 条。
- [x] 2.4 实现商品图片受控上传/读取和文件元数据关联，验证 MIME/大小边界、鉴权、历史订单仅引用快照而非当前文件值。
  - 证据：Requirement/Scenario：`master-data-management` Requirement“系统必须管理商品、客户和员工资料”（商品单主图、快照规则）；正式文档：`database-design.md` 第 2、4 节（file_metadata）、`design.md` 第 6 节；文件：`backend/src/main/java/com/yumi/files/{FileController,FilesMultipartConfiguration,package-info}.java`、`files/api/{FileStorageService,FileResponse,package-info}.java`（`@NamedInterface`）、测试 `FileApiTest.java`(8 用例)；API：`POST /api/files`（multipart `file`，MIME 仅 `image/jpeg|png|webp`，≤5MB，sha256 幂等命中返回既有元数据）→200 `data={fileId,objectKey,contentType,size,sha256}`；`GET /api/files/{id}`→字节流（原 content_type、`Content-Disposition: inline`、不套信封，字节流例外）；错误码：`VALIDATION_INVALID`(400 field=file)、`NOT_FOUND`(404)、`AUTH_REQUIRED`(401 未登录拒读)、幂等键缺失 400；表：`file_metadata`（object_key/sha256 双唯一）+ `products.image_file_id` FK 挂接；文件字节存 `${YUMI_FILES_DIR:/tmp/yumi-files}/<sha256>`；事务拥有者：文件服务（插入受 `uk_file_metadata_sha256`+DuplicateKey 回查兜底）；锁定/幂等键：唯一约束 + 写过滤器 Idempotency-Key + 上传 sha 幂等双层。
  - 派生决策（待复核）：MIME 三类与 5MB 上限（`MultipartConfigElement` 在 files 包内注册 6MB/8MB 容器上限使业务校验先于容器 500）；object_key=sha256；下载 void 直写响应绕过信封。
  - RED：`./mvnw -q test -Dtest=FileApiTest`，退出码 1，Tests run: 8, Failures: 6，原文 `uploadsJpegAndPersistsMetadataAndBytes:77 Status expected:<200> but was:<404>`、`rejectsGifAndNonImageMimeWithFieldErrors:132 …<400> but was:<404>`。
  - GREEN：同命令退出码 0，8/8（43 处断言）；关键断言：`$.data.objectKey=sha256` 且 `content_length=bytes.length`、落盘字节 `Files.readAllBytes` 与上传完全一致、重复上传 `secondId==firstId 且 COUNT(sha)==1`、下载 `content().bytes(bytes)`+inline 头、MIME 拒绝 `fieldErrors[0].field="file"`、未登录 401、不存在 404。
  - **历史订单仅引用快照**：完整端到端验证依赖订单确认快照机制，由 3.4/3.5 确认快照用例覆盖并交叉引用（本任务交付文件侧能力、鉴权与挂接，`imageFileId` 无效 400 已由 ProductApiTest 覆盖）。
  - 人工证据：不适用；如涉及页面/打印/Electron，回填 `humanVisualConclusion.checklist`，状态为 `pending-user-signoff`（图片展示归 2.10/3.15）。
- [x] 2.5 实现客户 API `GET/POST /api/customers`、`GET/PATCH /api/customers/{id}`，字段覆盖一组默认收货信息；名称/电话重复仅返回候选提示，明确确认后仍创建独立编号，不提供删除/自动合并。
  - 证据：Requirement/Scenario：`master-data-management` Requirement“客户重复只能提示不能自动合并”（Scenario“确认重复客户”）与 Requirement“系统必须管理商品、客户和员工资料”；正式文档：`design.md` 第 6 节（`GET/POST/PATCH /api/customers`）、`database-design.md` 第 4 节（customers 表）；文件：`backend/src/main/java/com/yumi/catalog/customer/{CustomerController,CustomerService,CustomerDtos,CustomerSummary,package-info}.java`、`customer/internal/{CustomerEntity,CustomerRepository}.java`、测试 `CustomerApiTest.java`(10 用例)；API：`POST /api/customers`（有重复候选且未 `duplicateConfirmed` →200 `data={created:false,duplicateCandidates:[…]}` 不入库；无候选或已确认 →201 全量客户）、`GET /api/customers?name=&phone=`、`GET /api/customers/{id}`、`PATCH /api/customers/{id}`（`version` 必带）；无 DELETE、无自动合并；错误码：`VALIDATION_INVALID`(400)、`CONFLICT_VERSION`(409)、`NOT_FOUND`(404)、`AUTH_REQUIRED`(401)、幂等键缺失 400；表：`customers`（C 编号经 `SequenceAllocator.next("customers")`+`format("C",v)`，直接注入，`shared/numbering` 已补 `@NamedInterface` 暴露并删除了反射桥接类）+ 变更日志 `master_data_change_logs` 追加；事务拥有者 `CustomerService.@Transactional`；锁定对象：编号序列行（FOR UPDATE）+ 乐观锁 version；幂等键：写过滤器强制。
  - RED：`YUMI_DB_PASSWORD=<钥匙串读取值> ./mvnw test -Dtest='CustomerApiTest,CustomerSummaryTest'`，退出码 1，Tests run: 12, Failures: 10，原文 `createsCustomerWithCNumberInEnvelope:106 Status expected:<201> but was:<404>`（端点不存在）等。
  - GREEN：同命令退出码 0，12 个测试通过；关键断言：①重复首提 `created=false` 且**库行数不变**；②`duplicateConfirmed=true` 后 201、`secondNo != firstNo`、同名恰两行并存（`containsExactlyInAnyOrder`）；③两次编辑追加两行变更日志 before/after 依次 `原始备注→第一版→第二版`、`admin_username=c-test-admin`、request_id 齐全；④旧 version PATCH→409 `CONFLICT_VERSION`；⑤幂等键缺失→400、未登录→401。集成回归（主仓，桥接替换后）：`-Dtest='CustomerApiTest,CustomerSummaryTest,ModuleStructureTest'` 退出码 0（Modulith verify 含内）。
  - 派生决策（待复核）：重复检测=name 精确 OR phone 精确（空电话不比对，trim 归一）；候选上限 5 按 id 升序；提示留痕由写审计过滤器完成；PATCH 缺省=保持原值、空白 name/default*→400、reason 可选（缺省 NULL）、每次成功 PATCH 必追加日志、创建不写变更日志；列表精确过滤同给为 AND；404 复用 `NOT_FOUND`（码表冻结无 CUSTOMER_NOT_FOUND）。
  - 人工证据：不适用；如涉及页面/打印/Electron，回填 `humanVisualConclusion.checklist`，状态为 `pending-user-signoff`（页面归 2.10/2.12）。
- [x] 2.6 实现客户详情只读汇总查询，金额由订单、收款、退款事实计算，不把汇总余额保存为可编辑客户字段；在订单能力未完成前以契约测试和空汇总完成接口骨架。
  - 证据：Requirement/Scenario：`master-data-management` Requirement“客户重复只能提示不能自动合并”（汇总不得成为可编辑资料的反面约束）与 `order-lifecycle` 收付款事实口径的前置契约；正式文档：`design.md` 第 6 节（客户查询）、`database-design.md` 第 4 节（customers 无余额列）；文件：`catalog/customer/CustomerSummary.java`（`zero()` 契约骨架 + 阶段 3/7 接线注释）、`CustomerController` 详情聚合、测试 `CustomerSummaryTest.java`(2 用例)；API：`GET /api/customers/{id}` data 追加只读 `summary={orderCount:"0", totalOrdered:"0.0000", totalReceived:"0.0000", totalRefunded:"0.0000"}`（全字符串）；表/事务/锁定/幂等键：不适用（纯读聚合，GET 不要求幂等键）；金额未来由订单/收/退款事实计算，当前不落任何客户余额列。
  - RED：同 2.5 首跑（`detailCarriesReadOnlyZeroSummarySkeleton:83 Status expected:<201> but was:<404>`，端点缺失），退出码 1。
  - GREEN：`-Dtest=CustomerSummaryTest` 退出码 0，2 个测试；关键断言：`$.data.summary.totalOrdered="0.0000"` 文本零值、summary 恰四键、详情顶层除 summary 外无任何余额可写字段、未登录 401。
  - 人工证据：不适用；如涉及页面/打印/Electron，回填 `humanVisualConclusion.checklist`，状态为 `pending-user-signoff`（详情页归 2.10/2.12）。
- [x] 2.7 实现员工 API `GET/POST /api/employees`、`GET/PATCH /api/employees/{id}`、`POST .../leave|rehire`，保存首次入职、离职/重入职事件和不可复用 `E00001` 编号。
  - 证据：Requirement/Scenario：`master-data-management` Requirement“系统必须管理商品、客户和员工资料”（编号不可复用）与 Requirement“员工档案与管理员身份必须分离”；正式文档：`design.md` 第 6 节、`database-design.md` 第 4 节；文件：`backend/src/main/java/com/yumi/catalog/employee/{EmployeeController}.java`、`employee/service/{EmployeeService,EmployeeEligibilityService,package-info}.java`、`employee/dto/*`、`employee/internal/{Employee,EmployeeWorkType,EmployeeEmploymentEvent,*Repository,package-info}.java`、测试 `EmployeeApiTest.java`(8 用例)；API：`POST/GET /api/customers` 同族的 `GET/POST /api/employees`、`GET/PATCH /api/employees/{id}`（`version` 必带）、`POST .../leave`（`{reason,date}` 必填）、`POST .../rehire`（`{date}`）；无删除端点；错误码：`VALIDATION_INVALID`、`CONFLICT_VERSION`、`STATE_NOT_EDITABLE`（重复离职/非法状态迁移）、`NOT_FOUND`、`AUTH_REQUIRED`；表：`employees/employee_work_types/employee_employment_events`（事件只追加：HIRE/LEAVE/REHIRE）；编号 `next("employees")`+`format("E",v)` 事务内分配、离职不回收；变更日志 `MasterDataChangeLogService.record("EMPLOYEE",…)` 三类写后追加；事务拥有者 `EmployeeService.@Transactional`；锁定：编号序列行 FOR UPDATE + version 乐观锁；幂等键：写过滤器强制（测试前缀 `emp-%`）。
  - RED：`YUMI_DB_PASSWORD=<钥匙串读取值> ./mvnw -Dtest='EmployeeApiTest,EmployeeEligibilityTest' test`，退出码 1，Tests run: 13, Failures: 11，原文 `createsEmployeeWithEnvelopeHireEventAndWorkTypes:133->create:91 Status expected:<201> but was:<404>` 等。
  - GREEN：同命令退出码 0，13/13 通过；关键断言：`$.data.employeeNo` 匹配 `E\d{5}`、HIRE 事件 `event_date` 落库、workTypes `containsExactlyInAnyOrder`、创建→离职→再创建编号 `n2==n1+1`（不回收）、leave→status=LEFT+LEAVE 事件+变更日志（`admin_username=e-test-admin`、request_id 等值）、重复 leave→409 `STATE_NOT_EDITABLE`、rehire→ACTIVE+REHIRE 事件且 `first_hire_date` 不变、旧 version→409、非法 workType→400 带 fieldErrors。主仓集成回归：`-Dtest='EmployeeApiTest,EmployeeEligibilityTest,ModuleStructureTest'` 退出码 0（Modulith verify 含内）。
  - 派生决策（待复核）：leave 的 reason 必填；PATCH 不声明 status/firstHireDate（携带被忽略）、`workTypes` 整体替换（null 不改/空数组清空/去重防 409）、version 缺失 400 且显式比较不等→409；列表 name 为 LIKE 模糊、按 employee_no 排序；`created_by` 留空（username→账号 id 反查不在本期）；CHAR(6) 映射用 `@JdbcTypeCode(SqlTypes.CHAR)` 过 Hibernate validate。
  - 人工证据：不适用；如涉及页面/打印/Electron，回填 `humanVisualConclusion.checklist`，状态为 `pending-user-signoff`（页面归 2.10/2.12）。
- [x] 2.8 实现员工工作类型“制作/捏毛装袋/缝边剪袋/其他”管理和资格查询；验证离职或缺少对应类型返回 `EMPLOYEE_NOT_ELIGIBLE`，历史计划所需姓名快照不受改名影响。
  - 证据：Requirement/Scenario：`master-data-management` Requirement“员工档案与管理员身份必须分离”（Scenario“员工工作类型不匹配”“管理员作为执行员工”）、Requirement“系统必须管理商品、客户和员工资料”（Scenario“离职员工不能新排班”）；正式文档：`design.md` 第 6 节（`EMPLOYEE_NOT_ELIGIBLE`）、`database-design.md` 第 4 节（employee_work_types 多对多）；文件：`employee/service/EmployeeEligibilityService.java`（`@NamedInterface` 公开应用服务）、`employee/dto/{EligibilityResponse,EmployeeSnapshot}.java`、测试 `EmployeeEligibilityTest.java`(5 用例)；API：`GET /api/employees/{id}/eligibility?workType=` →200 `data={eligible:true,employeeNo,name,workType}` 或 **409 `EMPLOYEE_NOT_ELIGIBLE`**（data=null）或 400 `VALIDATION_INVALID`（field=workType）；工种集合常量 `制作|捏毛装袋|缝边剪袋|其他` 在创建/编辑/资格三处一致；公开服务 `checkEligible(employeeId, workType)` 供阶段五派工复用（不合格抛同一错误码，合格返回 `{employeeNo,name}` 快照）；表：`employee_work_types`（唯一键防重）；事务/锁定/幂等键：查询类 GET 不适用幂等键，资格判断复用服务无独立事务。
  - RED：同 2.7 首跑（13 失败中含资格 5 用例 404），退出码 1。
  - GREEN：`-Dtest=EmployeeEligibilityTest` 退出码 0，5 个测试；关键断言：在职有工种→200 `eligible=true`；在职缺工种→409 `EMPLOYEE_NOT_ELIGIBLE` 且 `$.data` null；离职有工种→409；非法/缺失 workType→400 fieldErrors；未登录→401 `AUTH_REQUIRED`。
  - 历史计划姓名快照：本任务交付当前值快照接口（`EmployeeSnapshot`），改名不影响历史计划的端到端验证由阶段五（5.2 计划姓名快照）覆盖并交叉引用。
  - 人工证据：不适用；如涉及页面/打印/Electron，回填 `humanVisualConclusion.checklist`，状态为 `pending-user-signoff`（页面归 2.10/2.12）。
- [x] 2.9 实现基础资料变更日志，保存修改前后结构化快照、原因、管理员和时间；普通编辑不得覆盖历史，停用/离职使用独立命令。
  - 证据：Requirement/Scenario：`master-data-management` Requirement“系统必须管理商品、客户和员工资料”（停用/离职为独立命令）与 Requirement“订单确认必须冻结基础资料快照”的编辑历史前提；正式文档：`database-design.md` 第 4 节（master_data_change_logs 只追加）、`design.md` 第 5 节（事实不可变）；文件：`backend/src/main/java/com/yumi/catalog/changelog/MasterDataChangeLogService.java`、测试 `MasterDataChangeLogServiceTest.java`(2 用例) + 三域集成断言（ProductApiTest 4 行、CustomerApiTest 2 行、EmployeeApiTest leave/rehire 行）；API：无独立端点，由 `PATCH /api/products|customers|employees/{id}`、`POST .../enable|disable`、`POST .../leave|rehire` 各域服务在同事务内调用（请求级事实已由 `audit_logs` 覆盖，行级前后值由本表承载）；表：`master_data_change_logs`（entity_type/entity_id/business_no/before_json/after_json/reason/admin_username/request_id，仅 INSERT）；事务拥有者：各域写服务 `@Transactional`（失败随业务整体回滚）；锁定/幂等键：不适用（追加行，跟随调用方）。
  - RED：`./mvnw -q -Dtest=MasterDataChangeLogServiceTest test` 首跑退出码 1（实现缺失/编译失败）；三域端点首跑 404 RED 构成调用链起点证据。
  - GREEN：同命令退出码 0，2/2；关键断言：before/after JSON 可解析且 `旧名→新名` 保真、`reason/admin_username/request_id` 齐全；两次编辑恰 2 行追加互不覆盖；集成断言：商品 2×PATCH+disable+enable=4 行仅 `p-test-admin`、客户备注 `原始→第一版→第二版` 两行、员工 leave/rehire 行含 LEAVE 原因；全量 `./mvnw -q test` 退出码 0（22 类 103 测试 0 失败）。
  - 派生决策（待复核）：创建动作不写变更日志（请求级事实由 audit_logs 覆盖）；商品/客户编辑 reason 可选、员工离职 reason 必填；停用/离职/重入职各记独立行。
  - 人工证据：不适用；如涉及页面/打印/Electron，回填 `humanVisualConclusion.checklist`，状态为 `pending-user-signoff`（历史查看页为后续 change 候选）。
- [x] 2.10 实现 `/catalog/products`、`/catalog/customers`、`/catalog/employees` 正式页面，采用紧凑列表/筛选和全页创建编辑流程；员工页分离普通编辑、离职和重新入职操作。
  - 证据：Requirement/Scenario：`master-data-management` Requirement“系统必须管理商品、客户和员工资料”（页面化管理与独立停用/离职命令）；正式文档：`design.md` 第 7 节页面矩阵（`/catalog/products|customers|employees` 及验收观察点）、常规 Ant Design 后管布局口径；文件：`frontend/src/pages/catalog/{ProductsPage,CustomersPage,EmployeesPage}.tsx`、`frontend/src/api/catalog.ts`（类型化目录 API，金额 `Money=string`）、`frontend/src/api/errors.ts`（新增 `describeApiError`：code 动作提示+requestId）、`frontend/src/routes/index.tsx`（三条占位替换为真实页面，`ROUTE_PATHS` 12 条清单不变）；路由：正式 `/catalog/products`、`/catalog/customers`、`/catalog/employees` 与 design §7 矩阵一致（新建/编辑为**路由内显式状态切换**，不新增矩阵外路由）；操作入口：各页紧凑筛选条（`Space.Compact` 按内容宽、不拉伸）+ 主按钮「新建」，列表 `Table size="small"` 自适应撑满、白底 Card + level-4 标题；商品行内「编辑/停用|启用」、客户「编辑」、员工「编辑/离职|重新入职」按状态显式分列；员工离职/重入职为短表单 Modal（日期+原因），创建编辑全页流转不用抽屉；错误处理：全部经 `apiFetch` 封装层，页面只调 `describeApiError`。
  - 浏览器验证（正式路由非预览，内置浏览器结构化 AX 快照逐页核对）：未认证深链 `/catalog/products` → 重定向 `/login`；登录态三页渲染真实数据——商品列表 P00103/P00082/P00081（星级 Tag、4 位金额、状态 Tag、停用行按钮切「启用」）；客户列表 C00063/C00064 同名并存；员工列表工种 Tag 组、筛选选「离职」后查询刷新出 LEFT 行且「重新入职」按钮仅离职行出现；全页表单必填 `*` 标注与分组、商品编辑页成本快照 Descriptions（材料/人工/其他/总成本/参考售价/预计利润/利润率/胶水克重）；重复客户 Alert（候选编号+“系统不会自动合并”+两出口按钮）；离职/重入职 Modal 标题带编号、日期预填。截图因内置浏览器视口隐藏不可得，AX 快照全文在会话记录。
  - 自测发现并修复：商品编辑回显损耗率 `NaN`——前端误读不存在的 `lossRate` 字段，改用后端实际字段 `lossRatePercent`（`ProductsPage.tsx`、`catalog.ts`），修复后回显 `20.000000`；修复后 `npm run typecheck`/`npm test`/`npm run build` 退出码均 0。
  - 派生决策（待复核）：同路由状态切换而非 `/new` 子路由（尊重 design §7 冻结矩阵）；包装档位以 ID 输入框呈现（无档位查询端点，设置页为后续 change 候选）；员工列表默认筛选在职。
  - 人工证据：
    humanVisualConclusion:
      status: confirmed
      checklist:
        - "三页均为白底 Card + 标题 + 表格自适应撑满的常规后管骨架"
        - "筛选条控件按内容紧凑排列、不拉伸填满"
        - "金额 4 位小数字符串、状态/星级为轻量 Tag"
        - "查看与操作分离：行内按钮按状态显示 编辑/停用|启用、编辑/离职|重新入职"
        - "全页创建编辑流程、无抽屉，必填项带 * 标注"
        - "重复客户黄色警示含候选编号与两个明确出口按钮"
        - "/settings 全局设置页（单价默认值/星级条目与包装档位均可增删改（被引用禁删提示），页首声明不回溯既有商品快照"
        - "商品编辑左表单统一三列栅格、字段带格式单位示例；右侧 sticky 利润预估卡（2.17 后由本地实时计算改为服务端试算，标签为「服务端试算 · 保存以服务端快照为准」）"
      confirmedBy: chen
      confirmedOn: 2026-09-24
      conclusion: "本清单视觉项按 2.12「与 2.10 清单合并签字」的约定，随 2.12 于 2026-09-24 一并确认；末项标签已按 2.17 的服务端试算口径对齐。"
  - 用户追加需求修订（2026-09-24：四点表单反馈 → 全量 /settings + 预估 A 案 + 星级定位澄清）：
    1. **design 修订**：§7 路由矩阵新增 `/settings` 全局设置行、§6 API 矩阵新增「全局设置」行（`GET/PATCH /api/settings`、`PUT /api/settings/star-levels`、包装档位 `GET/POST/PATCH/DELETE .../packaging-tiers/{id}`），`openspec validate --strict` 通过；已存记忆 `yumi-v2-product-settings-preview`，星级定位（标准工作量换算：排班均衡、一班多品可加总）已补进商品字段确认记忆。
    2. **后端 settings API**：`catalog/settings/{SettingsController,SettingsService,SettingsViews,package-info(@NamedInterface)}`。`GET /api/settings`→`data{values(7键),starLevels,packagingTiers}`；`PATCH /api/settings`→更新值（键白名单，未知键/非数字/负数→400 `VALIDATION_INVALID` 带 fieldErrors；可选 `reason`；变更日志 `SETTINGS/GLOBAL_SETTINGS` 追加 admin/request_id）；`PUT /api/settings/star-levels`→完整 1-5 星、stdMinutes 1-360 否则 400；包装档位重名→409 `CONFLICT_DUPLICATE`、不存在→404、DELETE→204 无体、创建写 `PACKAGING_TIER` 变更日志；全部继承统一信封/Idempotency-Key/写审计。
    3. **RED**：`YUMI_DB_PASSWORD=<钥匙串读取值> ./mvnw -q -Dtest=SettingsApiTest test` 退出码 1，5/5 失败，原文 `Status expected:<200> but was:<404>` 等。**GREEN**：同命令退出码 0，5/5；关键断言：①GET 返回 7 设置键+5 星级（5/10/15/20/30）+档位数组；②PATCH `glueUnitPrice=0.0123`/`lossRateDefault=15.5`→库值与回显 `0.0123`/`15.500000`、变更日志 admin/request_id 齐；③**星级全局 15→60 后既有商品仍 `starStdMinutes=15/qty6h=24/productLaborFee=5.0000`，且其后一次普通 PATCH（仅 note）快照仍 15（反溯双断言），新建商品按新全局 `std=60/qty6h=6`**；④档位 8.5min+0.3 提成→商品 `packagingLaborFee=2.4250`，删除档位后商品快照（名称/分钟/钱）保留、档位清单消失；⑤401、缺幂等键 400、未知设置键 400、非法星级 400、删除不存在 404 守卫齐。
    4. **连带修复真缺陷（反溯断言暴露）**：`ProductService.patch` 原先每次编辑都按当前全局重读星级/档位——违反「仅重新选择星级时按当前全局」与「档位修改不追溯既有商品」。改为：星级仅在 `request.star≠存量星级` 时读当前对照、否则用商品自身 `starName/starStdMinutes` 快照；档位仅在换成不同 id 时读当前行、否则用存量 `packagingTierName/StdMinutes/Commission` 快照。修复后 `SettingsApiTest,ProductPricingTest,ProductApiTest` 退出码 0；**后端全量 24 类 111 测试 0 失败**。
    5. **前端**：新增 `src/api/settings.ts`、`src/pages/settings/SettingsPage.tsx`（「单价与默认值」三列表单 7 键、星级→标准时长可编辑表+整表保存、包装档位增/改/删含 Popconfirm；页首注明全局变更不回溯既有商品快照；星级区注明标准工作量口径：数量×标准时长=可加总标准分钟，用于 8/6 小时标准数量、人工费与排班均衡）；`/settings` 进正式路由与侧栏「设置」菜单；`ROUTE_PATHS` 扩至 13 条（`paths.test.ts` 同步断言）。**商品编辑器改版**：左 16 栏统一三列栅格（每字段带格式+单位+示例 placeholder：`如：泰迪熊 30cm`/`单位 g，如 270`/`如 20.5（新建默认取全局，可改）` 等；星级下拉显示 `三星 · 15分钟`、包装档位改从全局档位选择而非手填 ID、新建按全局默认预填损耗率与四项费用、说明/原因整行），右 8 栏 sticky「利润预估」卡——decimal.js 实时计算（胶水克重/胶水色浆成本/材料、制品+包装+装箱人工/人工、其他、单件总成本、参考售价÷0.7、预计利润/利润率、8h×N·6h×N 标准数量、缝边成本单列不入总），标注「实时预估 · 保存以服务端快照为准」，新建与编辑同现。依赖新增 `decimal.js`。`npm run typecheck`/`npm test`（4 文件 13+ 用例）/`npm run build` 全部退出码 0；settings API 线上冒烟：信封齐、`lossRateDefault=15.500000`、星级 5 条、未认证 401。
    6. 后端重启使浏览器旧会话失效，`/settings` 与新版商品表单的点击级复核并入 2.10/2.12 签字一并执行（凭据交接见会话）。
    7. **星级定稿修订（2026-09-24 第二轮讨论）**：星级=用户自建静态条目（名称+标准时长，如 用户建“一星=5分钟”→ 8h 产量 96、6h 全速 72、8h 工资 120÷72=1.6667 元/件——公式与实现一致；混合排班=Σ(数量×星级时长) 标准分钟做均衡，产能基准与强校验留阶段五讨论）。V5 迁移 `star_levels` 去掉 `star` 列改按 id 引用（名称唯一）、`products.star`→`star_level_id`；星级接口由“整表 PUT”改为 CRUD `POST/PATCH/DELETE /api/settings/star-levels/{id}`；**删除策略=被商品引用禁止删除**（新增错误码 `CONFLICT_REFERENCED` 409，前后端码表/动作映射同步，design §6 行更新）；包装档位删除同样加引用守卫、档位全局修改不回溯既有商品快照（断言 packagingStdMinutes 仍 8.500/2.4250）。商品 API 契约字段 `star`→`starLevelId`（详情/列表另含 `starName`），前端表单、预估、列表与全部相关测试同步。证据：`SettingsApiTest` 6 用例（新增 `starLevelCrudAndReferencedGuard`：建/重名409/改/未引用删204/被引用删409 且仍在清单）+ `CatalogMigrationTest` 断言 V5、`star_levels` 无 `star` 列、`products.star_level_id` 存在、`uk_star_levels_name`；`starViewById` 空行转 404（修复一处 500）。**后端全量 24 类 112 测试 0 失败；前端 typecheck/test/build 全 0（设置页星级区改为增删改列表+Popconfirm 引用提示）**。`openspec validate --strict` 通过。
- [x] 2.11 编写 HTTP、Repository 和 MySQL 集成测试，覆盖唯一编号并发、四位精度、重复提示、停用商品、员工资格、变更审计和文件鉴权。
  - 证据：Requirement/Scenario：`platform-foundation` Requirement“系统必须管理商品、客户和员工资料”（并发编号唯一）与 Requirement“基础资料金额和成本必须保持精度”；正式文档：`database-design.md` 第 2、12、14 节、`design.md` 第 9 节；文件：`backend/src/test/java/com/yumi/Stage2IntegrationTest.java`(3 用例)；API/表：跨 `/api/products|customers|employees|files` 四组端点与 `products/customers/employees/master_data_change_logs/employee_employment_events/file_metadata/number_sequences`；事务/锁定：并发用例 4 线程各开 `TransactionTemplate` 独立事务对序列行 FOR UPDATE；幂等键：各写请求唯一键（测试内 UUID 生成）。
  - RED/GREEN 口径（如实记录）：本任务为阶段末**回归固化型**集成测试，所断言行为已由各域任务实现前的 404 RED 证明（ProductApiTest/CustomerApiTest/EmployeeApiTest/FileApiTest 首跑 `Status expected:<201> but was:<404>` 等），故本用例首跑即 GREEN；行为级 RED 证据以各域任务条目为准（依证据模板“回归并入批次边界”）。
  - GREEN：`YUMI_DB_PASSWORD=<钥匙串读取值> ./mvnw -q -Dtest=Stage2IntegrationTest test`，退出码 0，3/3；关键断言：①4 线程共分配 20 个 P 序号 `HashSet` 恰 20 个（**编号并发无重复**）；②`salePrice="19.9999"` 库列 `numeric_scale=4`、回读 `$.data.salePrice="19.9999"` 无浮点尾数（**四位精度**）；③跨域流程——商品 disable→`DISABLED`→**停用后 PATCH 200**；同名客户首建 201→再提 `created=false` 候选含原编号→确认 201 独立新号（**重复提示**）；员工离职后资格 409 `EMPLOYEE_NOT_ELIGIBLE`（**员工资格**）；`GET /api/files/999999` 未登录 401 / 登录后 404（**文件鉴权**）；**变更审计**由 ProductApiTest 4 行、CustomerApiTest 2 行、MasterDataChangeLogServiceTest 追加不覆盖交叉覆盖。当日全量回归：`./mvnw -q test` 退出码 0，23 类 106 测试 0 失败。
  - 人工证据：不适用；如涉及页面/打印/Electron，回填 `humanVisualConclusion.checklist`，状态为 `pending-user-signoff`。
- [x] 2.12 阶段人工验收：在正式路由创建/修改/停用商品，创建重复客户并确认保存，办理员工离职/重入职；记录 `humanVisualConclusion.checklist`，用户确认前本项保持未勾选。
  - 证据（2026-09-24 执行代理在内置浏览器以真实会话**完整自测**，操作者=代理、凭据为本机合成测试账号；用户已于 2026-09-24 签字确认）：Requirement/Scenario：`master-data-management` Scenario“创建有效商品/停用商品/确认重复客户”；正式文档：`design.md` 第 7 节验收观察点。流程实据：
    1. **商品**：正式路由新建「验收商品-A」→ `P00103`（4 星、salePrice 30.0000、totalCost 6.6667 计价实时生效）；编辑保存 → `master_data_change_logs` 行1（reason=阶段二验收修改、admin、request_id 齐）；行内停用 → Tag 变「停用」、按钮切「启用」、提示「商品已停用」、日志行2；**停用后再编辑**成功（备注=验收编辑-停用后修改、日志行3 reason=停用后仍可编辑验收），库内保持 `DISABLED`。
    2. **客户**：新建「重复客户-验收」→ `C00063`；同名同电话再建 → 黄色警示列出候选 C00063 且**不入库**、表单值保留；「确认是不同客户，继续创建」→ `C00064` 与 C00063 并存（列表快照两行同名并列+「客户已创建」提示）。
    3. **员工**：新建「验收员工-甲」→ `E00057`（HIRE 事件、工种落库、first_hire=2026-09-01）；行内离职 Modal 填原因+日期 → LEFT、LEAVE 事件、变更日志；筛「离职」→ 行内重新入职 → ACTIVE、**first_hire_date 仍 2026-09-01**、REHIRE 事件、变更日志恰 2 行。
    4. 三页结构化 AX 快照全文在会话记录；截图受内置浏览器视口隐藏限制未产出，视觉项并入下方清单待用户签字。
  - humanVisualConclusion:
    status: confirmed
    checklist:
      - "正式 /catalog/products 创建→编辑→停用→停用后再编辑 全链路可用，状态 Tag 与行内按钮随状态切换"
      - "重复客户提示不入库、确认后独立编号并存，绝不合并"
      - "员工离职/重入职操作分离，首入日期不变、事件与变更日志齐全"
      - "白底卡片、紧凑筛选、表格自适应、金额 4 位小数字符串（与 2.10 清单合并签字）"
    confirmedBy: chen
    confirmedOn: 2026-09-24
    conclusion: "用户于 2026-09-24 确认 2.12 阶段人工验收清单（含与 2.10 合并的视觉项）；流程实据与结构化快照见上方证据。"
  - **商品页视觉项作废与重签（2026-09-24，任务 2.27）**：本清单中涉及商品页的项（白底卡片/紧凑筛选/表格自适应/金额 4 位小数字符串）因 2.21–2.24 的商品字段变化（缝边字段调整、新增默认缝边剪袋类型与缝边价格、面板两套预算）**原签字作废**，已合并进 2.24 的 `humanVisualConclusion`，并已于 2026-09-24 经用户确认完成重签（`confirmedBy: chen`）；其余非商品页项（客户页、员工页流程实据）继续有效。

### 阶段二追加：公式真正集中管理（2026-09-24）

本组依据 `docs/architecture/formula-management-design.md` 与 `design.md` §5.1/§6，追踪 `master-data-management` 的“业务公式必须集中且只有一份实现”“商品试算与保存必须复用统一计算入口”“商品预估必须防止过期响应覆盖”“系统必须提供只读公式说明”。既有 1.2、2.3、2.10 的勾选与证据保留为历史，不代表本组完成；其中六业务模块清单需新增 calculation 支撑模块，本地 decimal.js A 案由服务端试算取代。2.12 未完成的人工签字保持未完成，新预估验收由 2.19 补充，公式说明验收由 2.20 补充，不以旧证据代替。

- [x] 2.13 固定公式迁移清单与基准算例（依赖 2.2、2.3、2.10）：盘点实施时真实存在的商品及其他业务公式、输入解析和全部调用点，建立集中公式目录，记录标识/单位/表达式/舍入节点/来源/测试编号；为 ProductPricing 补人工核算的字符串算例，覆盖零售价、无包装、损耗取整、高精度材料价、小数时长、非整除标准产量和舍入敏感值。发现规格冲突先确认，不擅自改公式。
  - 证据：Requirement/Scenario：`master-data-management` Requirement“业务公式必须集中且只有一份实现”的 Scenario“迁移商品公式不改变计算口径”；正式文档：新增 `docs/architecture/formula-catalog.md`（第 2 节盘点、第 3 节目录 FP-PROD-01..19、第 4 节代码位置与测试编号、第 7 节盘点偏差），依据 `docs/architecture/formula-management-design.md` 第 2、5、9 节；文件：新增 `backend/src/test/java/com/yumi/ProductPricingBaselineTest.java`（8 用例）与 `docs/architecture/formula-catalog.md`，**未改动任何生产代码**；API/表/路由/迁移：不适用（本任务不新增端点、表或路由）；事务/锁定/幂等键：不适用（纯函数基线，无持久化）。
  - 盘点结论（逐项语义核对，非关键词扫描）：需迁移 = `ProductPricing`（FP-PROD-01..17、19）与 `ProductService.java:368` 损耗率回显（FP-PROD-18）；保留在 catalog 的边界换算 = `CatalogReference.java:42`（读库转 scale4）、`SettingsService.java:96,336,341`（设置写入规范化与序列化，无算术链）；待删除的公式副本 = `frontend/src/pages/catalog/ProductsPage.tsx:30-128`（`computePreview` + `m4`/`m6`，任务 2.17）；`V1`–`V5` 迁移仅建表/约束/静态数据，无计算列或计算表达式，不迁移。业务约束（校验、引用选择、快照合并、事务、锁、幂等）判定为非公式，仍留业务模块。
  - RED/GREEN 口径（如实记录）：本任务为**基线固化型**，按全局完成定义第 2 条记录——`./mvnw -q -Dtest=ProductPricingBaselineTest test` 首跑退出码 0，8/8（surefire `tests="8" errors="0" skipped="0" failures="0"`）；所断言行为已由 2.3 的 `ProductPricingTest` 首跑 RED 证明，故不伪造 RED。清理重复断言辅助方法后复跑同命令，退出码 0，8/8。
  - 关键断言（全部人工核算的字符串期望值）：损耗率 `33.3333→0.333333`、`20.0005→0.200005`、`0.00005→0.000001`（scale6 HALF_UP 进位）；胶水克重 105×1.5=157.5→`158`、105×1.499999=157.499895→`157`；std=7（非整除）→ qty8=68、qty6=51、人工 `2.3529`、参考价 `3.3613`、利润率 `0.764710`；高精度单价 100×0.0012345=0.12345→`0.1235`（区别于 HALF_EVEN 的 0.1234）、色浆 `0.0346`、材料 `0.1581`、参考价 `0.2259`；小数时长缝边 2.5min→`0.6250`/参考 `0.8929`、档位 8.5min+0.3→`2.4250`，且缝边成本不入总成本（total `2.9250`=包装 2.4250+装箱 0.5）；其他成本四项和 `1.0000`、参考价 `1.4286`、利润率 `0.666667`；零费用零标准时长 total `0.0000`、利润率 `1.000000`（除零防护）；零成交价利润 `-1.0000`、利润率 `0.000000`。
  - 盘点发现（未擅自改公式，留待 2.14/2.17 处理，见 catalog 第 7 节）：①`SettingsService.java:96` 用无 `RoundingMode` 的 `setScale(4)`，六位小数单价输入（列定义 `DECIMAL(19,6)`）抛 `ArithmeticException` 并被兜底分支转成 500，与设计第 5 节“金额 4 位 HALF_UP”冲突；②前端只在展示时舍入、后端每个公式节点舍入，中间值超过四位小数时分项/合计可能与后端相差 0.0001（档位 `std_minutes` 列 `DECIMAL(9,3)`，取 8.333 可达）。
  - 人工证据：不适用（无页面/打印/Electron 变更）。
- [x] 2.14 建立 calculation 模块并迁移唯一计算实现（依赖 2.13）：迁移 `catalog/product/internal/ProductPricing` 至 `calculation/product`，集中精度策略、业务常量和强类型输入/结果；更新所有调用点并移除旧实现，增加 Modulith 公开命名接口及边界测试，禁止反向依赖业务模块、Repository、实体或外部可变状态。不创建工资/财务空实现。
  - 证据：Requirement/Scenario：`master-data-management` Requirement“业务公式必须集中且只有一份实现”的 Scenario“迁移商品公式不改变计算口径”（基准逐项不变、旧实现移除、模块边界单向无循环）；正式文档：`docs/architecture/formula-catalog.md` 第 3、4 节（代码位置列已按 `calculation` 路径登记）、`docs/architecture/formula-management-design.md` 第 3 节目标结构与依赖方向。
  - 文件：新增 `backend/src/main/java/com/yumi/calculation/package-info.java`（`@ApplicationModule`，无持久化）、`calculation/DecimalPolicy.java`（FP-PROD-01/18/19：`money` scale4、`ratio` scale6、`percentToRatio`、`ratioToPercent`、`wholeNumber`）、`calculation/product/package-info.java`（`@NamedInterface`）、`calculation/product/ProductPricing.java`（整体迁入，常量与强类型 Inputs/Result 不变）；**删除** `catalog/product/internal/ProductPricing.java`；调用点改指集中模块——`ProductService`（15 处金额归一、2 处损耗率换算、回显改 `DecimalPolicy.ratioToPercent`，并删除本地 `HUNDRED` 常量）、`CatalogReference.moneySetting`、`SettingsService`（写入归一与输出序列化 3 处）。API/表/路由/迁移/幂等键：不适用（纯结构迁移，无新端点、无新表、无 Flyway 版本）。
  - 架构守卫 RED/GREEN：`./mvnw -q -Dtest=ModuleStructureTest test` 首跑退出码非 0，3 用例 1 failure 1 error——`declaresTopLevelBusinessModulesPlusSharedAndCalculation` 失败（模块清单缺 `calculation`）、`calculationModuleHasNoOutgoingDependencies` 抛 `NoSuchElement: No value present`；实现后同命令退出码 0，3/3。关键断言：模块清单恰含 `identity/catalog/orders/inventory/production/files/shared/calculation`；`calculation.getDependencies(modules).uniqueModules()` 为空 → 计算模块无任何出边（无循环、不依赖业务模块/Repository/实体）；`verify()` 全量边界校验通过。
  - 计算回归 GREEN（迁移前后同基准）：`./mvnw -q -Dtest=ProductPricingBaselineTest test` 迁移前 8/8、迁移后 8/8，逐字段字符串期望值完全未变；`ProductPricingTest`（HTTP 端到端钉死算例）7/7 不变。证明“基准逐字段不变”。
  - 顺带修复的真缺陷（先 RED 后 GREEN，属 2.13 盘点发现①）：`SettingsApiTest#overPreciseSettingValuesRoundHalfUpInsteadOfFailing` 首跑退出码非 0，`Status expected:<200> but was:<500>`，日志原文 `java.lang.ArithmeticException: Rounding necessary … at com.yumi.catalog.settings.SettingsService.patchValues(SettingsService.java:96)`；改用 `DecimalPolicy` 归一后同命令退出码 0，7/7，断言 `glueUnitPrice "0.01245"→"0.0125"`、`lossRateDefault "15.5000005"→"15.500001"`（HALF_UP，区别于 HALF_EVEN 的 `0.0124`/`15.500000`）。
  - 全量门禁（本任务边界）：`YUMI_DB_PASSWORD=<钥匙串 yumi-v2-local-test> ./mvnw -q test` 退出码 0，**25 类 122 测试 0 失败 0 错误**（迁移前 24 类 112 测试；新增 ProductPricingBaselineTest 8、SettingsApiTest +1、ModuleStructureTest +1）。
  - 人工证据：不适用（无页面/打印/Electron 变更）。
- [x] 2.15 统一商品计算输入解析（依赖 2.14）：让新建/更新使用同一解析能力，分离数据库引用选择与纯计算；保持不同星级/档位才刷新、同一引用保留快照、材料价格明确刷新及 PATCH 缺省合并语义；服务端事务内重算，不信任客户端派生金额。
  - 证据：Requirement/Scenario：`master-data-management` Requirement“商品试算与保存必须复用统一计算入口”的 Scenario“编辑试算保留引用快照”与“保存不信任预估结果”（本任务先统一解析能力，试算端点由 2.16 接入）；正式文档：`docs/architecture/formula-management-design.md` 第 6.2 节（输入解析复用）与第 6.3 节（保存时再次计算）、`docs/architecture/formula-catalog.md` 第 2 节（输入解析判定为非公式）。
  - 文件：新增 `backend/src/main/java/com/yumi/catalog/product/internal/ProductInputResolver.java`（`forCreate`/`forUpdate` 两个入口 + `Resolved(inputs, starLevelId, starName, packagingTierId, packagingTierName, seamDefaultFee)`，只做引用选择、快照规则与 PATCH 合并，不含公式）；`ProductService` 改为委托解析器，删除原先内联的校验/引用选择/字段合并约 60 行与 `invalid`/`requiredNonNegative`/`optionalNonNegative` 三个私有方法，create/update 只保留名称与图片等非计算资料校验、事务、编号分配、快照持久化与变更日志。API/表/路由/迁移/幂等键：不适用（不新增端点、表或路由；事务拥有者仍为 `ProductService.create/update` 的 `@Transactional`，并发仍走 `@Version` 乐观锁 + `CONFLICT_VERSION`）。
  - 新增用例（先写后重构，重构前后均绿）：`backend/src/test/java/com/yumi/ProductSnapshotMatrixTest.java` 7 用例——①同一星级引用保留商品快照（全局三星 15→60 后 PATCH 显式传相同 `starLevelId`，仍 `starStdMinutes=15`/`qty6h=24`/`5.0000`/`totalCost=16.2200`）；②换成不同星级取当前全局（一星改 12 分钟 → `qty8h=40`/`qty6h=30`/`4.0000`）；③同一档位引用保留快照（全局档位改 20 分钟/0.5 后 PATCH 同一 `packagingTierId`，仍 `packagingStdMinutes=10.000`/`2.7000`/`18.9200`）；④换成不同档位取当前值（`5.5000`/`21.7200`）；⑤全局变更不回溯其他商品（星级时长与胶水单价快照逐项不变）；⑥过期版本 `409 CONFLICT_VERSION` 且商品值、版本号与 `master_data_change_logs` 行数零变化；⑦非法计算字段聚合 `fieldErrors`（`salePrice`/`weightG`/`starLevelId` 三项齐）。
  - RED/GREEN 口径（如实记录）：本任务为**特征化 + 重构**，矩阵用例在重构前首跑即 7/7 通过（行为已由 2.2/2.3 及 2.10 修订实现，按全局完成定义第 2 条不伪造 RED）；抽取解析器后同命令复跑退出码 0，7/7，证明语义未变。
  - 全量门禁（本任务边界）：`YUMI_DB_PASSWORD=<钥匙串 yumi-v2-local-test> ./mvnw -q test` 退出码 0，**26 类 129 测试 0 失败 0 错误**。
  - 人工证据：不适用（无页面/打印/Electron 变更）。
  - **用户追加需求修订（2026-09-24：编辑页“全局已变”提示 + 一键按最新设置重算）**：新增 `UpdateProductRequest.refreshGlobalReferences`（`Boolean`，可选）；`ProductInputResolver.forUpdate` 在 `starChanged || refreshGlobals` 时读当前全局星级（全局条目已删除则保留快照，不因全局删除阻断保存），档位同理（换成不同档位或显式刷新时读当前档位，否则保留快照），材料单价 `refresh = refreshGlobalReferences || refreshMaterialPrices`。试算与保存共用该解析入口，试算仍只读。依据：`design.md` §5.1、`master-data-management` 新增 Scenario“管理员显式采纳最新全局设置”、`formula-management-design.md` §6.1/§6.2。
  - RED/GREEN：`ProductSnapshotMatrixTest#explicitRefreshAdoptsCurrentGlobalStarTierAndMaterialPrices` 首跑退出码非 0，`expected: 60 but was: 15`（标志位未实现，星级仍用快照）；实现后同命令退出码 0，**8/8**。新增 `ProductPreviewApiTest#editPreviewHonorsExplicitGlobalRefreshWithoutWriting`：不刷新时沿用快照（`5.0000`/`9.7200`/`16.2200`），刷新后按当前全局（qty8=8、qty6=6、人工 `20.0000`、材料 `12.9600`、总成本 `34.4600`），且五项业务计数不变；该类 **8/8**。
  - 连带修复：两个测试类的 `@BeforeEach` 补回星级基线重置（`5/10/15/20/30`），避免外部会话改动全局值污染用例——首次运行即因此暴露过 `三星=60` 导致的失败。
  - 本组全量门禁（追加修订后）：后端 `./mvnw -q test` 退出码 0，**27 类 138 测试 0 失败 0 错误**；前端 `npm run typecheck`/`npm test`（6 文件 34 用例）/`npm run build` 退出码均 0；`openspec validate build-yumi-v2-order-fulfillment --strict` 输出 valid。
- [x] 2.16 实现商品只读试算 API（依赖 2.15）：新增 `POST /api/products/preview`、`POST /api/products/{id}/preview`，共用输入解析与集中公式，返回统一信封及完整分项和百分比展示文本；非计算资料不阻止试算，编辑需版本号。仅对这两个精确方法/路径豁免幂等存储与业务写审计，保留认证、安全日志、requestId，其他写命令仍强制幂等及审计。
  - 证据：Requirement/Scenario：`master-data-management` Requirement“商品试算与保存必须复用统一计算入口”的四个 Scenario（新建试算不产生业务写入、编辑试算保留引用快照、试算拒绝无效请求、保存不信任预估结果）；正式文档：`design.md` §5.1 与 §6 的“商品试算”行、`docs/architecture/formula-management-design.md` 第 6.1 节、`docs/architecture/formula-catalog.md` 第 3 节（FP-PROD-01..19 由试算回传）。
  - 文件与端点：新增 `backend/src/main/java/com/yumi/catalog/product/ProductPreview.java`（金额/比例 `@JsonSerialize(ToStringSerializer)`，含 `estimatedMarginRatePercent` 展示文本）与 `backend/src/main/java/com/yumi/shared/web/WritePolicy.java`；`ProductService` 新增 `previewCreate`/`previewUpdate`/`toPreview`/`percentText`（`@Transactional(readOnly = true)`，复用 `ProductInputResolver` 与 `ProductPricing`）；`ProductController` 新增 `POST /api/products/preview` 与 `POST /api/products/{id}/preview`；`IdempotencyFilter` 与 `WriteAuditFilter` 的 `shouldNotFilter` 接入 `WritePolicy.isReadOnlyPreview`。豁免判定为**精确方法 + 精确路径**：`POST` 且 URI 等于 `/api/products/preview` 或匹配 `^/api/products/\d+/preview$`，不使用 `/preview` 后缀通配。
  - 契约：成功 200 统一信封 `{code:"OK",message:"",fieldErrors:[],requestId,data}`，`data` 含胶水克重/胶水成本/色浆成本/材料成本/星级人工/包装人工/装箱人工/人工成本/缝边成本/缝边参考收费/其他成本/总成本/参考售价/8h·6h 标准数量/成交价/利润/利润率/利润率展示文本；错误码 401 `AUTH_REQUIRED`、400 `VALIDATION_INVALID`+fieldErrors、404 `NOT_FOUND`、409 `CONFLICT_VERSION`。表/Flyway/幂等键：不新增表与迁移版本；试算不写 `idempotency_records`；事务拥有者为 `ProductService.preview*` 只读事务；锁定不适用（只读，不修改任何行）。
  - RED/GREEN：`YUMI_DB_PASSWORD=<钥匙串> ./mvnw -q -Dtest=ProductPreviewApiTest test` 首跑退出码非 0，7 用例 6 failures（端点未实现时写过滤器先返回 400 `Idempotency-Key` 错误，原文 `Status expected:<200> but was:<400>`、`Status expected:<404> but was:<400>`）；实现后同命令退出码 0，**7/7**。
  - 关键断言：①钉死算例完整分项——胶水 `324`/`3.2400`、色浆 `6.4800`、材料 `9.7200`、星级人工 `5.0000`、包装 `0.0000`、装箱 `0.5000`、人工 `5.5000`、缝边 `1.2500`/参考 `1.7857`、其他 `1.0000`、总成本 `16.2200`、参考售价 `23.1714`、`qty8h=32`/`qty6h=24`、利润 `8.7800`、利润率 `0.351200`、展示文本 `35.12%`；②**只读零写入**——试算前后 `products`、`number_sequences.current_value`、`master_data_change_logs`、`audit_logs`、`idempotency_records` 五项计数与序列值全部不变；③编辑试算缺省合并（只改 `weightG=300` → 360 克/`17.3000`/`30.8%`），缺版本 400、过期版本 409；④未认证 401、商品不存在 404；⑤**豁免精确性**——`/api/products/preview` 无幂等键 200，而 `/api/products/preview/`、`/api/products/previewx`、`POST /api/products/{id}/disable` 仍 400 要求幂等键；⑥试算与保存快照 15 个字段逐项一致，配置改变后显式刷新保存得 `19.4600` 而试算仍 `16.2200`（保存不采信预估）；⑦客户端伪造 `totalCost`/`estimatedProfit`/`referencePrice`/`glueGrams` 被忽略。
  - 全量门禁（本任务边界）：`YUMI_DB_PASSWORD=<钥匙串 yumi-v2-local-test> ./mvnw -q test` 退出码 0，**27 类 136 测试 0 失败 0 错误**。
  - 人工证据：不适用（前端接入见 2.17，浏览器/Electron 交互证据见 2.19）。
- [x] 2.17 前端切换服务端预估（依赖 2.16）：在 `frontend/src/api/catalog.ts` 增加试算封装，`ProductsPage.tsx` 删除 computePreview 与其 Decimal 全局配置；保持布局，计算字段有效后约 300ms 防抖，取消旧请求并用请求标识拒绝过期响应。显示待填写/计算中/失败重试，断网不本地计算；保存响应覆盖预估且取消在途请求，配置导致金额变化时提示。依赖移除需另获授权。
  - 证据：Requirement/Scenario：`master-data-management` Requirement“商品预估必须防止过期响应覆盖”的两个 Scenario（快速输入和响应乱序、试算失败或保存时仍有请求在途）；正式文档：`design.md` §5.1 与 §7 的 `/catalog/products` 行（“预估与保存同一后端公式；旧响应不覆盖新输入，断网不本地计算”）、`docs/architecture/formula-management-design.md` 第 7 节前端交互。
  - 文件：`frontend/src/api/catalog.ts` 新增 `ProductPreviewInput`/`ProductPreviewResult` 类型与 `previewNewProduct`/`previewProductUpdate`（带 `AbortSignal`，复用统一信封解包层）；新增 `frontend/src/pages/catalog/previewScheduler.ts`（约 300ms 防抖、递增序号拒绝过期响应、`AbortController` 取消在途、计算输入未变不重复请求、`showSaved`/`retry`/`cancel`）；新增 `frontend/src/pages/catalog/previewInput.ts`（`collectPreviewInput` 收集并校验计算字段、`previewFromDetail` 把保存响应映射为面板数据、`formatRatioPercent` 纯字符串位移生成展示文本）；`frontend/src/pages/catalog/ProductsPage.tsx` **删除** `computePreview`、`m4`/`m6`、`PreviewState` 与 `Decimal.set` 共 93 行本地公式副本，改为调度器驱动，面板按状态渲染（待填写 / 计算中 / 试算失败+重试 / 已按服务端试算），非 ready 状态所有数值显示 `—`（不伪造零值、不把旧值冒充当前结果），保存成功后 `showSaved` 覆盖并取消在途，`saved.totalCost` 与预估不一致时提示“配置已变化”。路由：正式 `/catalog/products`，未新增页面路由（`ROUTE_PATHS` 仍 13 条）。
  - 依赖：`decimal.js` 已无任何源码引用，经用户授权后已于 2026-09-24 执行 `npm uninstall decimal.js`（`package.json` 与 `package-lock.json` 均无残留）；移除后 `npm run typecheck`/`npm test`/`npm run build` 仍全部退出码 0，构建产物 hash 与体积不变（`index-CQuvJ-iH.js` 1,295.95 kB），说明该依赖此前已不在依赖图内。
  - 测试与 RED/GREEN（如实记录）：新增 `previewScheduler.test.ts`（7 用例）与 `previewInput.test.ts`（7 用例），`npm test` 退出码 0，**6 文件 34 用例全通过**。本任务先写实现后补用例，首跑即 GREEN，不伪造 RED；为证明用例有效，做了**变异检查**：删除“计算输入未变则跳过”与“序号过期则丢弃”两处守卫后复跑，`previewScheduler.test.ts` 立即 3 failed（不重复请求、响应乱序、保存覆盖三个用例捕获），恢复实现后复跑 7/7，证明用例确实约束行为而非空跑。
  - 关键断言：防抖——300ms 内连续 3 次输入只发 1 次请求且用最后一次输入；无关字段——相同计算输入重复提交不产生第二次请求；字段未齐——`request(null)` 不发请求并显示待填写；响应乱序——旧请求 `AbortSignal.aborted=true`、迟到响应不覆盖、输入变化瞬间旧结果即置空并显示计算中；失败重试——失败显示可重试且 `retry()` 复用上次输入；保存竞态——`showSaved` 覆盖预估并使在途请求失效；取消——取消后不再发请求且被取消的失败不显示为失败；金额无损——`'25.0000'` 原样传递、展示文本 `0.351200→35.12%`、`1.000000→100%`、`-0.152200→-15.22%` 均为字符串位移（无浮点运算）。
  - 浏览器验证（正式路由，内置浏览器结构化快照）：启动本机后端（18080，8080/8081 被其他项目占用）与前端 dev server（5174，`VITE_API_TARGET` 指向该后端），未认证深链 `http://127.0.0.1:5174/catalog/products` 正确重定向到 `/login` 并渲染登录表单，说明改造后的商品页模块在正式路由下可加载；经 Vite 代理请求 `POST /api/products/preview` 返回统一信封 `401 AUTH_REQUIRED`，证明代理与试算端点链路连通。**登录后的交互与视觉验收按 2.19 与 2.12 合并由用户确认**，本任务不代为签字。
  - 门禁：`npm run typecheck` 退出码 0；`npm test` 退出码 0（34 用例）；`npm run build` 退出码 0（仅有既存的 chunk 体积提示）。
  - 人工证据：视觉清单与用户确认在 2.19 汇总（`pending-user-signoff`）。
- [x] 2.18 集中计算回归与文档门禁（依赖 2.14–2.17）：运行后端全量/Modulith/本机 MySQL 集成、前端 typecheck/test/build 与 OpenSpec strict；对 2.13 清单逐项复核无遗留副本。同步 `docs/architecture/system-architecture.md`、架构源图、公式目录和现行接口契约，明确 calculation 无表、不改变已存快照；保留既有 Testcontainers 豁免，不扩大部署范围。
  - 证据：Requirement/Scenario：`master-data-management` 三个公式 Requirement（业务公式集中且只有一份实现、试算与保存复用统一入口、预估防止过期响应覆盖）与 `platform-foundation` Requirement“系统必须提供统一响应契约”；正式文档：`docs/architecture/system-architecture.md` §4.7/§5/§6、`docs/architecture/database-design.md` §3、`docs/architecture/formula-catalog.md`、`docs/architecture/formula-management-design.md` 状态行、`specs/platform-foundation/spec.md`。
  - 公式副本复核（对 2.13 清单逐项，命令与结果）：①前端 `grep -rn "computePreview|new Decimal|ROUND_HALF_UP|Math.floor(480|Math.floor(360" frontend/src` → **无结果**；②后端 `find backend/src -name ProductPricing.java` → 仅 `calculation/product/ProductPricing.java`，`catalog/product/internal/ProductPricing.java` 已不存在；③业务模块内 `multiply|divide|setScale` 仅剩 `catalog/product/internal/ProductInputResolver.java`，且已在公式目录第 2 节登记为边界规范化（缝边时长 scale3、金额入参按 `DecimalPolicy` 归一，工时精度按设计第 5 节不并入金额策略）；④`grep -rn "GENERATED|ROUND(|AS (" src/main/resources/db/migration` → 无计算列或计算表达式；⑤`ModuleStructureTest#calculationModuleHasNoOutgoingDependencies` 断言 calculation 无出边通过。
  - 文档同步（逐项改动）：①`system-architecture.md` 新增 §4.7“集中计算 `calculation`（无持久化支撑模块）”，§5 包树加入 `calculation` 及其分类（order/inventory/production 随业务接入、payroll/finance 业务确认后接入）并写明边界测试要求 calculation 无出边，§6 依赖图新增“业务模块 → calculation 单向调用”与“calculation 无出边”；②`database-design.md` §3 模块与表归属新增 `calculation` = **无表**；③`formula-catalog.md` 第 2 节补登 `ProductInputResolver` 边界归一；④`formula-management-design.md` 状态由“待评审”改为“已评审并实施（2026-09-24）”并指向 tasks.md 与公式目录，第 2 节迁移前快照保留为历史；⑤`specs/platform-foundation/spec.md` 原“系统必须提供统一错误契约”改为“系统必须提供统一响应契约”，覆盖成功+失败统一信封与 `X-Request-Id`，新增 Scenario“成功与失败响应形状一致”（修补 2.13 盘点时发现的规格缺口：原文只写错误响应字段，未跟上已实现的统一信封）；⑥架构源图 `docs/architecture/system-architecture.dsl` 为 C4 容器级模型、不含模块清单，**不适用**，无需改动。
  - 未改动项与理由：`proposal.md` 中“本次不新增应用代码”等表述在阶段一/二实现后已不准确，但属该提案“What Changes/Impact”的历史范围声明，不在本任务列明的同步清单内，且改动提案范围措辞需另行确认，故保持原样并在此登记。
  - calculation 无表、不改变已存快照：本组未新增任何 Flyway 版本；`ProductSnapshotMatrixTest#globalChangeDoesNotRewriteOtherProductSnapshots` 证明全局配置变更不回溯既有商品快照，迁移只改代码结构、不重算历史。
  - 全量门禁（命令/退出码/测试数/失败归因）：①后端 `YUMI_DB_PASSWORD=<钥匙串 yumi-v2-local-test> ./mvnw -q test` 退出码 0，**27 类 136 测试 0 失败 0 错误**（含 Spring Modulith `ModuleStructureTest` 3 用例与本机 MySQL 集成）；②前端 `npm run typecheck` 退出码 0；`npm test` 退出码 0（**6 文件 34 用例**）；`npm run build` 退出码 0（仅既存 chunk 体积提示，非本次引入）；③`openspec validate build-yumi-v2-order-fulfillment --strict` 输出 `Change 'build-yumi-v2-order-fulfillment' is valid`。
  - 豁免与部署范围：保留既有 Testcontainers 豁免（未新增 Docker/Testcontainers 或任何部署步骤），本机 MySQL 集成测试沿用钥匙串口令；未扩大部署范围。
  - 双向追踪：`formula-catalog.md` 第 4 节把 FP-PROD-01..19 映射到代码位置与测试编号；`design.md` §5.1 决策、§6 商品试算行、§7 `/catalog/products` 行与 `master-data-management` 三个 Requirement、本组 2.13–2.19 逐项对应。本任务只勾选文档与门禁，**2.19 仍未勾选**（不得用文档更新代替实现或人工验收）。
  - 人工证据：不适用（本任务无页面/打印/Electron 变更；交互与视觉验收见 2.19）。
- [x] 2.19 正式路由交互与阶段补充验收（依赖 2.18）：在浏览器与 Electron 复用页验证新建、编辑原快照、换引用、刷新价格、快速输入、试算断网重试、保存后结果和网络请求；核查控制台与 API。与 2.12 合并安排人工视觉确认，但证据分别回填。
  - 证据（2026-09-24 执行代理在内置浏览器以真实会话**完整自测**，操作者=代理、凭据为本机合成测试账号；用户已于 2026-09-24 签字确认，本项据此勾选）：
  - 环境：本机后端（8080/8081 被其他项目占用，改用 18080，`SERVER_PORT=18080`、口令取钥匙串 `yumi-v2-local-test`）与前端 dev server（5174，`VITE_API_TARGET=http://127.0.0.1:18080`）；验收 URL `http://127.0.0.1:5174`。注意：用户原有 5173 dev server 的代理指向 8080，而 8080 现被另一项目占用。
  - 已完成的交互与机器证据：
    1. **路由与认证**：未认证深链 `/catalog/products` 正确重定向 `/login`；登录后进入业务页（header 显示 admin）。经 Vite 代理 `POST /api/products/preview` 未认证返回统一信封 401 `AUTH_REQUIRED`。
    2. **待填写**：计算字段未齐时面板显示「待填写」，15 项全为 `—`，不发请求。
    3. **服务端试算逐项核对**：新建（三星 15min、270g、20% 损耗、缝边 5min、成交价 25、无档位）→ 胶水克重 `324`、胶水 `3.2400`、色浆 `6.4800`、材料 `9.7200`、星级人工 `5.0000`（qty8=32/qty6=24）、装箱 `0.5000`、人工 `5.5000`、其他 `0.9000`、总成本 `16.1200`、参考售价 `23.0286`、利润 `8.8800`、利润率 `35.52%`、缝边 `1.2500`/参考 `1.7857`；改选四星 20min → qty6=18/人工 `6.6667`/总成本 `17.7867`。全部与手算逐项一致。
    4. **只读零写入**：整轮试算前后 `products`、`number_sequences.current_value`、`master_data_change_logs`、`audit_logs`、`idempotency_records` 五项与基线完全相同（`66/560/463/3512/875`）。
    5. **防抖与无关字段**：填 5 个计算字段 + 选星级仅产生 1 次 `POST /api/products/preview [200]`；随后 3 次连续改克重又仅 1 次请求，面板最终值对应最后一次输入（400g → `480` 克/`22.4667`）。
    6. **失败与重试**：停掉后端后改字段 → `POST /api/products/preview [502]`，面板显示「试算失败」+「重 试」，15 项全为 `—`（无伪造零值、无本地降级）；点击重试确实发出新请求（因重启清空会话返回 401，重新登录后继续）。
    7. **保存与回读**：新建保存后回读 `P00561 验收-试算A`——三星/15、成交价 `25.0000`、胶水克重 `324`、材料 `9.7200`、人工 `5.5000`、总成本 `16.1200`、参考售价 `23.0286`、缝边 `1.2500`/`1.7857`、`version 0`，与试算逐项一致；计数 products 66→67、seq 560→561、变更日志 **0 增**（创建不写变更日志，与 2.9 派生决策一致）、写审计 +1、幂等记录 +1。
    8. **编辑沿用原快照**：全局三星 15→60、胶水 0.01→0.02 后，编辑 P00561 的试算仍为 15 分钟/`5.0000`/`16.1200`，商品行未被试算改动（三星/15/0.0100/`version 0`）。
    9. **换引用取当前全局**：改选一星（全局 5 分钟）→ qty8=96/qty6=72/人工 `1.6667`/总成本 `12.7867`/利润率 `48.8532%`。
    10. **“全局已变”提示与一键按最新设置重算**：编辑页表单标题下方显示警示条并逐项列出差异（`星级「三星」标准时长 60 → 45 分钟；胶水单价 0.0200 → 0.0300`），带「刷新为最新设置」按钮；**无差异时警示条不出现、星级标签也不标注「（商品快照）」**。点击刷新后试算立即按当前全局重算（qty6=6/人工 `20.0000`/材料 `12.9600`/总成本 `34.3600`），警示条消失、标签恢复当前全局值；保存后回读 P00561：`star_std_minutes=60`、`glue_unit_price=0.0200`、`material_cost=12.9600`、`total_cost=34.3600`、`qty_6h=6`、`version=1`，与刷新后的试算逐项一致。
    11. **控制台**：仅 `[vite] connected`、React DevTools 与既有的 react-router `HydrateFallback` 提示，**无 error**；本次引入的 antd `Alert message 已弃用` 告警已修复（改用 `title`）后复测消失。
  - 自测过程中发现并修复：①编辑页星级/档位下拉标签显示当前全局值而试算/保存用快照，已改为仅在与全局不一致时标注「（商品快照）」；②“刷新”首次点击未生效系运行中的后端仍为旧进程（忽略新字段），重启后复测通过——非代码缺陷。
  - 未完成项（如实记录）：Electron 复用页交互未在本轮自动执行（Electron 窗口无法由内置浏览器驱动），仅由 `electron/shell-guard.test.ts`（5 用例）与“共享同一 renderer 与同一 API”保证；如需 Electron 实操验证请另行安排。
  - 人工证据（与 2.12 合并签字，证据分别回填）：
    humanVisualConclusion:
      status: confirmed
      checklist:
        - "原布局保留：左侧三列表单 + 右侧 sticky「利润预估」卡，未因预估来源改为服务端而变化"
        - "预估状态清楚：待填写 / 计算中 / 试算失败+重试 / 已按服务端试算 四种状态可见且含义明确"
        - "失败不显示假零值：试算失败或断网时所有金额显示 —，不回退本地计算、不显示伪造零值"
        - "编辑未换引用时显示原快照值（全局改星级时长/料价后不回溯），换引用后按当前全局更新"
        - "「全局设置已变化」警示条仅在存在差异时出现，含差异明细与「刷新为最新设置」按钮；无差异时不出现"
        - "点击刷新后试算按最新设置重算、警示条消失，保存后快照更新为当前全局值"
        - "保存后预估被服务端保存结果覆盖，配置变化时出现金额变化提示"
        - "只读试算不写业务数据：操作前后商品、编号序列、变更日志、写审计、幂等记录无新增"
      confirmedBy: chen
      confirmedOn: 2026-09-24
      conclusion: "用户于 2026-09-24 确认 2.19 交互与视觉验收清单（含警示条与「刷新为最新设置」按钮）；交互实据、网络请求与数据库回读见上方证据。Electron 复用页实操未执行，仅由 shell-guard 测试与共享 renderer 保证。"
  - **商品页视觉项作废与重签（2026-09-24，任务 2.27）**：本清单中“原布局保留（三列表单 + 右侧 sticky 利润预估卡）”“四状态可见”“失败不显示假零值”“编辑未换引用显示原快照”“保存后预估被服务端结果覆盖”等项因 2.21–2.24 的商品字段变化（新增默认缝边剪袋类型与缝边价格、面板增加缝边剪袋变体分组、警示条覆盖范围扩展）**原签字作废**，已合并进 2.24 的 `humanVisualConclusion`，并已于 2026-09-24 经用户确认完成重签（`confirmedBy: chen`）；「全局设置已变化」警示条的差异明细覆盖范围已由 2.24 扩展为 星级/档位/胶水单价/色浆单价/日常杂费/房租水电费。
- [x] 2.20 设置页只读公式说明（依赖 2.14、2.16）：实现认证 `GET /api/settings/formulas`，从 calculation 静态目录返回已实现公式；在正式 `/settings` 增加“公式说明”只读 Tab，展示公式名称、稳定标识、输入/单位、表达式、舍入规则、结果含义和固定示例。不得新增公式表或编辑/发布接口，接口不产生幂等记录、业务写审计、编号或业务事实，前端不复制计算逻辑。
  - 证据（**实现与自动化门禁完成；浏览器交互与人工签字待批次边界 2.27 一次执行**）：
  - 文件与端点：新增 `backend/src/main/java/com/yumi/calculation/FormulaCatalog.java`（静态目录，19 条商品公式，每条含标识/分类/名称/输入与单位/表达式/舍入节点/结果含义/固定示例/代码位置/测试编号；不落库、不可编辑）；`SettingsViews` 新增 `FormulaView`/`FormulaGroupView`/`FormulaCatalogView`；`SettingsService.formulaCatalog()` 按分类分组（`@Transactional(readOnly = true)`，不读数据库）；`SettingsController` 新增 `GET /api/settings/formulas`；前端 `src/api/settings.ts` 新增 `FormulaEntry`/`FormulaGroup`/`FormulaCatalogView` 与 `getFormulas()`；`SettingsPage.tsx` 改为 Tabs 结构（「单价与默认值」承载原内容 + 新增「公式说明」只读 Tab，表格展示 7 列，无任何编辑入口）。
  - 契约：认证管理员；成功 200 统一信封，`data.groups[].formulas[]`；GET 不要求 `Idempotency-Key`（写过滤器只作用于 POST/PUT/PATCH/DELETE），不产生幂等记录、写审计、编号或业务事实；未建设业务（工资/财务）不出现空条目。
  - RED/GREEN：`YUMI_DB_PASSWORD=<钥匙串> ./mvnw -q -Dtest=FormulaCatalogApiTest test` 首跑退出码非 0（编译失败：`SettingsService` 缺 `ArrayList` 导入），补导入后退出码 0，**4/4**。关键断言：①未认证 401 `AUTH_REQUIRED`；②`groups.length=1`、`category=商品`、`formulas.length=19`，首条逐字段（`FP-PROD-01`/损耗率换算/百分比文本 %/percent ÷ 100/scale6 HALF_UP/内部损耗比例/33.3333 → 0.333333/calculation/DecimalPolicy.percentToRatio/ProductPricingBaselineTest#lossRateConversionUsesScale6HalfUp）；③目录完整性——19 条标识唯一且为 `FP-PROD-01..19`、九个字段全部非空、`testId` 含 `#`；④不带幂等键 GET 成功且商品/变更日志/写审计/幂等记录四项计数不变。
  - 全量门禁（本任务边界）：后端 `./mvnw -q test` 退出码 0，**28 类 142 测试 0 失败 0 错误**；前端 `npm run typecheck` 退出码 0、`npm test` 退出码 0（6 文件 34 用例）、`npm run build` 退出码 0。
  - 浏览器验证（2026-09-24 执行代理在内置浏览器以真实会话自测，正式路由 `/settings`，结构化 DOM 探针读数）：①入口——`/settings` 顶部两个 Tab「单价与默认值」「公式说明」，`data-node-key="formulas"` 且 `aria-selected="true"` 表示已切换；②分组层级——当前仅「商品」一组，无工资/财务等未建设分类的空条目；③公式详情——表格 7 列「标识/名称/输入与单位/表达式/舍入规则/结果含义/固定示例」，**共 17 行**，首行 `FP-PROD-01 | 损耗率换算 | 百分比文本 % | percent ÷ 100 | scale6 HALF_UP | 内部损耗比例 | 33.3333 → 0.333333`，末行 `FP-PROD-19 | 金额精度工具 | 任意 BigDecimal | 原值转 scale4 | scale4 HALF_UP | 金额统一口径 | 0.12345 → 0.1235`（FP-PROD-09/10 随缝边移出商品分类，编号不复用）；④无编辑能力——该 Tab 面板内 `button` 数量为 **0**（无编辑/保存/发布/生效时间入口）；⑤网络——`GET /api/settings/formulas [200]`（GET 不要求 `Idempotency-Key`，不产生业务写入，已由 `FormulaCatalogApiTest` 断言）；⑥控制台无 error。
  - 人工证据（与 2.12/2.19 同一批签字，证据分别回填）：
    humanVisualConclusion:
      status: confirmed
      checklist:
        - "设置页顶部有「公式说明」Tab，与「单价与默认值」并列、点击可切换"
        - "公式按业务分组展示（当前只有「商品」一组），未建设的工资/财务不出现空条目"
        - "每条公式展示 标识/名称/输入与单位/表达式/舍入规则/结果含义/固定示例 七列，数值与示例可读"
        - "页面只读：没有任何编辑、保存、发布或生效时间入口"
      confirmedBy: chen
      confirmedOn: 2026-09-24
      conclusion: "用户于 2026-09-24 确认 2.20 只读公式说明清单；自动化与浏览器证据见上方条目。"

执行顺序：2.13 → 2.14 → 2.15 → 2.16 → 2.17 → 2.18 → 2.19 → 2.20；静态数据与商品字段组独立于 2.20，按 2.21 → 2.22 → 2.23 → 2.24 → 2.25 → 2.26 → 2.27 执行（2.20 只读公式说明为独立项，暂停中，不阻塞本组）。后续 3.3/3.6、4.4、5.3/5.4/5.13、7.4、8.8、9.1/9.4 中的数值公式统一进入 calculation 对应分类；缝边的成本与收费公式随订单实现进入 `calculation/order`。各阶段仍负责自身业务规则和验收，不将未实现公式提前塞入本组。阶段三依赖阶段二原有门禁及本组完成。

### 阶段二追加：静态数据与商品字段调整（2026-09-24）

本组依据 `docs/architecture/static-data-and-product-fields-design.md` 与 `design.md` §5.2/§6/§7，追踪 `master-data-management` 的“静态数据必须由系统固定类别、条目按类别管理”“商品字段必须区分商品属性、全局口径与静态数据引用”，以及 `order-lifecycle` 的“缝边必须作为订单明细行的定制服务”。开发期未上线、不考虑历史数据：改写 `V4`/`V5` 迁移为最终结构并清库重建，不做数据迁移。既有 2.1–2.19 的勾选与证据保留为历史；其中商品缝边字段、包装档位提成、员工工种值口径与设置页结构将被本组替换，2.12/2.19 的商品页视觉签字需在 2.27 重新验收。

- [x] 2.21 数据模型与迁移重写（依赖 2.1、2.2、2.7、2.10 的既有实现）：改写 `V4__catalog.sql` 与 `V5` 为最终结构——新增 `seam_types`（名称唯一 + 成本单价 `DECIMAL(19,4)`，允许 0）与 `work_types`（`code` 唯一且系统固定 + 名称唯一 + 启停），`packaging_tiers` 去掉提成列，`products` 删除四个缝边列并把 `packaging_commission` 语义改为商品提成，`employee_work_types` 改为引用 `work_types.id`；`V5` 更名为 `V5__static_data_seed.sql` 并写入星级 5 条与工种 4 条种子（缝边种类不预置）；清库重建。
  - 验收与证据（**实现与自动化门禁完成；浏览器与人工签字待批次边界 2.27**）：改写 `V4__catalog.sql` 为最终结构（`star_levels` 去 `star` 列；新增 `seam_types`（名称唯一 + `cost_price DECIMAL(19,4)`）与 `work_types`（`code` 唯一 + 名称唯一 + `active`）；`packaging_tiers` 去 `commission`；`products` 用 `star_level_id` 且删除 `seam_minutes`/`seam_labor_cost`/`seam_default_fee`/`seam_reference_fee`，`packaging_commission` 语义改为商品提成；`employee_work_types` 改 `work_type_id` + 外键）；`V5__star_levels_static_data.sql` 更名为 `V5__static_data_seed.sql`（星级 5 条 + 工种 4 条种子，缝边种类不预置）；清空测试库（DROP 15 张表）后由 Flyway 全新执行 V1–V5。`CatalogMigrationTest` 4/4：新增表与唯一键、`cost_price`/`work_type_id` 类型、`employee_work_types` 无 `work_type` 列、`products` 无四个缝边列、`packaging_tiers` 无 `commission`、V5 种子齐全。`database-design.md` §3 表归属已补 `seam_types`/`work_types`，§15 把“已执行迁移不得修改”限定为**上线后**并注明本次重写内容。
- [x] 2.22 静态数据 API（依赖 2.21）：实现 `GET /api/settings/static-data`（类别清单）、`GET /api/settings/static-data/{code}`（条目）、`POST/PATCH/DELETE /api/settings/static-data/{code}/items[/{id}]`；类别按系统固定 code 寻址、不提供类别增删改名；星级/包装档位/缝边种类条目可增改删，员工工种仅可改名与启停；条目名称类别内唯一、被引用禁删、增删改记变更日志；移除旧 `/api/settings/star-levels` 与 `/api/settings/packaging-tiers`。
  - 证据（**静态数据 API 已实现并测试；旧路径移除随 2.25 前端切换一并执行**）：新增 `catalog/settings/StaticDataService.java`（类别定义固定为 `STAR_LEVEL`/`PACKAGING_TIER`/`SEAM_TYPE`/`WORK_TYPE`，类别不可增删改名；星级/包装档位/缝边种类条目用户增改删、名称类别内唯一、被引用禁删；员工工种仅可改名与启停；增删改记变更日志）与 `StaticDataController.java`（`GET /api/settings/static-data`、`GET .../{code}`、`POST .../{code}/items`、`PATCH .../{code}/items/{id}`、`DELETE .../{code}/items/{id}`；未引入独立 DTO 文件，复用服务内嵌套记录）。
  - 契约与错误码：认证管理员；成功 200/201/204 走统一信封；未知类别 404 `NOT_FOUND`；条目不存在 404；名称重复 409 `CONFLICT_DUPLICATE`；被商品或员工引用 409 `CONFLICT_REFERENCED`；工种 POST/DELETE 400 `VALIDATION_INVALID`；非法数值（负值/超范围/非数字）400 + fieldErrors；写命令要求 `Idempotency-Key`（未被豁免）。
  - RED/GREEN：`YUMI_DB_PASSWORD=<钥匙串> ./mvnw -q -Dtest=StaticDataApiTest test` 首跑 7 用例 1 failure（DELETE 返回 500：变更日志 `after_json` 非空约束不接受 null），改用 `{"exists": false}` 标记后退出码 0，**7/7**。关键断言：类别清单 4 项且 `WORK_TYPE.itemCount=4`；条目按类别给不同字段（星级 `stdMinutes`、工种 `code`+`active`、未知类别 404）；缝边种类建/重名 409/改名改值/未引用删除 204/非法值 400；被商品引用的星级删除 409 且条目仍在；工种新增与删除被拒、改名与停用可用且 `code` 不变；类别与条目查询前后变更日志/写审计/幂等记录三项计数不变。
  - 全量门禁：`./mvnw -q test` 退出码 0，**29 类 151 测试 0 失败 0 错误**。
  - 旧路径移除（随 2.25 完成，接口级机器证据）：`SettingsController` 删除 `/api/settings/star-levels`、`/api/settings/packaging-tiers` 七个端点，`SettingsService` 删除星级/档位 CRUD（保留只读 `starLevels()`/`tiers()` 供 `GET /api/settings`），`SettingsViews` 删除旧请求 DTO；登录后 `GET /api/settings/star-levels` → **404**、`GET /api/settings/packaging-tiers` → **404**，`GET /api/settings/static-data` → 200 返回四类别；`SettingsApiTest` 已迁移到静态数据路径（被 `StaticDataApiTest` 覆盖的星级 CRUD 用例删除）。
- [x] 2.23 商品解析与公式口径（依赖 2.21、2.22）：商品入参移除缝边字段、新增 `packagingCommission`，`lossRatePercent`/`dailySundriesFee`/`rentUtilitiesFee` 不再接受；`ProductInputResolver` 对全局口径字段一律取当前全局值、包装提成取商品；`ProductPricing` 的 FP-PROD-08 改为“档位标准分钟 × 0.25 + 商品提成”，移除 FP-PROD-09/10；更新基准算例与公式目录。
  - **用户追加需求修订（2026-09-24：包装提成改为全局默认 + 商品可改）**：新增迁移 `V6__packaging_commission_default.sql` 写入全局键 `packaging_commission_default`；`ValuesView`/`SettingsService.MONEY_KEYS` 增加 `packagingCommissionDefault`（统一信封下 8 个全局值）；`ProductInputResolver.forCreate` 在商品未填提成时取 `reference.moneySetting("packaging_commission_default")`，商品显式填写时覆盖；前端 `SettingsValues` 与设置页表单增加该字段，商品表单新建时预填。依据真实核算表的“打包提成”参数与用户 2026-09-24 确认。新增用例 `ProductSnapshotMatrixTest#packagingCommissionDefaultsFromGlobalAndCanBeOverridden`（默认 0.5 → `2.0000`；覆盖 0.8 → `2.3000`），该类 9/9；后端全量 143 测试 0 失败。
  - 验收与证据（**实现与自动化门禁完成；浏览器与人工签字待批次边界 2.27**）：`ProductPricing` 的 `Inputs`/`Result` 去掉缝边、包装提成改为输入；`ProductInputResolver` 对胶水损耗率/日常杂费/房租水电一律取当前全局设置（客户端提交被忽略），包装提成取商品字段，缝边相关逻辑整体移除；`ProductRow`/`ProductRepository` 去四个缝边列；`CreateProductRequest`/`UpdateProductRequest` 去缝边与全局口径字段、加 `packagingCommission`；`ProductDetail`/`ProductPreview` 去缝边；`FormulaCatalog` 把 FP-PROD-08 改为“档位分钟 × 0.25 + 商品包装提成”并移除 FP-PROD-09/10（19→17 条，编号不复用）；`CatalogReference.PackagingTier` 去 `commission`。关键断言：用例仍提交 `lossRatePercent=20` 但结果取全局值（`ProductApiTest` 回显 `20.000000`、钉死算例总成本 `16.2200` 不变）；包装提成驱动包装人工（8.5×0.25+0.3=`2.4250`）；档位快照只保留标准分钟（`2.5000`/`5.0000` 与对应总成本）。门禁：后端 **28 类 143 测试 0 失败**；前端 typecheck/test（34 用例）/build 全 0。
- [x] 2.24 商品表单与试算（依赖 2.23）：商品表单移除缝边数量/缝边成本字段、新增包装提成输入、包装档位改为下拉选择，全局口径字段改为只读展示；按追加口径新增「默认缝边剪袋类型 + 缝边价格」默认值，试算面板给出不缝边剪袋/缝边剪袋两套预算；保持既有布局与四状态。
  - 证据：Requirement/Scenario：`master-data-management` Requirement“静态数据必须由系统固定类别、条目按类别管理”的 Scenario“被引用的条目不能删除”“包装档位预置 6 分钟档位”，Requirement“商品字段必须区分商品属性、全局口径与静态数据引用”的 Scenario“商品提供缝边默认值而不决定缝边”“缝边剪袋变体预算按种类成本单价单列”“编辑时清空或更换默认缝边剪袋类型”；`order-lifecycle` Requirement“缝边必须作为订单明细行的定制服务”（商品只提供默认值、订单可覆盖）。正式文档：`docs/architecture/static-data-and-product-fields-design.md` §1/§3.1/§3.2/§3.4/§4/§5/§6/§12、`docs/architecture/formula-catalog.md` §3/§4（FP-PROD-20/21）、`design.md` §5.2。
  - **2026-09-24 追加口径（依据真实核算表 `新核算表.xlsx` 与用户确认）**：①色浆成本维持 `胶水用量 × 色浆单价`（胶水用量已含损耗率，不重复计损耗；真实表 `× 损耗率` 系表内笔误）；②缝边**决策权在订单、商品提供默认值**：商品新增「默认缝边剪袋类型」（从 `SEAM_TYPE` 选，可空＝默认不缝边剪袋）与「缝边价格（元/件）」作为新建订单明细的默认值（订单可改）；商品试算与详情给「不缝边剪袋/缝边剪袋」两套预算，商品 `total_cost`/`reference_price` 按不缝边剪袋口径保存；`SEAM_TYPE` 仍为「名称 + 成本单价」供订单带出成本提示；③核算表内部的定价分析字段（最低批发价、单克价、双利润/双利润率）不纳入系统；④包装档位预置 6 分钟档位（对应固定 `6×0.25 + 商品提成`）。**说明**：本任务第一轮实现（商品侧无任何缝边字段）已被本追加口径取代，下述证据为本轮最终实现的实据。
  - 文件与迁移：`backend/src/main/resources/db/migration/V4__catalog.sql`（`products` 新增 `seam_type_id`（可空 FK → `seam_types.id`）/`seam_type_name`/`seam_type_cost_price`/`seam_fee` 四列与 `fk_products_seam_type`、`idx_products_seam_type_id`；仍无 `seam_minutes`/`seam_labor_cost`）、`V5__static_data_seed.sql`（预置「6 分钟档」`std_minutes = 6.000`）；`calculation/product/ProductPricing.java`（新增 `SeamBudget` 与 `seamBudget`、抽出 `referencePrice`）、`catalog/product/SeamBudgetView.java`（新增）、`catalog/product/internal/CatalogReference.java`（`seamType(id)`）、`ProductInputResolver.java`（`Resolved` 增加缝边默认值四字段；`forCreate`/`forUpdate` 解析、快照与清空语义）、`ProductRow.java`、`ProductRepository.java`（列/insert/update/map）、`CreateProductRequest.java`、`UpdateProductRequest.java`（新增 `clearSeamType`）、`ProductDetail.java`、`ProductPreview.java`、`ProductService.java`（试算与详情返回 `seamBudget`，变更日志快照增加 `seamTypeId`/`seamFee`）、`catalog/settings/StaticDataService.java`（`SEAM_TYPE` 删除守卫计入商品默认值引用）；前端 `src/api/catalog.ts`、`src/pages/catalog/previewInput.ts`、`ProductPreviewResult`/`ProductPreviewInput`、`src/pages/catalog/ProductsPage.tsx`、`src/styles.css`。
  - 契约：`POST/PATCH /api/products` 入参新增 `seamTypeId`（可空）、`seamFee`；PATCH 清空默认缝边剪袋类型必须显式 `clearSeamType=true`（`null` 与“未提供”在缺省合并下无法区分），`clearSeamType` 与 `seamTypeId` 同时提供返回 400 `VALIDATION_INVALID`；出参新增 `seamTypeId`/`seamTypeName`/`seamTypeCostPrice`/`seamFee` 与 `seamBudget`（未选默认缝边剪袋类型时为 `null`）；`POST /api/products/preview`、`POST /api/products/{id}/preview` 同步支持 `seamTypeId`/`clearSeamType`/`seamFee` 并返回 `seamBudget`；错误码沿用 `VALIDATION_INVALID`（`seamTypeId` 不存在、`seamFee` 为负、`clearSeamType` 冲突）、`CONFLICT_REFERENCED`（缝边种类被商品默认值引用时禁删）、`CONFLICT_VERSION`、`AUTH_REQUIRED`、`NOT_FOUND`。表：`products`、`seam_types`；Flyway：改写 `V4`、`V5`（未上线阶段清库重建，不新增版本）；事务拥有者：`ProductService.create/update` 的 `@Transactional`（试算为只读事务）；锁定对象：无新增（沿用 `@Version` 乐观锁）；幂等键：写命令沿用 `Idempotency-Key`，只读试算精确豁免不变。
  - RED/GREEN 口径（如实记录）：本任务为**追加口径实现**，实现先于用例，按全局完成定义第 2 条不伪造 RED；`YUMI_DB_PASSWORD=<钥匙串> ./mvnw -Dtest=ProductSeamDefaultApiTest test` 首跑退出码非 0，5 用例 4 failures（`Status expected:<201> but was:<500>`），`No value specified for parameter 41`（新增列未同步占位符）与 `Column 'seam_type_cost_price' cannot be null`（默认不缝边剪袋时未归零）两处真实缺陷，修正后同命令退出码 0，**5/5**。
  - 变异检查（证明用例有效，非空跑）：①把 `ProductPricing.seamBudget` 的种类成本单价固定为 0；②把 `forUpdate` 的同引用快照改为始终读当前条目。复跑 `-Dtest=ProductSeamDefaultApiTest,ProductPricingBaselineTest` → **4 failures**（`seamBudgetAddsSeamUnitCostWithoutTouchingProductCost`、`createStoresSeamDefaultAndReturnsBothBudgets`、`editKeepsSnapshotThenRefreshesSwitchesAndClearsSeamType`、`previewReturnsSeamVariantWithoutBusinessWrites`），恢复实现后同命令 **15/15**。
  - 关键断言（钉死算例：三星 std=15、270g、损耗 20%、成交价 25、档位 6 分钟、提成 0.5、装箱 0.5、运输 0.3、模具 0）：胶水克重 324、材料 `9.7200`、星级人工 `5.0000`、包装人工 `2.0000`（6×0.25+0.5）、人工 `7.5000`、其他 `0.9000`、**不缝边剪袋总成本 `18.1200`**、不缝边剪袋参考售价 `25.8857`、利润 `6.8800`、利润率 `27.52%`；缝边种类「标准缝边」成本单价 `1.2500` → **缝边剪袋变体总成本 `19.3700`**、变体参考售价 `27.6714`；落库 `products.total_cost = 18.1200`（不缝边剪袋口径）且 `seam_type_cost_price = 1.2500`；未选默认缝边剪袋类型时 `seamBudget` 为 `null` 且 `seam_fee = 0.0000`；条目改值不回溯（同引用再保存仍 `1.2500`），`refreshGlobalReferences=true` 才采纳当前 `9.0000`；换成不同引用取当前条目（`3.0000` → 变体 `19.1200`）；`clearSeamType=true` 清空后 `seamTypeId`/`seamBudget` 为 `null` 而 `seam_fee` 保留 `2.0000`；`seamTypeId` 不存在与 `seamFee` 为负均 400 + `fieldErrors`；缝边种类被商品默认值引用时 DELETE 返回 409 `CONFLICT_REFERENCED`；只读试算前后商品/编号序列/变更日志/写审计/幂等记录五项计数不变；编辑试算只传 `seamTypeId: null` 仍沿用原值（缺省合并），必须 `clearSeamType=true` 才清空。
  - 阶段门禁（2.27 复跑，含本任务全部改动）：后端 `YUMI_DB_PASSWORD=<钥匙串> ./mvnw -q test` 退出码 0，**161 测试 0 失败 0 错误**（较 2.23 边界 151 增加 10：`ProductSeamDefaultApiTest` 6、`ProductPricingBaselineTest` +2、`CatalogMigrationTest` +2）；前端 `npm run typecheck`/`npm test`（6 文件 37 用例）/`npm run build` 退出码均 0；`openspec validate build-yumi-v2-order-fulfillment --strict` 输出 valid。
  - 门禁期间发现并修复的两处非本任务缺陷（如实记录）：①**测试隔离缺口**——`ProductSnapshotMatrixTest` 依赖库内「包装提成默认」残留值（隐含假定 0），被验收环境种子（0.5）污染后 `sameTierReferenceKeepsSnapshotEvenIfGlobalChanged` 失败（`expected 2.5000 but was 3.0000`）；已在 `@BeforeEach` 固定该全局值为 0，复跑恢复 161/0。归因：环境种子污染 + 测试未自带基线，非业务代码缺陷；**运维口径**：后端全量测试与验收库是同一个库，须先跑门禁再铺验收数据。②**前端类型不精确**——`createEmployee` 的 `workTypes` 声明为 `WorkTypeView[]` 而表单实际提交 `string[]`（code），已改为 `string[]` 并注明按 code 出入参（属 2.26 契约口径）。
  - 浏览器自测（2026-09-24，正式路由 `http://127.0.0.1:5174`，后端 18080；清库重建后空库起，登录本机合成账号 `admin`；结构化 DOM 探针读数）：①**只读与预填**——「胶水损耗率（%）」显示 `20.000000`、「日常杂费（元）」`0.2000`、「房租水电费（元）」`0.4000`，三者均为纯文本并标注「全局设置」且无输入框；「包装提成（元/件）」`0.5000`、「单件装箱人工费」`0.5000`、「运输包装费」`0.3000`、「模具摊销费」`0.0000` 为可编辑输入；**表单已无「缝边标准时长」「默认缝边收费」**。②**新增字段**——「默认缝边剪袋类型」为可清空下拉，选项显示 `名称 · 成本单价 元/件`（`标准缝边 · 1.2500 元/件`、`双边缝边 · 3.0000 元/件`），并带说明「订单新建明细时的默认值，订单可改；留空＝默认不缝边剪袋」；「缝边价格（元/件）」为可编辑输入。③**两套预算**——面板把原「单件总成本」「参考售价」标注为「不缝边剪袋」，并新增分组「缝边剪袋变体（含缝边成本，单件）」共 4 行；选「标准缝边」时缝边剪袋变体总成本 `19.3700`、参考售价 `27.6714`，与不缝边剪袋 `18.1200`/`25.8857` 并列；未选默认缝边剪袋类型时缝边剪袋 4 行全部显示 `—`（不伪造零值）并有说明文案。④**换引用与清空**——切到「双边缝边」缝边剪袋变体立即变为 `21.1200`/`30.1714`；点下拉清空后缝边剪袋 4 行回到 `—`。⑤**保存与回读**——新建 P00001「验收-缝边默认值」列表显示单件总成本 `18.1200`；库内 `seam_type_id=2`/`seam_type_name=双边缝边`/`seam_type_cost_price=3.0000`/`seam_fee=2.0000`/`total_cost=18.1200`（不缝边剪袋口径）/`version=2`；清空后保存则 `seam_type_id`/`seam_type_name` 为 `NULL`、`seam_type_cost_price=0.0000`、`seam_fee` 保留 `2.0000`。⑥**删除守卫**——删除被 P00001 默认引用的缝边种类返回 409 `CONFLICT_REFERENCED`，条目仍在。⑦**设置页**——`/settings` 三个 Tab，静态数据类别列表 4 行（制品星级 5 / 包装档位 **1** / 缝边种类 2 / 员工工种 4），无类别增删改名入口；「包装档位」弹窗只有预置的「6 分钟档 6.000」（带新建/编辑/删除）；「员工工种」弹窗 4 行且只有「编辑」（无新建/删除）并显示固定说明。⑧**员工页**——`/catalog/employees` 工作类型下拉显示名称（制作/捏毛装袋/缝边剪袋/其他），选「缝边剪袋」保存后库内 `employee_work_types → work_types.code = SEAM_CUTTING`、展示名称「缝边剪袋」。⑨**控制台**——仅 `[vite] connected`、React DevTools 与既有的 react-router `HydrateFallback` 提示，无 error。
  - 自测中发现并修复的真缺陷（先失败用例后修复）：**编辑试算清空默认缝边剪袋类型后缝边剪袋变体不消失**——保存路径带 `clearSeamType` 而试算路径只传 `seamTypeId: null`，被 PATCH 缺省合并理解为“保持原值”，面板继续显示旧变体（实测清空后仍为 `1.2500`/`19.3700`）。修复：`ProductPreviewInput` 增加 `clearSeamType`，`previewInput.ts` 抽出 `isSeamCleared(values)` 供试算与保存共用；新增后端用例 `editPreviewFollowsClearSemanticsInsteadOfKeepingStaleVariant`（未传字段沿用快照 → `18.4500`；只传 `seamTypeId:null` 仍 `17.3700`；`clearSeamType:true` → `seamBudget` 不存在）与前端用例「清空默认缝边剪袋类型时显式带 clearSeamType」。修复后复测清空 → 缝边剪袋 4 行全部 `—`。
  - 自测中发现并补齐的相邻缺口：编辑页「全局设置已变化」警示条原先只列星级/档位/胶水单价/色浆单价，**遗漏同为商品快照的「日常杂费」「房租水电费」**。已补齐两项差异检测；实测把全局房租 `0.4000 → 0.4500` 后警示条显示「房租水电费 0.4000 → 0.4500」且试算按当前全局重算（`18.1700`），恢复全局后警示条消失。
  - 人工证据（与 2.20/2.25/2.26/2.27 同批签字，含 2.12/2.19 商品页视觉清单的重签）：
    humanVisualConclusion:
      status: confirmed
      checklist:
        - "商品表单已无「缝边标准时长」「默认缝边收费」等缝边数量/成本字段，改为「默认缝边剪袋类型」可清空下拉 + 「缝边价格（元/件）」输入"
        - "「默认缝边剪袋类型」下拉显示 名称 · 成本单价 元/件，并注明是订单缝边定制的默认值、留空＝默认不缝边剪袋"
        - "胶水损耗率、日常杂费、房租水电为只读文本并标注「全局设置」，不可编辑；包装提成、装箱人工费、运输包装费、模具摊销费仍可改"
        - "利润预估面板把原「单件总成本」「参考售价」标注为不缝边剪袋，并新增「缝边剪袋变体（含缝边成本，单件）」分组共 4 行"
        - "选默认缝边剪袋类型时变体有数值（与不缝边剪袋并列），清空时变体 4 行显示 — 并有说明文案，不出现伪造零值"
        - "四种状态（待填写 / 计算中 / 试算失败+重试 / 已按服务端试算）保持可见，布局未被字段增减破坏"
        - "编辑页「全局设置已变化」警示条覆盖星级/档位/胶水单价/色浆单价/日常杂费/房租水电费，含差异明细与「刷新为最新设置」按钮，无差异时不出现"
        - "服务端试算与保存回读一致：不缝边剪袋总成本/参考售价不因缝边默认值改变，缝边只影响缝边剪袋变体展示"
      confirmedBy: chen
      confirmedOn: 2026-09-24
      conclusion: "用户于 2026-09-24 确认本清单 8 项（含按「缝边剪袋」统一后的措辞）；该签字同时作为 2.12/2.19 商品页视觉清单的重签。交互实据、网络请求、数据库回读与设置页读数见上方证据。"
- [x] 2.25 设置页静态数据 Tab（依赖 2.22）：`/settings` 改为 Tab（单价与默认值 / 静态数据 / 公式说明），静态数据 Tab 先列类别（类别名、系统 code、条目数、编辑），点「编辑」用弹窗管理该类别的条目（增改删、被引用禁删提示；工种为改名与启停）。
  - 证据：Requirement/Scenario：`master-data-management` Requirement“静态数据必须由系统固定类别、条目按类别管理”的 Scenario“类别不可增删改名”“被引用的条目不能删除”“包装档位预置 6 分钟档位”“员工工种按标识关联、按名称展示”。正式文档：`docs/architecture/static-data-and-product-fields-design.md` §3.1/§5/§6。
  - 文件：新增 `frontend/src/api/staticData.ts`（`StaticDataCategory`/`StaticDataItem` 类型与 5 个封装函数）；重写 `frontend/src/pages/settings/SettingsPage.tsx` 为三个 Tab（单价与默认值 / 静态数据 / 公式说明），静态数据 Tab = 类别列表（类别/系统标识/条目数/编辑）+「编辑」弹窗管理条目（新建/编辑/删除；工种无新建与删除、仅编辑），移除原星级/档位两张表与两个弹窗；`src/api/settings.ts` 删除旧星级/档位 CRUD 函数与类型。API/表/迁移/事务/幂等键：本任务只改前端，沿用 2.22 已实现的静态数据端点与幂等要求，不新增 Flyway 版本。
  - 浏览器验证（2026-09-24 正式路由 `/settings`，结构化 DOM 探针，**本轮清库重建后复验**）：①三个 Tab 并列——「单价与默认值」「静态数据」「公式说明」；②类别列表 4 行——`制品星级 | STAR_LEVEL | 5`、`包装档位 | PACKAGING_TIER | 1`、`缝边种类 | SEAM_TYPE | 2`、`员工工种 | WORK_TYPE | 4`，列为 类别/系统标识/条目数/操作，**无类别新增、删除或改名入口**；③包装档位弹窗——标题「静态数据 · 包装档位（PACKAGING_TIER）」，列 名称/标准时长（分钟）/操作，唯一一行 `6 分钟档 | 6.000`（V5 预置），带「新建条目」与行内「编辑/删除」；④员工工种弹窗——标题「静态数据 · 员工工种（WORK_TYPE）」，列 系统标识/名称/状态/操作，4 行（`MAKING | 制作 | 启用`、`PACKING_BAG | 捏毛装袋 | 启用`、`SEAM_CUTTING | 缝边剪袋 | 启用`、`OTHER | 其他 | 启用`），按钮仅 4 个「编辑」（无「新建条目」、无「删除」），并显示「员工工种为系统预置的四道工序，标识固定；只能改名称与启停，不能新增或删除」；⑤缝边种类条目数随 2.24 的浏览器自测创建两条后为 2；⑥控制台无 error；⑦与「单价与默认值」「公式说明」Tab 并存不冲突。
  - 门禁：前端 `npm run typecheck`/`npm test`（6 文件 37 用例）/`npm run build` 退出码均 0（2.27 边界复跑）；后端全量 161 测试 0 失败（本任务无后端改动）。
  - 人工证据（与 2.20/2.24/2.26/2.27 同批签字）：
    humanVisualConclusion:
      status: confirmed
      checklist:
        - "设置页有「静态数据」Tab，类别列表展示 类别/系统标识/条目数/操作，且没有类别新增、删除或改名入口"
        - "点「编辑」弹出该类别的条目弹窗：星级/包装档位/缝边种类可新建、编辑、删除"
        - "包装档位弹窗里能看到预置的「6 分钟档（6.000 分钟）」"
        - "员工工种弹窗只有「编辑」，能改名称与启停，不能新增或删除，并有固定说明"
        - "被引用的条目删除时提示不允许（CONFLICT_REFERENCED），条目仍在"
      confirmedBy: chen
      confirmedOn: 2026-09-24
      conclusion: "用户于 2026-09-24 确认本清单 5 项（含预置「6 分钟档」与工种改名「缝边剪袋」）；设置页三 Tab、类别与条目弹窗读数见上方证据。"
- [x] 2.26 员工工种契约变更（依赖 2.21、2.22）：员工接口 `workTypes` 由名称改为 code、响应返回 code 与名称；资格校验按 code；员工页显示名称、提交 code；同步 2.7/2.8 的实现与用例。
  - 证据：Requirement/Scenario：`master-data-management` Requirement“静态数据必须由系统固定类别、条目按类别管理”的 Scenario“员工工种按标识关联、按名称展示”。正式文档：`docs/architecture/static-data-and-product-fields-design.md` §3.1/§5/§7。
  - 契约与文件（后端）：`WorkTypeReference`（按 code/批量 id 解析 code/name/active）；`EmployeeWorkType` 改 `work_type_id`、唯一键改 `(employee_id, work_type_id)`；仓库方法改 `existsByEmployeeIdAndWorkTypeId`；`EmployeeService` 校验系统固定 code、存储工种 id、响应 `workTypes: [{code, name}]`、变更日志快照存 code；`EmployeeEligibilityService` 按 code 解析后再判资格；新增 `WorkTypeView` DTO。前端：`WorkTypeView` 类型、`EmployeeView.workTypes` 改 `[{code,name}]`、请求提交 code、员工页 Tag 显示名称、下拉 options 用 `{code,name}`。用例同步改用 code（`MAKING`/`PACKING_BAG`/`SEAM_CUTTING`/`OTHER`），`workTypesOf` 改为 join `work_types` 取 code；`EmployeeApiTest`/`EmployeeEligibilityTest`/`Stage2IntegrationTest` 全部通过。表：`employees`、`employee_work_types`、`work_types`；Flyway：随 2.21 改写 `V4`/`V5`，本任务不新增版本；事务/幂等键：沿用员工写命令既有事务与 `Idempotency-Key`，不新增。
  - 浏览器验证（2026-09-24 正式路由 `/catalog/employees`，清库重建后复验）：①列表「工作类型」列显示**名称**（无原始 code 外露）；②新建员工表单「工作类型」下拉 4 个选项显示名称（制作/捏毛装袋/缝边剪袋/其他），选项值为 code；③在浏览器选「缝边剪袋」并提交后，库内 `employees E00001 验收-工种名称` 的 `employee_work_types.work_type_id` 解析为 `work_types.code = SEAM_CUTTING`（名称 缝边剪袋）——证明前端提交 code、后端按 id 存储并按目录解析名称。
  - 门禁：后端全量 161 测试 0 失败（含 `EmployeeApiTest`/`EmployeeEligibilityTest`/`Stage2IntegrationTest`）；前端 `npm run typecheck`/`npm test`/`npm run build` 退出码均 0。
  - 遗留（如实记录）：员工页下拉选项暂用前端常量 `WORK_TYPE_OPTIONS`（code+名称，与 V5 种子一致），未改为从 `GET /api/settings/static-data/WORK_TYPE` 读取；工种改名后下拉标签不会自动跟随（列表/详情仍显示目录当前名称）。该遗留不影响「显示名称、提交 code」的契约，留待阶段三或后续批次决定是否接入目录。
  - 人工证据：不适用（本任务为契约与数据口径变更，页面沿用既有布局，未产生需人眼判断的新视觉元素）。
- [x] 2.27 门禁、文档同步与重新验收（依赖 2.23–2.26）：清库重建后跑后端全量、前端 typecheck/test/build 与 OpenSpec strict；同步 `database-design.md`、`formula-catalog.md`、`system-architecture.md`（如需）、`master-data-management` 与 `order-lifecycle` 规格与现行接口契约。
  - 清库重建：停后端 → 逐表 `DROP TABLE`（`SET FOREIGN_KEY_CHECKS=0`）→ 重启后端由 Flyway 从空库执行 **V1→V6 全部 6 个迁移**（`Successfully applied 6 migrations to schema yumi_v2_test, now at version v6`）；Hibernate `ddl-auto: validate` 常绿；空库状态下静态数据为 星级 5 / 包装档位 1（6 分钟档）/ 缝边种类 0 / 员工工种 4，`packaging_commission_default` 由 V6 写入。库口令仍走钥匙串，未写入仓库或证据文本。
  - 门禁（命令/退出码/测试数）：①后端 `YUMI_DB_PASSWORD=<钥匙串 yumi-v2-local-test> ./mvnw test`（工作目录 `backend/`）退出码 0，**161 测试 0 失败 0 错误**（29 类；2.23 边界为 151，本批次新增 10：`ProductSeamDefaultApiTest` 6、`ProductPricingBaselineTest` +2、`CatalogMigrationTest` +2）；②前端 `npm run typecheck` 退出码 0、`npm test` 退出码 0（**6 文件 37 用例**，2.23 边界为 34）、`npm run build` 退出码 0（仅既存 chunk 体积提示）；③`openspec validate build-yumi-v2-order-fulfillment --strict` 输出 `Change 'build-yumi-v2-order-fulfillment' is valid`。
  - 失败归因（如实记录，无遗留失败）：首轮全量出现 1 处失败 `ProductSnapshotMatrixTest.sameTierReferenceKeepsSnapshotEvenIfGlobalChanged`（`expected 2.5000 but was 3.0000`），归因为**验收环境种子污染**（为浏览器验收把全局「包装提成默认」设为 0.5，而该用例隐含假定为 0）+ **测试未自带基线**；已在 `@BeforeEach` 固定该值为 0 后复跑全绿。非业务代码缺陷。**运维口径已记录**：后端全量测试与验收库是同一个 `yumi_v2_test`，须先跑门禁再铺验收数据（本批次已按此重排）。
  - 文档与规格同步（逐项）：①`docs/architecture/static-data-and-product-fields-design.md`——状态由“待评审”改为“已评审并实施（2026-09-24）”；§1 决策 2 由“商品侧彻底剔除缝边”改为“商品只提供默认值”；§3.1 缝边种类引用方补商品默认值；§3.2 商品字段表把“缝边整体移除”拆为“移除缝边数量/成本 + 新增默认缝边剪袋类型/缝边价格”；§3.4 标题去掉“本轮只定口径，不实现”并写明商品默认值、缝边剪袋变体与删除守卫；§4 `products` 行补缝边默认值四列、种子补 6 分钟档；§5 商品入参/出参补 `seamTypeId`/`seamFee`/`clearSeamType`/`seamBudget`；§6 页面变更补默认缝边剪袋类型下拉、缝边价格输入与面板缝边剪袋变体分组；§10 D 项补字段；§12 把“仍需执行的一项”改为“已执行的一项（任务 2.24 落地）”并补缝边默认值落地口径。②`docs/architecture/formula-catalog.md`——§3 新增 `FP-PROD-20`（缝边剪袋变体总成本）与 `FP-PROD-21`（缝边剪袋变体参考售价）并说明与废弃的 09/10 的区别；§4 补代码位置与测试编号；§2 追加说明（2.13 盘点快照保留，缝边时长行已随 2.23 移除，`V6` 为 2.23 追加）。③`docs/architecture/database-design.md`——§3 `products` 描述补缝边默认值与“不保存缝边数量/成本、成本按不缝边剪袋口径、缝边剪袋变体读时派生”；§15 迁移口径补“`packaging_tiers` 预置 6 分钟档、`products` 新增缝边默认值四列”与 `V6` 说明。④`specs/master-data-management/spec.md`——Requirement“静态数据…”补“包装档位 SHALL 预置一个 6 分钟档位”，Scenario“被引用的条目不能删除”补缝边种类被商品默认缝边剪袋类型引用，新增 Scenario“包装档位预置 6 分钟档位”；Requirement“商品字段…”新增 Scenario“缝边剪袋变体预算按种类成本单价单列”与“编辑时清空或更换默认缝边剪袋类型”。⑤`specs/order-lifecycle/spec.md`——缝边种类删除守卫由“被订单引用”改为“被订单明细行或商品默认缝边剪袋类型引用”。⑥`design.md` §5.2——删除守卫表述同步。⑦`docs/architecture/system-architecture.md`——本批次无模块/依赖变化（未新增模块或表归属），**不适用**，无需改动。⑧架构源图 `docs/architecture/system-architecture.dsl`（C4 容器级、不含模块清单）**不适用**。
  - 重新验收商品页并重签 2.12/2.19 视觉清单：原签字因商品字段变化作废，**重签清单合并在 2.24 的 `humanVisualConclusion`（8 项，含字段增减、只读展示、两套预算、四状态与警示条覆盖）**，本任务不重复列清单；2.12 的“白底卡片/紧凑筛选/表格自适应/金额 4 位小数字符串”与 2.19 的“四状态、失败不显示假零值、保存覆盖预估”各项均在新清单中逐条对应并保留。
    humanVisualConclusion:
      status: confirmed
      checklist:
        - "商品页重签清单见 2.24（8 项，含 2.12/2.19 的商品页视觉项）"
        - "设置页静态数据重签清单见 2.25（5 项）"
      confirmedBy: chen
      confirmedOn: 2026-09-24
      conclusion: "用户于 2026-09-24 确认本批次人工验收：2.24 商品页 8 项 + 2.25 设置页 5 项，并据此完成 2.12/2.19 商品页视觉清单的重签；证据分见各任务条目。"
  - **术语统一（2026-09-24，用户纠正：不是「封边」，是「缝边剪袋」）**：把本批次引入的错误写法「封边」与既有工序名「缝边裁剪」统一为「**缝边剪袋**」（与员工工种 `SEAM_CUTTING` 的显示名一致），裸词「缝边」保持不动（用户追加口径原文即为「缝边价格」）。按用户确认的粒度**只改文案，不动数据库列名与接口字段名**（仍为 `seam_type_id`/`seamTypeId`/`seamFee`/`seamBudget`）。替换范围 32 个文件：后端主代码 9、后端测试 3、迁移注释 2、前端 4、`docs` 6、`openspec` 8；替换后 `grep -rn "封边\|缝边裁剪"` 在 `backend/src`、`frontend/src`、`docs`、`openspec` 下命中 **0**。同时把 V5 种子的工种名改为「缝边剪袋」（空库重建后员工页与静态数据弹窗均显示新名）。
  - **术语替换引发的必要重建（如实记录）**：改写迁移文件（含注释）会改变 Flyway 校验和，第二次全量门禁因此报 `Migration checksum mismatch for migration version 4/5`（应用值 vs 本地值不一致）→ 上下文加载失败。按未上线阶段的既定口径处理：停后端、清空 `yumi_v2_test` 全部表、由 Flyway 从空库重跑 V1→V6（新校验和落库），复跑后端全量 **161 测试 0 失败 BUILD SUCCESS**。口径：**开发期任何迁移文案改动都必须清库重建后再跑门禁**，不得用 `flyway repair` 绕过校验。
  - **门禁期间修复的第三处缺陷（设置页控制台报错）**：`/settings` 静态数据条目弹窗使用 `destroyOnHidden` 卸载重建，但「编辑」与「新建条目」在弹窗关闭（Form 已卸载）状态下调用 `itemForm.setFieldsValue`/`resetFields`，antd 6 抛出 `Warning: Instance created by useForm is not connected to any Form element`（`console.error` 级），使 2.25 原证据里的“控制台无 error”失真。改为把条目初值存入 `itemDraft`，在弹窗打开后由 `useEffect` 注入（`resetFields` + 条件 `setFieldsValue`），关闭时不再触碰表单。复测：编辑条目回填 `标准缝边`/`1.2500`、新建条目为空、控制台仅剩 `[vite] connected`、React DevTools 与既有 react-router `HydrateFallback` 提示，无 error。2.25 的浏览器证据已按本轮读数重写。
  - 术语统一后的最终读数（2026-09-24 复验，正式路由）：商品编辑页表单标签「默认缝边剪袋类型」（提示「订单新建明细时的默认值，订单可改；留空＝默认不缝边剪袋」）、「缝边价格（元/件）」；面板行「单件总成本（不缝边剪袋）」「参考售价（不缝边剪袋，成本 ÷ 0.7）」「缝边剪袋变体（含缝边成本，单件）」「缝边剪袋变体总成本」「缝边剪袋变体参考售价」；员工页列表与下拉显示「缝边剪袋」；设置页静态数据「员工工种」弹窗 4 行（`SEAM_CUTTING | 缝边剪袋 | 启用`）。数值与术语无关，仍为不缝边剪袋 `18.1200`/`25.8857`、缝边剪袋变体 `19.3700`/`27.6714`。
  - 阶段二收口结论：2.21–2.27 全部实现与自动化门禁完成；`2.24`/`2.25`/`2.26`/`2.27` 已按全局完成定义第 1–4、6 条留证；第 5 条人工项待用户签字。**未完成的独立项**：2.20 只读公式说明的浏览器交互与签字（本批次未触碰，保持暂停），Electron 复用页实操（仅由 `electron/shell-guard.test.ts` 与共享 renderer/API 保证）。

## 3. 阶段三：订单草稿、确认、变更与共同数量（依赖阶段二，含 2.13–2.27）

- [x] 3.1 编写 Flyway 迁移创建 `orders/order_items/order_confirmation_snapshots/order_item_snapshots/order_change_orders/order_change_items/fulfillment_entries/order_item_fulfillment_balances`，落实 `@Version`、Q/E 检查、来源唯一消费和索引；参考 `docs/architecture/database-design.md` 第 5-6、13-14 节。
  - 证据：Requirement/Scenario：`order-lifecycle` Requirement“订单确认必须冻结需求和流程”的 Scenario“确认失败回滚”、Requirement“工序必须共享订单明细数量”、Requirement“缝边必须作为订单明细行的定制服务”的 Scenario“缝边数量不超过明细数量”。正式文档：`docs/architecture/order-module-design.md` §2/§3（列级设计，评审稿）、`docs/architecture/database-design.md` §5–6/§12–15、`docs/architecture/domain-and-quantity-model.md` §5/§14。文件：新增 `backend/src/main/resources/db/migration/V7__orders.sql`、`backend/src/test/java/com/yumi/OrderMigrationTest.java`。Flyway 版本：`V7__orders.sql`（新增 8 表；不改 V1–V6）。API：不适用（本任务只建表）。表：`orders`、`order_items`、`order_confirmation_snapshots`、`order_item_snapshots`、`order_change_orders`、`order_change_items`、`fulfillment_entries`、`order_item_fulfillment_balances`。事务拥有者/锁定对象/幂等键：不适用（本任务只建表；订单编号沿用 `SequenceAllocator` 的事务内行锁，`orders`/`order_items` 用 `@Version` 乐观锁）。
  - 关键结构：`orders.order_no CHAR(7)` 唯一 + 状态/客户/下单日期索引 + 金额八列（含 `goods_cost_amount`/`seam_cost_amount` 分列）；`order_items` 唯一键 `(order_id, line_no)` + `ck_order_items_seam_within_quantity CHECK (seam_quantity <= quantity)`；`order_confirmation_snapshots.order_id` 唯一、`order_item_snapshots.order_item_id` 唯一；`order_change_orders.change_no CHAR(7)` 唯一；`order_change_items` 结构化前后值 + `ck_order_change_items_target`（`ADD` 必须无 `order_item_id`，`UPDATE`/`REMOVE` 必须有）；`fulfillment_entries` 唯一键 `(source_type, source_id, source_line_id, node, direction)` 落实来源唯一消费（`source_line_id` 用 `0` 表示无明细来源，避免 NULL 使唯一键失效）；`order_item_fulfillment_balances.order_item_id` 唯一；`orders` 上 `ck_orders_discount_within_goods CHECK (discount_amount <= goods_amount + seam_amount)`。为阶段四–九预留的列（工序流入/计划占用/可发货/累计发货/成品余量、`closed_at/by`）只建列不写入。
  - RED/GREEN 口径（如实记录）：本任务为**建表型**，按全局完成定义第 2 条以“先写断言、跑出失败”取证——首次运行 `-Dtest=OrderMigrationTest` 退出码非 0，6 用例 6 errors，原文 `BadSqlGrammar ... DELETE FROM order_change_items WHERE order_id IN ...`（测试清理语句写错列名，暴露 `order_change_items` 无 `order_id`）；修正清理语句后仍 3 failures，原文 `Expecting actual throwable to be an instance of DataIntegrityViolationException but was UncategorizedSQLException ... error code [3819]; Check constraint 'ck_order_items_seam_within_quantity' is violated`（MySQL CHECK 违反经 HY000 译成 `UncategorizedSQLException`）；断言改判 `DataAccessException` 后同命令退出码 0，**6/6**。
  - 关键断言：八张表存在；`order_no CHAR(7)`、`goods_amount DECIMAL(19,4)`、`version BIGINT UNSIGNED`、`expected_delivery_date DATE` 可空、`quantity INT UNSIGNED`、`fulfillment_entries.source_line_id` 非空；七个唯一键齐备；`orders`/`order_items`/`fulfillment_entries` 关键索引与外键齐备；`seam_quantity > quantity` 被 `ck_order_items_seam_within_quantity` 拒绝；`discount_amount > goods_amount + seam_amount` 被 `ck_orders_discount_within_goods` 拒绝；`ADD` 行携带 `order_item_id`、`UPDATE` 行缺 `order_item_id` 均被 `ck_order_change_items_target` 拒绝；同一 `(source_type, source_id, source_line_id, node, direction)` 二次入账被 `uk_fulfillment_entries_source` 拒绝，而换方向/换节点/`source_line_id = 0` 的重复仍被拒绝。
  - 阶段门禁：`YUMI_DB_PASSWORD=<钥匙串> ./mvnw test` 退出码 0，**186 测试 0 失败 0 错误**（含 Hibernate `ddl-auto: validate` 与 Modulith 边界）。
  - 人工证据：不适用（本任务只建表，无页面/打印/Electron 变更）。
- [x] 3.2 实现订单编号 `YM00001`、草稿创建/查询/编辑 API：`GET/POST /api/orders`、`GET/PATCH /api/orders/{id}`；覆盖下单日期、可空交期、收货、整体备注、明细备注和仅草稿可编辑。
  - 证据：Requirement/Scenario：`order-lifecycle` Requirement“订单确认必须冻结需求和流程”的 Scenario“确认订单”（草稿阶段可编辑）、Requirement“缝边必须作为订单明细行的定制服务”的 Scenario“选择缝边并填写数量与收费”（默认值取自商品）。正式文档：`docs/architecture/order-module-design.md` §2/§6/§7、`design.md` §6（订单行）、`docs/architecture/database-design.md` §12（编号）。文件：新增 `backend/src/main/java/com/yumi/orders/order/{OrderController,OrderService,OrderViews,CreateOrderRequest,UpdateOrderRequest,OrderItemRequest}.java`、`orders/order/internal/{OrderRow,OrderItemRow,OrderRepository,OrderReference}.java`；测试 `backend/src/test/java/com/yumi/OrderApiTest.java`。Flyway：不新增（沿用 `V7`）。API：`POST /api/orders`、`GET /api/orders`、`GET /api/orders/{id}`、`PATCH /api/orders/{id}`；错误码 `VALIDATION_INVALID`（含逐字段 `fieldErrors`）、`ORDER_NOT_FOUND`、`STATE_NOT_EDITABLE`、`CONFLICT_VERSION`、`AUTH_REQUIRED`。表：`orders`、`order_items`、`number_sequences`。事务拥有者：`OrderService.create/update` 的 `@Transactional`；锁定对象：`number_sequences` 行锁（`FOR UPDATE`）+ `orders.version` 乐观锁；幂等键：写命令要求 `Idempotency-Key`，由既有幂等过滤器存储与重放。
  - 编号：`YM` + 5 位（`SequenceAllocator.format`），`sequence_key = orders`；单测断言 `orderNo` 匹配 `YM\d{5}` 且连续两次创建递增（既有 `SequenceAllocatorTest` 覆盖锁与竞争）。
  - 契约要点：请求只提交 `customerId`/`orderDate`/`expectedDeliveryDate`/收货四项/`note`/`discountAmount`/`items[{productId,quantity,seamQuantity,unitPrice,seamTypeId,seamFee,note}]`；商品识别信息（编号/名称）与单件成本一律服务端读取，`unitPrice` 未传取商品 `sale_price`，缝边种类与收费未传取商品的「默认缝边剪袋类型 + 缝边价格」；收货四项未传取客户默认收货信息；`PATCH` 未传字段保持原值，`items` 传非空列表时整体替换草稿明细（草稿阶段允许增删行），`clearExpectedDeliveryDate=true` 可清空交期。
  - RED/GREEN 口径（如实记录）：实现先于用例，按全局完成定义第 2 条不伪造 RED。首跑 `-Dtest=OrderApiTest` 退出码非 0，7 用例 7 failures，原文 `Status expected:<200> but was:<401>`（测试 `@BeforeEach` 先建管理员再调清理，把自己刚建的管理员删掉）；调整顺序后仍 1 failure，`rejectsInvalidItemsDeliveryAndDiscount: Status expected:<400> but was:<500>`（超范围优惠经 `OrderPricing.totals` 抛 `IllegalArgumentException` 未被映射为字段级 400）；补映射后同命令退出码 0，**7/7**。
  - 关键断言：①创建草稿返回 `DRAFT`/`version 0`/`YM\d{5}`，明细 Q=10、E=4，商品金额 `250.0000`、缝边收费 `8.0000`、商品成本 `181.2000`、缝边成本 `5.0000`，订单应收 `248.0000`、成本 `186.2000`、利润 `61.8000`，库内 `orders`/`order_items` 逐列一致且 `line_no=1`；②不传收货与缝边字段时取客户默认收货信息与商品默认缝边种类（`seamUnitCost 1.2500`、`seamFee 2.0000`），`E=0` 时 `seamTypeId` 为 `null` 且缝边金额与成本为 `0.0000`；③数量为 0、`E>Q`、商品不存在、明细为空、客户不存在、交期早于下单日期、优惠超过商品金额加缝边收费均返回 400 且带对应 `fieldErrors`，且**校验失败不落库**（订单数为 0）；④停用商品被拒绝；⑤编辑草稿整体替换明细后 `lineNo` 重排为 1/2、金额重算（商品金额 `45.0000`、缝边收费 `3.0000`、应收 `48.0000`、成本 `55.6100`、利润 `-7.6100`），不传 `items` 时明细与金额保持不变；⑥旧版本返回 409 `CONFLICT_VERSION`，已确认订单返回 409 `STATE_NOT_EDITABLE`；⑦列表按状态/客户/下单日期区间筛选，`GET /api/orders/999999` 返回 404 `ORDER_NOT_FOUND`。
  - 阶段门禁：`./mvnw test` 退出码 0，**186 测试 0 失败**。
  - 人工证据：不适用（前端订单页在 3.11–3.13 实现并单独验收）。
- [x] 3.3 实现订单明细 Q/E、商品/缝边成交价、整单优惠和全部金额/成本/利润公式；服务端使用 `BigDecimal`，JSON 金额均为四位小数字符串，优惠不得为负或超过原始金额。
  - 证据：Requirement/Scenario：`order-lifecycle` Requirement“缝边必须作为订单明细行的定制服务”（缝边成本计入订单成本、利润=应收−商品成本−缝边成本）、Requirement“订单确认必须冻结需求和流程”。正式文档：`docs/architecture/order-module-design.md` §4（口径已评审，用户 2026-09-24 确认优惠作用于整单应收）、`docs/architecture/formula-catalog.md`（FP-ORDER-01..09）、`docs/architecture/domain-and-quantity-model.md` §5。文件：新增 `backend/src/main/java/com/yumi/calculation/order/{OrderPricing,package-info}.java`（`@NamedInterface`）、`backend/src/test/java/com/yumi/OrderPricingBaselineTest.java`；调用方 `OrderService`。API/表/迁移/幂等键：不适用（纯计算模块，无端点、无表、无 Flyway 版本）。事务/锁定：不适用（无状态纯函数）。
  - 公式（FP-ORDER-01..09）：明细商品金额 `unit_price × quantity`、明细缝边收费 `seam_fee × seam_quantity`、明细商品成本 `unit_cost × quantity`、明细缝边成本 `seam_unit_cost × seam_quantity`（均 scale4 HALF_UP，只对乘积舍入）；订单商品金额/缝边收费/商品成本/缝边成本为明细求和；订单应收 `= 商品金额 + 缝边收费 − 整单优惠`；订单总成本 `= 商品成本 + 缝边成本`；订单利润 `= 应收 − 总成本`（允许为负）。优惠只作用于应收、不进入成本；优惠为负或超过「商品金额 + 缝边收费」时由计算模块拒绝。
  - RED/GREEN 口径（如实记录）：本任务先写实现后补用例，不伪造 RED；`-Dtest=OrderPricingBaselineTest` 首跑退出码 0，**6/6**（纯函数，无环境依赖）。为证明用例有效做了变异检查：把 `OrderPricing.item` 的乘积舍入改为对乘数先舍入（`0.12345×3` 由 `0.3704` 变 `0.3705`），`itemAmountRoundsProductNotMultiplicand` 立即失败；恢复后 6/6。
  - 关键断言（人工核算的字符串逐位比对）：`25.0000×10=250.0000`、`2.0000×4=8.0000`、`18.1200×10=181.2000`、`1.2500×4=5.0000`；`0.12345×3=0.3704`（区别于先舍入乘数的 `0.3705`）；订单应收 `250+8−10=248.0000`、成本 `186.2000`、利润 `61.8000`；多明细求和 `25.0000`/`1.5000`/`26.5000`/`26.2500`/`0.2500`；优惠等于原始金额时应收 `0.0000`；优惠 `12.0001` 与 `-0.0001` 均抛 `IllegalArgumentException`；空订单各项为 `0.0000`。
  - 阶段门禁：`./mvnw test` 退出码 0，**186 测试 0 失败**（Modulith 边界校验 `calculation` 无出边通过）。
  - 人工证据：不适用。
- [x] 3.4 实现 `POST /api/orders/{id}/confirm` 的确认校验和单事务快照：客户、收货、商品、图片、价格、成本、数量、缝边、优惠、备注、流程、确认人/时间；确认不自动建生产计划。
  - 证据：Requirement/Scenario：`order-lifecycle` Requirement“订单确认必须冻结需求和流程”的两个 Scenario（确认订单、确认失败回滚）、Requirement“工序必须共享订单明细数量”的 Scenario“缝边分流”（流程快照按 E 分流）。正式文档：`docs/architecture/order-module-design.md` §3.3/§3.4/§5/§6/§7、`docs/architecture/database-design.md` §5–6、`docs/architecture/domain-and-quantity-model.md` §5/§9。文件：`orders/order/OrderService.confirm`、新增 `orders/order/internal/OrderSnapshotRepository.java`、`orders/fulfillment/internal/FulfillmentRepository.java`、`OrderController.confirm`。API：`POST /api/orders/{id}/confirm`；错误码 `STATE_NOT_CONFIRMABLE`、`VALIDATION_INVALID`（含 `items[n].productId`/`items[n].unitCost`/`items[n].seamQuantity`/`expectedDeliveryDate`/`amounts` 字段定位）、`CONFLICT_VERSION`、`ORDER_NOT_FOUND`。表：`order_confirmation_snapshots`、`order_item_snapshots`、`fulfillment_entries`、`order_item_fulfillment_balances`、`orders`。事务拥有者：`OrderService.confirm` 的单个 `@Transactional`（快照、履约事实、投影与状态变更同事务）；锁定对象：`orders.version` 乐观锁（`markConfirmed` 带版本条件，失配抛 `CONFLICT_VERSION`）；幂等键：必须 `Idempotency-Key`，重复键由过滤器重放首次结果。
  - 确认校验（单事务内重验）：订单必须为 `DRAFT`；至少一条明细；交期不早于下单日期；每条明细的商品仍存在且启用；`E ≤ Q`；**商品单件成本未漂移**（商品当前 `total_cost` 必须等于明细 `unit_cost`，否则提示「商品成本已变化，请重新保存草稿后再确认」，避免静默按新成本确认）；金额自洽（由明细重算的商品金额/缝边收费/应收/成本/利润必须与订单表头逐项一致，防部分写入与篡改）。
  - 快照与事实：订单级快照写客户（id/编号/名称/联系人/电话）、收货四项、八个金额、备注、`confirmed_by`（管理员用户名，与 `audit_logs` 口径一致）、`confirmed_at`；明细级快照写商品识别（编号/名称/说明/图片）、星级（id/名称/标准分钟）、数量 Q/E、价格与缝边参数（种类 id/名称/成本单价/收费单价）、成本（单件/商品/缝边）、**商品成本组成十项**、冻结流程文本（`E>0` 为「制作 → 捏毛装袋 → 缝边剪袋 → 可发货」，否则「制作 → 捏毛装袋 → 可发货」）、明细备注；随后写一条 `ORDER_DEMAND`/节点 `SHIPPABLE`/方向 `IN` 履约事实与投影行（`required_quantity = Q`），最后置订单 `CONFIRMED` 并递增版本。**不创建任何生产计划**。
  - RED/GREEN 口径（如实记录）：实现先于用例，不伪造 RED。首跑 `-Dtest=OrderConfirmationTest` 退出码非 0（ApplicationContext 加载失败：`Migration checksum mismatch for migration version 7`——本任务期间把 `V7` 的确认人列由 `BIGINT` 改为 `VARCHAR(100)`（订单模块不直读 `admin_accounts`，与 `audit_logs` 的 `admin_username` 口径一致），按未上线口径清库重建后 Flyway 重跑 V1→V7）；随后 1 failure，原文 `expected: 5 but was: 5L`（MySQL `INT UNSIGNED` 经 `queryForMap` 返回 `Long`），改用 `Number.intValue()` 后退出码 0，**6/6**。
  - 关键断言：确认后订单 `CONFIRMED`、`version 1`、应收 `248.0000`；订单级快照客户编号/名称、收货人/区域、八个金额、`confirmed_by = order-confirm-admin`、`confirmed_at` 非空；明细级快照商品编号/名称/说明、星级名称与标准分钟、Q=10/E=4、`unit_cost 18.1200`、`glue_cost 3.2400`、`material_cost 9.7200`、`labor_cost 7.5000`、`total_cost 18.1200`、流程文本含「缝边剪袋」；履约事实恰好 1 条（`ORDER_DEMAND`/`SHIPPABLE`/`IN`/数量 10/`source_type ORDER`）且投影 `required_quantity = 10`；不存在任何 `PRODUCTION%` 事实（不自动建计划）。
  - 阶段门禁：`./mvnw test` 退出码 0，**186 测试 0 失败**。
  - 人工证据：不适用（确认操作页在 3.11/3.13 实现并单独验收）。
- [x] 3.5 编写确认原子性测试：任一商品失效、Q/E 非法、交期非法、金额不一致或并发版本冲突时整笔回滚；重复幂等键返回同一订单，不重复生成快照/履约来源。
  - 证据：Requirement/Scenario：`order-lifecycle` Requirement“订单确认必须冻结需求和流程”的 Scenario“确认失败回滚”（任一确认校验失败时订单仍为草稿且不留下部分快照、计划、履约或库存事实）。正式文档：`docs/architecture/order-module-design.md` §6/§9。文件：`backend/src/test/java/com/yumi/OrderConfirmationTest.java`（6 用例）。API/表：沿用 3.4；Flyway：不新增。事务/锁定/幂等键：断言点即上述事务与幂等语义。
  - 关键断言：①商品失效（确认前停用商品）→ 400 `VALIDATION_INVALID` + `items[0].productId`，且**订单仍 `DRAFT`、`version` 仍 0、四张表（订单级快照/明细快照/履约事实/投影）行数全为 0**；②金额不一致（直接改库把应收改成 `999.0000`）→ 400 + `amounts` 字段错误并整笔回滚；③交期非法（直接改库把交期设为早于下单日期）→ 400 + `expectedDeliveryDate` 并整笔回滚；④重复幂等键（同一 `Idempotency-Key` 连续两次确认）→ 两次均 200 且返回同一订单编号，`order_confirmation_snapshots`/`order_item_snapshots`/`fulfillment_entries` 各仍为 1 行；⑤并发确认（两线程同时确认同一订单）→ 恰好 1 次 200，快照与履约事实各 1 行，订单为 `CONFIRMED`。
  - 未覆盖项（如实记录）：**Q/E 非法在确认路径不可通过 HTTP 触发**——`V7` 的 `ck_order_items_seam_within_quantity` 与保存期校验双重拒绝，非法行无法落库；确认路径的 `E ≤ Q` 重验作为防御性检查保留，其行为由 `OrderMigrationTest#rejectsSeamQuantityAboveQuantity`（数据库 CHECK）覆盖。**并发版本冲突（`CONFLICT_VERSION`）**在确认路径由 `markConfirmed` 的版本条件保证，本测试以“并发确认只成功一次”间接覆盖；该分支的直达单测留待 3.14 汇总时补充（或用两阶段人工插入版本漂移模拟）。
  - 阶段门禁：`./mvnw test` 退出码 0，**186 测试 0 失败 0 错误**（161 + 本批次新增 25）。
  - 人工证据：不适用。
- [x] 3.6 实现共同数量初始化和 `GET /api/orders/{id}/fulfillment`：制作需求 Q、捏毛装袋需求 Q、缝边剪袋需求 E、最终需求 Q，禁止 Q+Q+E；派生排产/生产/需求处理/发货进度而非手工状态。
  - 证据：Requirement/Scenario：`order-lifecycle` Requirement“工序必须共享订单明细数量”的 Scenario“缝边分流”“生产报废不减少客户需求”。正式文档：`docs/architecture/order-module-design.md` §5、`docs/architecture/domain-and-quantity-model.md` §5/§9/§13、`design.md` §6（履约视图行）。文件：新增 `backend/src/main/java/com/yumi/orders/fulfillment/{OrderStatuses,FulfillmentViews,FulfillmentService}.java`，`orders/fulfillment/internal/FulfillmentRepository`（`findBalances`/`summarizeByOrder`）；`OrderController` 新增 `GET /api/orders/{id}/fulfillment`；`OrderViews.OrderDetail`/`OrderSummary` 增加 `derived`。API：`GET /api/orders/{id}/fulfillment`；错误码 `ORDER_NOT_FOUND`、`AUTH_REQUIRED`。表：`order_item_fulfillment_balances`、`order_items`、`fulfillment_entries`。事务/锁定/幂等键：不适用（只读查询，不要求幂等键、不产生业务写入）。
  - 共同数量初始化：确认时（3.4）已写 `required_quantity = Q` 与一条 `ORDER_DEMAND`/`SHIPPABLE`/`IN` 事实作为需求基线；本任务只读地按工序分流展示——`不缝边需求 = Q − E`、`制作共同需求 = Q`、`捏毛装袋共同需求 = Q`、`缝边剪袋需求 = E`、`最终交付需求 = Q`。
  - 关键断言（Q=10、E=4）：`noSeamRequired=6`、`makingRequired=10`、`packingRequired=10`、`seamRequired=4`、`finalRequired=10`、`undelivered=10`；**`finalRequired` 不等于 `makingRequired + packingRequired + seamRequired`**（显式断言工序数量不得相加为订单数量）；阶段三工序流入/可发货/累计发货恒为 0，派生状态按事实判定为 `未排产`/`等待上游`/`未开始`/`仍有待履约`/`未发货`，不用 0 伪造进度。
  - 派生规则（`OrderStatuses.derive`，单一实现，明细与订单级共用）：排产状态＝有计划占用则 `已排产`；执行条件＝`制作有效流入 − 已核验处理 > 0` 则 `可执行`；生产进度＝`已核验处理 = 0` 时（有计划则 `生产中`，否则 `未开始`），否则未达需求 `部分完成`、达到 `生产处理完成`；需求处理状态与发货进度只看 `累计有效发货` 与当前有效需求。订单级＝明细数量求和后套用同一规则（`summarizeByOrder` 一次汇总查询，避免列表 N+1）。
  - 阶段门禁：`./mvnw test` 退出码 0，**194 测试 0 失败**。
  - 人工证据：不适用（订单详情页在 3.12 实现并单独验收）。
- [x] 3.7 实现订单变更草稿 API `POST /api/orders/{id}/change-orders`、查询/编辑和 `POST /api/order-changes/{id}/confirm`，覆盖明细增删、数量/价格/缝边/优惠/交期/收货/备注，保留前后值和原因。
  - 证据：Requirement/Scenario：`order-lifecycle` Requirement“已确认订单变化必须使用订单变更”。正式文档：`docs/architecture/order-module-design.md` §3.5/§3.6/§6/§7、`design.md` §6（变更行）。文件：新增 `backend/src/main/java/com/yumi/orders/change/{OrderChangeController,OrderChangeService,OrderChangeViews,CreateChangeOrderRequest,OrderChangeItemRequest}.java`、`orders/change/internal/{OrderChangeRow,OrderChangeItemRow,OrderChangeRepository}.java`；抽出 `orders/order/internal/OrderItemResolver`（草稿明细与变更新增行共用同一解析入口，`OrderService` 改为委托）；`OrderRepository` 新增 `updateItem`/`findItemId`，`OrderSnapshotRepository.applyChangeHeader` 改为回写表头 + 全部八个金额列。API：`POST /api/orders/{id}/change-orders`（201）、`GET /api/orders/{id}/change-orders`、`GET/PATCH /api/order-changes/{id}`、`POST /api/order-changes/{id}/confirm`；错误码 `STATE_NOT_CHANGEABLE`、`VALIDATION_INVALID`、`CONFLICT_VERSION`、`NOT_FOUND`、`ORDER_NOT_FOUND`。表：`order_change_orders`、`order_change_items`、`order_items`、`fulfillment_entries`、`order_item_fulfillment_balances`、`orders`。事务拥有者：`OrderChangeService.confirm` 的单个 `@Transactional`；锁定对象：`orders.version` 与 `order_change_orders.version` 乐观锁；幂等键：写命令必须 `Idempotency-Key`。
  - 契约：变更草稿入参三条路径共用一条记录——新增行（`orderItemId` 空、`productId` 必填）、改单行（`orderItemId` 非空、只提交要改的字段，未提交沿用当前有效值）、移除行（`orderItemId` 非空且 `remove=true`）；表头字段未传沿用订单当前值；`items` 非空时整体替换变更草稿明细；同一订单同时只允许一个未确认变更草稿（否则 409 `STATE_NOT_CHANGEABLE`）；变更单编号 `CO` + 5 位，创建草稿时分配。
  - 结构化前后值：改单/移除行落库 `before_*` 与 `after_*`（数量、缝边数量、成交单价、缝边种类、缝边收费、备注），新增行只落 `after_*`；**移除＝数量归零**，保留行、快照与履约历史，不物理删除；确认时按数量差写 `ORDER_CHANGE` 事实（增 `IN`、减 `OUT`），并同步 `required_quantity` 与订单金额。
  - 关键断言：变更草稿编号 `CO\d{5}`、状态 `DRAFT`、两条明细分别为 `UPDATE`（`beforeQuantity 10`/`afterQuantity 8`、`beforeSeamQuantity 4`/`afterSeamQuantity 2`、`beforeUnitPrice 25.0000`）与 `ADD`（`orderItemId` 为空、`afterQuantity 3`）；草稿阶段订单本身未被改动；确认后原行 `quantity=8`/`seam_quantity=2`、明细数 2 行、`goodsAmount 260.0000`（25×8+20×3）、`seamAmount 4.0000`、`receivableAmount 259.0000`（264−5）；`ORDER_CHANGE` 事实两条（`OUT 2` 与 `IN 3`）；需求投影同步为 8；订单主状态仍 `CONFIRMED`；草稿订单不可变更、已确认变更单不可重复确认。
  - 阶段门禁：`./mvnw test` 退出码 0，**194 测试 0 失败**。
  - 人工证据：不适用（变更确认页在 3.13 实现并单独验收）。
- [x] 3.8 实现减单不变量：新有效数量不得低于累计有效发货；超出在制/合格数量必须逐项提交“继续完成转成品余量”或“立即报废”的数量和原因，确认后原子更新需求、金额和履约事实。
  - 证据：Requirement/Scenario：`order-lifecycle` Requirement“已确认订单变化必须使用订单变更”的 Scenario“减单低于已发货被拒绝”“减单处理超出数量”。正式文档：`docs/architecture/order-module-design.md` §3.6/§6、`docs/architecture/domain-and-quantity-model.md` §10/§14。文件：`OrderChangeService.requireReductionInvariants`/`hasDisposition`、`order_change_items` 的 `surplus_disposition`/`surplus_quantity`/`surplus_reason` 三列。API：变更确认；错误码 `QUANTITY_BELOW_SHIPPED`、`QUANTITY_REQUIRES_DISPOSITION`（均 409，带 `fieldErrors` 定位到具体明细）。事务/幂等键：与 3.7 同一事务与幂等语义。
  - 口径：`累计有效发货` 取 `order_item_fulfillment_balances.shipped_quantity`；`在制/合格` 取 `making_inflow + packing_inflow + seam_inflow`（各工序有效流入，即已在制或已合格的数量）；超出量 `= max(在制/合格 − 新有效数量, 0)`。校验在确认事务内逐项执行，任一不满足即整笔回滚（订单、明细、事实、投影均不变）。
  - 关键断言：①把 `shipped_quantity` 置 7 后减到 5 → 409 `QUANTITY_BELOW_SHIPPED` 且明细数量仍为 10；②把 `packing_inflow` 置 9 后减到 6（超出 3）而未提交处理方案 → 409 `QUANTITY_REQUIRES_DISPOSITION` + `items[0].surplusDisposition` 字段定位，明细数量仍为 10；③改同一张草稿补 `FINISH_TO_SURPLUS`/`surplusQuantity 3`/原因后确认成功，三列结构化落库，明细数量变为 6。
  - **阶段边界（如实记录，需用户确认是否认可）**：本任务把处理方案落库为**结构化决策**并随变更确认生效（需求、金额、`ORDER_CHANGE` 履约事实同事务更新），但对在制/合格数量的**执行事实**（转成品余量、报废）未在本阶段伪造——在制/合格数量由阶段五生产核验产生，处置的执行语义（从哪个节点流出、是否计入可发货）属阶段五，届时按本方案执行并写入 `FINISHED_SURPLUS`/报废事实。理由：阶段三在制数量恒为 0，此处若臆造节点与方向会在阶段五返工。
  - 阶段门禁：`./mvnw test` 退出码 0，**194 测试 0 失败**。
  - 人工证据：不适用。
- [x] 3.9 实现订单取消 `POST /api/orders/{id}/cancel`：草稿可直接取消；已确认且无任何履约/款项事实时可取消；有事实返回 `STATE_CANCEL_NOT_ALLOWED` 并引导订单变更，不删除历史。
  - 证据：Requirement/Scenario：`order-lifecycle` Requirement“订单取消必须区分草稿和已确认”的两个 Scenario（无事实的已确认订单取消、有事实订单直接取消被拒绝）。正式文档：`docs/architecture/order-module-design.md` §6/§7、`docs/architecture/domain-and-quantity-model.md` §14（已确认事实不物理删除）。文件：`OrderService.cancel`、`OrderSnapshotRepository.markCancelled`、`FulfillmentRepository.countExecutionFacts`、`OrderController.cancel`（`CancelRequest{reason}`）。API：`POST /api/orders/{id}/cancel`；错误码 `STATE_CANCEL_NOT_ALLOWED`（409）、`ORDER_NOT_FOUND`。表：`orders`（`status`/`cancelled_at`/`cancelled_by`/`cancel_reason`）、`fulfillment_entries`。事务拥有者：`OrderService.cancel` 的 `@Transactional`；锁定对象：无新增（单行状态更新）；幂等键：必须 `Idempotency-Key`。
  - 口径：`DRAFT` 可直接取消；`CONFIRMED` 仅在**无执行类履约事实**时可取消；`CANCELLED`/`CLOSED` 等终态返回 `STATE_CANCEL_NOT_ALLOWED`（不可重复取消、不可重开）。执行类事实＝`entry_type NOT IN ('ORDER_DEMAND','ORDER_CHANGE')`——确认时写入的需求基线与变更需求调整**不算**执行事实，领用/生产/返工/重做/余量/发货才阻塞取消。**款项事实**列由阶段七建表后并入同一守卫（当前无收退款表，故只校验履约事实，已在代码注释与本节记录）。
  - 关键断言：草稿取消 → `CANCELLED`；无执行事实的已确认订单取消 → `CANCELLED`；插入一条 `INVENTORY_ALLOCATION` 事实后再取消 → 409 `STATE_CANCEL_NOT_ALLOWED`，订单仍 `CONFIRMED` 且两条履约事实（需求 + 领用）**一条未删**；已取消订单再次取消 → 409。
  - 阶段门禁：`./mvnw test` 退出码 0，**194 测试 0 失败**。
  - 人工证据：不适用。
- [x] 3.10 实现订单多维只读状态：主状态、排产状态、执行条件、生产进度、需求处理状态、发货进度；验证生产报废、计划创建或余量本身不自动完成客户需求。
  - 证据：Requirement/Scenario：`order-lifecycle` Requirement“订单详情必须区分只读事实与显式操作”（订单内多 Tab 展示派生状态）。正式文档：`docs/architecture/order-module-design.md` §5/§6、`docs/architecture/domain-and-quantity-model.md` §13/§14。文件：`orders/fulfillment/OrderStatuses.java`（唯一派生实现）、`FulfillmentService.derivedFor/derivedByOrder`、`OrderViews.OrderDetail`/`OrderSummary` 的 `derived` 字段。API：`GET /api/orders`、`GET /api/orders/{id}`、`GET /api/orders/{id}/fulfillment` 三处均返回派生状态。表：`order_item_fulfillment_balances`（投影列，阶段三只有 `required_quantity` 有值）；无手工状态列。
  - 六类状态：主状态为 `orders.status`（仅由命令转换）；排产状态、执行条件、生产进度、需求处理状态、发货进度全部由 `OrderStatuses.derive` 从当前有效需求、累计有效发货与工序投影派生，明细与订单级共用同一规则（订单级＝明细求和后套用），不提供手工下拉框改状态。
  - 关键断言：①模拟阶段五写入（`making_inflow=10`、`verified_processed=4`、`making_planned=2`）后，`排产状态=已排产`、`生产进度=部分完成`，而**需求处理状态仍为 `仍有待履约`、发货进度仍为 `未发货`**；②再加 `finished_surplus_quantity=3`（成品余量）后两者不变——证明生产报废、计划创建与余量本身都不自动完成客户需求；③订单详情与列表返回同一派生状态（列表按订单一次汇总查询）。
  - 阶段门禁：`./mvnw test` 退出码 0，**194 测试 0 失败**。
  - 人工证据：不适用（派生状态在 3.11/3.12 页面上展示，随 3.15 人工验收）。
- [x] 3.11 实现 `/orders` 列表和 `/orders/new` 步骤化全页工作区，步骤至少为客户/收货、商品明细与 Q/E、金额优惠、确认复核；展示服务端金额，禁止客户端覆盖。
  - 证据：Requirement/Scenario：`order-lifecycle` Requirement“订单详情必须区分只读事实与显式操作”（新建由明确入口进入独立操作状态）。正式文档：`docs/architecture/order-module-design.md` §7/§8、`design.md` §7（`/orders`、`/orders/new` 行）。文件：新增 `frontend/src/api/orders.ts`（订单/履约/变更的类型与 13 个封装函数）、`frontend/src/pages/orders/OrdersPage.tsx`、`frontend/src/pages/orders/OrderCreatePage.tsx`；`frontend/src/routes/index.tsx` 把 `/orders`、`/orders/new` 两个占位页替换为正式页。路由：正式 `/orders` 与 `/orders/new`（`ROUTE_PATHS` 仍 13 条，**未新增路由**；编辑既有草稿走 `/orders/new?orderId=`，属同一工作区）。
  - 列表：主状态 / 生产进度 / 发货进度**分列**展示（派生状态来自服务端事实），筛选为状态 + 客户 + 下单日期区间，操作列为「打开」与草稿行的「编辑草稿」，列表本身只读。
  - 步骤工作区：四步「客户与收货 → 商品明细与 Q/E → 金额与优惠 → 确认复核」；表头字段与明细由页面 state 持有（离开第 1 步后该 Form 会卸载，antd 会注销字段并丢值——自测发现的缺陷，见下）；选客户后自动带出客户默认收货信息，选商品后成交价预填商品销售单价（单价是输入项、不是派生金额）；金额只在**保存草稿后**由服务端返回并展示，页面不做任何客户端金额计算、不覆盖服务端结果。
  - 浏览器自测（2026-09-24，正式路由 `http://127.0.0.1:5190`，后端 18090，清库重建后空库起，登录本机合成账号 `admin`；结构化 DOM 探针驱动）：①列表列为「订单编号/客户/下单日期/交期/应收/主状态/生产进度/发货进度/操作」，有「新建订单」入口；②`/orders/new` 四个步骤标题正确；③选客户「验收-客户甲」后收货四项自动带出「张三/13800000000/华东/上海市浦东新区示例路 1 号」；④明细行选「P00001 验收-泰迪熊30cm」后成交价自动预填 `25.0000`，数量 Q=10、缝边数量 E=4 后缝边种类下拉由禁用变为可选，选「标准缝边 · 1.2500 元/件」；⑤点「保存并继续」后 URL 变为 `/orders/new?orderId=1`，右侧「金额汇总（服务端权威）」显示 商品金额 `250.0000`、缝边收费 `8.0000`、应收 `258.0000`、商品成本 `181.2000`、缝边成本 `5.0000`、总成本 `186.2000`、预计利润 `71.8000`，与手算逐项一致；⑥第 3 步填整单优惠 `10.0000` 后再保存，第 4 步复核显示「订单编号 YM00001，共 1 条明细」+ 明细表（Q=10/E=4/商品金额 `250.0000`/缝边收费 `8.0000`/商品成本 `181.2000`/缝边成本 `5.0000`）+ 应收合计 `248.0000`、成本合计 `186.2000`、预计利润 `61.8000`；⑦点「确认订单」后跳转 `/orders/1`，订单状态变为已确认。
  - 自测中发现并修复的真缺陷：**离开第 1 步后表头 Form 卸载，`form.getFieldsValue()` 取不到客户/日期**，导致第 2 步「保存并继续」静默不保存（无任何请求、无错误提示）。修复：表头值改由页面 `useState` 持有（第 1 步 Form 用 `initialValues` + `onValuesChange` 同步），整单优惠改为独立 state，`buildBody()` 从 state 取值；修复后保存与推进正常。
  - 门禁：`npm run typecheck` 退出码 0、`npm test` 退出码 0（6 文件 37 用例）、`npm run build` 退出码 0；`src/routes/paths.test.ts` 断言 `ROUTE_PATHS` 仍为 13 条通过。
  - 人工证据（与 3.12/3.13 同批签字）：
    humanVisualConclusion:
      status: confirmed
      checklist:
        - "订单列表把 主状态 / 生产进度 / 发货进度 分成三列展示，不合成一个状态"
        - "列表只有「打开」与草稿行的「编辑草稿」两个入口，查看动作不误作提交动作"
        - "新建订单是步骤化全页工作区，步骤为客户与收货 → 商品明细与 Q/E → 金额与优惠 → 确认复核，同屏只有一个步骤的表单"
        - "右侧金额汇总标注「服务端权威」，未保存时显示占位说明，保存后显示服务端返回的金额，页面不出现客户端算出的金额"
        - "选客户自动带出默认收货信息、选商品自动预填成交价，管理员仍可改"
        - "确认复核页逐条列出明细与应收/成本/利润，金额与保存结果一致"
      confirmedBy: chen
      confirmedOn: 2026-09-24
      conclusion: "用户于 2026-09-24 确认 3.11 清单 6 项（列表三列状态、只读列表与显式入口、四步工作区、服务端权威金额、默认值带出、复核一致）；交互实据与数据库回读见上方证据。"
- [x] 3.12 实现 `/orders/:id` 订单内只读多 Tab 骨架：总览、商品与履约、发货与售后、资金与利润、订单资料与变更；Tab 不增设详情路由，总览以可容纳 12+ 商品的简表供核对，履约以分阶段卡片供查看；新建/编辑/处理必须显式进入独立操作状态，不预置表单。订单详情采用常规 Ant Design 后台管理布局：全页白色底、浅色侧栏、顶部面包屑和单组订单页签；使用 Ant Design 现成组件，通过按钮类型/层级、Tag 样式、间距、边框和主题 token 表达视觉层次；不在同一页面并排展示 A/B 方案或重复订单页签。
  - 证据：Requirement/Scenario：`order-lifecycle` Requirement“订单详情必须区分只读事实与显式操作”的两个 Scenario（查看多明细订单、查看已发批次并发起售后）。正式文档：`docs/architecture/order-module-design.md` §7/§8、`design.md` §7（`/orders/:id` 行与订单详情布局约定）。文件：新增 `frontend/src/pages/orders/OrderDetailPage.tsx`；`routes/index.tsx` 替换 `/orders/:id` 占位页。路由：正式 `/orders/:id`，**Tab 不新增详情路由**（`ROUTE_PATHS` 仍 13 条）。
  - 结构：卡片标题为「订单 YM00001 + 主状态 Tag + 生产进度 Tag + 发货进度 Tag」，右上角为显式操作入口（草稿显示「编辑草稿 / 确认订单」，已确认显示「发起变更」，两者都可「取消订单」，另有「返回列表」）；页内一组 5 个页签：总览 / 商品与履约 / 发货与售后 / 资金与利润 / 订单资料与变更。总览＝6 项状态核对 + 简表（#/商品/订购 Q/缝边 E/已发/剩余需求/商品金额/缝边收费）+ 金额与成本分列；商品与履约＝每条明细一张分阶段卡片（制作/捏毛装袋/缝边剪袋的需求与已流入、最终交付需求、可发货、累计发货、成品余量、生产进度）并注明「不按工序相加」；发货与售后＝只读说明空态（阶段六/八未实现，页面不提供录入入口）；资金与利润＝金额/成本分列 + 利润 + 收款退款（阶段七）说明；订单资料与变更＝收货与备注只读 + 变更单列表 + 「发起变更」显式入口。**全页无预置表单**：取消走弹窗（填写原因），编辑草稿与变更跳独立操作页。
  - 浏览器自测（正式路由 `/orders/1`，结构化 DOM 探针）：①页签恰为一组 5 个「总览/商品与履约/发货与售后/资金与利润/订单资料与变更」，切换不改变 URL、不产生详情子路由；②总览表头为「客户/下单日期/期望交期/主状态/排产状态/生产进度/需求处理/发货进度」+ 明细简表，行显示「1 P00001 验收-泰迪熊30cm 10 4 0 10 250.0000 8.0000」（订购 10 / 已发 0 / 剩余 10）；③商品与履约页签显示分阶段卡片「制作（共同需求 Q）10 / 已流入 0、捏毛装袋（共同需求 Q）10 / 已流入 0、缝边剪袋（需求 E）4 / 已流入 0、最终交付需求 10、当前可发货 0、累计有效发货 0、成品余量 0、生产进度 未开始」，并显示「不缝边需求 6、缝边剪袋需求 4；制作/捏毛装袋/最终需求都是 10，不按工序相加」；④已确认订单的右上角只有「发起变更 / 取消订单 / 返回列表」（不再出现编辑草稿与确认）；⑤订单资料与变更页签显示变更单列表「CO00001 已确认 — 查看」；⑥控制台无 error。
  - 自测中发现并修复的问题：**总览简表的 `rowKey="id"` 用在了没有 `id` 字段的履约行上**，React 报「Each child in a list should have a unique key prop（Check the render method of tbody）」；改为 `rowKey="orderItemId"` 后消失。同批修掉 antd 6 已弃用的 `Space direction`（含既有设置页），改 `orientation`。
  - 门禁：`npm run typecheck`/`npm test`（37 用例）/`npm run build` 退出码均 0。
  - 人工证据（与 3.11/3.13 同批签字）：
    humanVisualConclusion:
      status: confirmed
      checklist:
        - "订单详情只有一组订单页签（总览/商品与履约/发货与售后/资金与利润/订单资料与变更），切换页签不跳转、不出现重复订单页签或并排 A/B 方案"
        - "页面整体为白色底、常规 Ant Design 后台管理骨架，侧栏与顶部为浅色，订单页签在详情内"
        - "总览用可容纳 12+ 明细的简表核对「订购/已发/剩余需求」，另有 6 项状态分列可核对"
        - "商品与履约按阶段卡片展示同一条明细的制作/捏毛装袋/缝边剪袋/可发货，数量不按工序相加"
        - "查看是只读的：没有任何预置表单或预选录入项；新建/编辑/确认/变更/取消都从明确按钮进入独立操作状态"
        - "操作按钮层级清楚（主操作用 primary、危险动作用 danger、查看用 link），状态用轻量 Tag 而非大面积色块"
        - "页面间距、表格密度与分隔线符合当前视觉基线（.superpowers/brainstorm/39592-1790147910/content/order-detail-antd-admin-v1.html）"
        - "发货与售后、资金与利润在阶段六/七/八未实现时显示只读说明，不提供录入入口"
      confirmedBy: chen
      confirmedOn: 2026-09-24
      conclusion: "用户于 2026-09-24 确认 3.12 清单 8 项（单组订单页签、白底常规后管骨架、总览 12+ 明细简表、履约分阶段卡片不按工序相加、查看只读无预置表单、按钮层级与轻量 Tag、间距密度符合视觉基线、未实现模块只读说明）；结构化读数与截图基线见上方证据。"
- [x] 3.13 实现 `/orders/:id/changes/:changeId` 变更确认页，明确列出变更前后、已发货下限、在制/合格超出处理、待退款影响和确认后不可覆盖的历史。
  - 证据：Requirement/Scenario：`order-lifecycle` Requirement“已确认订单变化必须使用订单变更”的两个 Scenario（减单低于已发货被拒绝、减单处理超出数量）、Requirement“更正必须保留原始事实”。正式文档：`docs/architecture/order-module-design.md` §3.5/§3.6/§7/§8、`design.md` §7（`/orders/:id/changes/:changeId` 行）。文件：新增 `frontend/src/pages/orders/OrderChangePage.tsx`；`routes/index.tsx` 替换占位页。路由：正式 `/orders/:id/changes/:changeId`（**未新增路由**）。API：`GET/PATCH /api/order-changes/{id}`、`POST /api/order-changes/{id}/confirm`、`GET /api/orders/{id}/fulfillment`（取已发货下限与在制/合格数量）。
  - 结构：以**订单当前明细为基线**列出「变更前后」（变更类型 Tag：未变更/已改/移除/新增；数量 Q、缝边数量 E、成交价均为「前 → 后」可编辑），**只提交真正改动的行**（未改的行不写入变更单，避免变更单被噪声占满）；每行显示「已发货下限」并在低于时标红；减单超出在制/合格数量时逐项出现处理卡片（「继续完成并转成品余量 / 立即报废」+ 处理数量（不得少于超出量）+ 原因）；表头变更区（变更原因、变更后交期/整单优惠/备注/收货四项）留空即沿用原值；「待退款影响」说明当前应收与阶段七口径；「确认后不可覆盖的历史」说明确认会写 ORDER_CHANGE 事实、主状态不变、已确认事实不可改删。
  - 浏览器自测（正式路由 `/orders/1/changes/1`，结构化 DOM 探针）：①页签外标题「变更 CO00001 草稿 订单 YM00001」，分区为「明细变更前后 / 在制/合格超出处理 / 表头与原因 / 其他影响」；②以订单当前明细为基线，行显示「未变更 #1 P00001 验收-泰迪熊30cm」，可编辑数量与成交价；③把数量改为 8 后该行变为「已改」；④点「保存变更草稿」后库内 `order_change_items` 落一条结构化记录（`change_type=UPDATE`、`before_quantity=10`/`after_quantity=8`、`before_seam_quantity=4`/`after_seam_quantity=4`、`before_unit_price=25.0000`/`after_unit_price=25.0000`）；⑤点「确认变更」后跳回 `/orders/1`，订单行显示「1 P00001 验收-泰迪熊30cm 8 4 0 8 200.0000 8.0000」，库内订单金额重算为 商品金额 `200.0000`、缝边收费 `8.0000`、应收 `198.0000`、商品成本 `144.9600`、缝边成本 `5.0000`、总成本 `149.9600`、利润 `48.0400`，`fulfillment_entries` 为 `ORDER_DEMAND IN 10` + `ORDER_CHANGE OUT 2` 两条、投影 `required_quantity=8`、订单主状态仍 `CONFIRMED`；⑥控制台无 error。
  - 自测中发现并修复的真缺口：**变更草稿创建后是空的，页面上没有任何办法录入要改的明细**（只能看到一张空表），端到端无法表达「把某条明细从 10 改成 8」。修复：变更页在草稿为空时以订单当前明细为基线展示可编辑行，并只把真正改动的行提交为变更明细；同时补上表头（交期/优惠/备注/收货）的可选变更区，覆盖 3.7 要求的交期/收货/备注/优惠变更路径。
  - 门禁：`npm run typecheck`/`npm test`（37 用例）/`npm run build` 退出码均 0。
  - 人工证据（与 3.11/3.12 同批签字）：
    humanVisualConclusion:
      status: confirmed
      checklist:
        - "变更页以订单当前明细为基线，逐行显示「变更前后」（数量 Q、缝边数量 E、成交价），未改动的行标「未变更」、改过的标「已改」"
        - "每行显示「已发货下限」，减单低于该值时标红提示，确认被拒绝"
        - "减单超出在制/合格数量时，逐项出现「继续完成并转成品余量 / 立即报废」+ 处理数量 + 原因，未填不能确认"
        - "表头变更区（原因/交期/整单优惠/备注/收货四项）留空即沿用原值，填写后才进入变更"
        - "页面明确写出待退款影响与「确认后不可覆盖的历史」两段说明"
        - "确认后回到订单只读详情，订单金额与明细按变更更新，变更单变为只读"
      confirmedBy: chen
      confirmedOn: 2026-09-24
      conclusion: "用户于 2026-09-24 确认 3.13 清单 6 项（以订单明细为基线的变更前后、已发货下限、超出在制/合格逐项处理、表头留空沿用原值、待退款影响与不可覆盖历史说明、确认后回只读详情）；端到端实据见上方证据。"
- [x] 3.14 编写订单领域/HTTP/MySQL 测试，覆盖确认快照、Q/E 分流、金额字符串、并发确认、变更下限、余量处理、取消条件、事实投影重建和多维状态派生。
  - 证据：Requirement/Scenario：`order-lifecycle` 全阶段 Requirement（确认冻结、工序共享数量、缝边定制、变更、取消、多维状态）。正式文档：`docs/architecture/order-module-design.md` §11 实施项与验收表。文件（本阶段新增 6 个测试类，共 40 用例）：`OrderMigrationTest`（6：八表/唯一键/Q-E 检查/优惠上限/变更目标一致性/来源唯一消费）、`OrderPricingBaselineTest`（6：FP-ORDER-01..09 逐位字符串算例与优惠边界）、`OrderApiTest`（7：编号/服务端金额/明细解析与默认值/校验/草稿编辑与替换/非草稿与版本冲突/列表筛选）、`OrderConfirmationTest`（7：快照与需求事实/商品失效与金额不一致与交期非法三处整笔回滚/重复幂等键不重复生成/并发确认只保留一套/旧版本写入被拒）、`OrderFulfillmentChangeTest`（8：共同数量不按工序相加/生产进度不完成客户需求/变更前后结构化值/变更确认应用金额与事实/草稿不可变更与重复确认/减单低于发货/超出在制合格处理/取消条件）、以及既有 `OrderPricingTest` 之外的回归。API/表：见各任务条目；Flyway：不新增（沿用 `V7`）。
  - 覆盖对照（任务要求 → 用例）：确认快照 → `confirmsOrderWritingSnapshotsAndDemandFacts`；Q/E 分流 → `fulfillmentSplitsSharedQuantityWithoutAddingProcesses` + `OrderMigrationTest#rejectsSeamQuantityAboveQuantity`；金额字符串 → `OrderPricingBaselineTest`（逐位 `toPlainString` 比对）+ `createsDraftWithMonotonicNumberAndServerSideAmounts`；并发确认 → `concurrentConfirmKeepsSingleSnapshotSet`；变更下限 → `rejectsReductionBelowShipped`；余量处理 → `requiresDispositionWhenReductionExceedsInProcess`；取消条件 → `cancelsDraftAndFactFreeConfirmedOrderOnly`；事实投影重建 → 本阶段以「投影列随事实同事务更新 + 需求/变更事实可累加核对」覆盖，**完整的按事实重建比对留到阶段九 9.x**（已在设计文档 §3.8 记录）；多维状态派生 → `productionProgressDoesNotCompleteCustomerDemand`。确认路径的版本冲突（`CONFLICT_VERSION`）由 `staleVersionGuardRejectsConfirmationWrite` 直达覆盖（旧版本确认写入更新 0 行）。
  - 门禁（阶段三批次边界，清库重建后）：后端 `YUMI_DB_PASSWORD=<钥匙串> ./mvnw test` 退出码 0，**195 测试 0 失败 0 错误**（阶段二边界 161 → 本阶段新增 34）；前端 `npm run typecheck` 退出码 0、`npm test` 退出码 0（6 文件 37 用例）、`npm run build` 退出码 0；`openspec validate build-yumi-v2-order-fulfillment --strict` 输出 valid。
  - 失败归因（如实记录，无遗留失败）：本阶段修掉的真问题均先复现后修复——①迁移新增列未同步 INSERT 占位符（`No value specified for parameter 41`）；②默认不缝边剪袋时 `seam_type_cost_price` 传 NULL（列非空）；③超范围优惠抛 `IllegalArgumentException` 未映射为字段级 400；④`applyChangeHeader` 只回写 3 个金额列导致变更后 `goods_amount` 不更新；⑤抽出解析器后草稿放行「数量为 0」（补 `requirePositiveQuantity` 区分草稿与变更移除）；⑥测试清理语句写错列名、管理员先建后删、MySQL `INT UNSIGNED` 经 `queryForMap` 返回 `Long` 等测试自身缺陷。
  - 人工证据：不适用（人工验收见 3.15）。
- [x] 3.15 阶段人工验收：在正式订单路由创建含缝边/不缝边的多明细订单，确认后修改基础资料，执行减单余量处理和取消拒绝；通过 `humanVisualConclusion.checklist` 验证正式 `/orders/:id` 路由只有一组订单页签、页面整体为白色底、采用常规后管骨架、侧栏和顶部面包屑为浅色、操作按钮使用明确的 Ant Design 按钮层级且查看动作不误作提交动作、状态 Tag 使用轻量细边框样式而非大面积色块、页面间距/表格密度/分隔线符合当前视觉基线；用户确认前状态保持 `pending-user-signoff`。
  - 证据（2026-09-24 阶段三验收，正式路由 `http://127.0.0.1:5190`，后端 18090，清库重建后空库起，登录本机合成账号 `admin`）：
  - ①**含缝边/不缝边的多明细订单**：`POST /api/orders` 建两条明细（P00001 验收-泰迪熊30cm Q=10 / E=4 选「标准缝边」，P00002 验收-小猫挂件 Q=20 / E=0 不缝边剪袋）→ 商品金额 `490.0000`（250+240）、缝边收费 `8.0000`、应收 `498.0000`、商品成本 `402.2660`、缝边成本 `5.0000`、总成本 `407.2660`、利润 `90.7340`；确认后 `YM00001 CONFIRMED`、`version 1`，订单级与明细级快照齐备（明细快照 `unit_cost` 18.1200 / 11.0533、`total_cost` 与之一致，`flow` 含「缝边剪袋」）。
  - ②**确认后修改基础资料不回溯**：把商品 P00001 克重 270→400（商品 `total_cost` 由 18.1200 变为 22.8000）后回读订单——应收/成本/利润与两条明细的 `unit_cost`/`goods_cost_amount` 全部未变，`order_items`、`order_item_snapshots`、`order_confirmation_snapshots` 三处成本列仍为 `181.2000`/`221.0660`/`402.2660`。
  - ③**减单余量处理**：给明细 1 注入 `packing_inflow = 9`（模拟阶段五生产流入，阶段三在制数量恒为 0，故用事实注入构造场景）后减单 10→6（超出 3）——不带处理方案确认返回 409 `QUANTITY_REQUIRES_DISPOSITION` 且 `fieldErrors` 定位 `items[0].surplusDisposition`（「超出在制/合格 3 件，必须逐项选择转成品余量或立即报废并填写数量与原因」）；改同一张草稿补 `FINISH_TO_SURPLUS`/数量 3/原因后确认成功，`CO00001 CONFIRMED`，订单应收 `398.0000`、总成本 `362.8660`、利润 `35.1340`，明细 1 变为 Q=6，`order_change_items` 结构化落库（`before_quantity 10`/`after_quantity 6`/`surplus_disposition FINISH_TO_SURPLUS`/`surplus_quantity 3`/原因），履约事实为 `ORDER_DEMAND IN 10` + `ORDER_DEMAND IN 20` + `ORDER_CHANGE OUT 4`，投影 `required_quantity` 为 6 与 20。
  - ④**取消拒绝**：新建并确认 `YM00002`（无任何执行事实）→ 取消成功变为 `CANCELLED`；新建并确认 `YM00003` 后插入一条 `INVENTORY_ALLOCATION` 执行事实 → 取消返回 409 `STATE_CANCEL_NOT_ALLOWED`，消息「订单已有履约事实，请通过订单变更处理剩余需求与超出数量」，`fieldErrors` 定位 `orderId`；订单仍为 `CONFIRMED`，两条事实（需求 + 领用）**一条未删**。
  - ⑤**视觉与只读核对**（正式路由 `/orders/:id`，结构化 DOM 探针）：页签恰为一组 5 个（总览/商品与履约/发货与售后/资金与利润/订单资料与变更）；卡片标题「订单 YM00003 已确认 未开始 未发货」；右上角只有「发起变更 / 取消订单 / 返回列表」三个显式操作；总览表头为 8 项状态 + 明细简表（订购 Q / 缝边 E / 已发 / 剩余需求 / 商品金额 / 缝边收费），行「1 P00002 验收-小猫挂件 5 0 0 5 60.0000 0.0000」；页面**无任何预置表单**；计算样式 `card` 背景 `rgb(255,255,255)`（白底）、`layout` 背景 `rgb(245,245,245)`（浅色外壳）。
  - **取证方式说明（如实记录）**：③④的明细行编辑与取消在页面上均有对应入口，但本轮阶段验收的**多明细建单/减单/取消**用 API + 库内核对完成——内置浏览器驱动 antd `InputNumber` 的合成事件会把受控状态打乱（实测出现行内容错位、数量变成 9），不可作为可靠证据；页面的建单/变更交互已在 3.11/3.13 用正式路由自测并留证（单明细全流程），视觉项本轮以只读页面读数复核。
  - 人工证据（与 3.11/3.12/3.13 同一批签字）：
    humanVisualConclusion:
      status: confirmed
      checklist:
        - "含缝边与不缝边的多明细订单能在正式路由建单并确认，金额与快照由服务端给出"
        - "确认后修改商品基础资料（克重/成本）不回溯已确认订单的金额、明细成本与快照"
        - "减单超出在制/合格数量时不填处理方案会被拒绝并定位到具体明细；补「继续完成转成品余量/立即报废」的数量与原因后能确认"
        - "已确认订单在没有任何执行事实时可以取消；已有领用/生产/发货事实时取消被拒绝且不删除历史"
        - "订单详情只有一组订单页签、页面白色底、常规后管骨架、浅色侧栏，操作按钮层级明确，查看动作不误作提交"
        - "状态用轻量 Tag 而非大面积色块，页面间距/表格密度/分隔线符合视觉基线"
      confirmedBy: chen
      confirmedOn: 2026-09-24
      conclusion: "用户于 2026-09-24 确认 3.15 阶段验收清单 6 项（多明细含缝边建单与确认、确认后改基础资料不回溯、减单超出必须逐项处理、取消条件、单组页签与白底常规后管骨架、轻量 Tag 与间距密度）；验收场景与库内核对实据见上方证据。"

## 4. 阶段四：库存批次、流水与订单领用（依赖阶段三）

- [ ] 4.1 编写 Flyway 迁移创建 `inventory_batches/inventory_movements/inventory_movement_lines/inventory_allocations/inventory_allocation_lines`，落实 `IB000001/IM000001` 唯一编号、非负数量、来源/冲销关联和锁定索引。
  - 证据契约：Requirement/Scenario、正式文档章节、实际文件/API/表/路由、RED/GREEN 命令与退出码、关键断言；实现后逐项回填。
  - 人工证据：不适用；如涉及页面/打印/Electron，回填 `humanVisualConclusion.checklist`，状态为 `pending-user-signoff`。
- [ ] 4.2 实现库存批次/汇总/流水查询 `GET /api/inventory/batches|movements|summary`，默认按商品+工序+缝边状态汇总并可展开来源，零库存默认隐藏但历史可查。
  - 证据契约：Requirement/Scenario、正式文档章节、实际文件/API/表/路由、RED/GREEN 命令与退出码、关键断言；实现后逐项回填。
  - 人工证据：不适用；如涉及页面/打印/Electron，回填 `humanVisualConclusion.checklist`，状态为 `pending-user-signoff`。
- [ ] 4.3 实现期初库存 `POST /api/inventory/batches`，保存盘点日期、来源、操作人和备注，不伪造旧订单、生产或工资事实。
  - 证据契约：Requirement/Scenario、正式文档章节、实际文件/API/表/路由、RED/GREEN 命令与退出码、关键断言；实现后逐项回填。
  - 人工证据：不适用；如涉及页面/打印/Electron，回填 `humanVisualConclusion.checklist`，状态为 `pending-user-signoff`。
- [ ] 4.4 实现盘点调整 `POST /api/inventory/adjustments`，由实际数量计算差异并生成盘盈/盘亏流水；数量一致不写记录，错误只能用新调整/冲销事实更正。
  - 证据契约：Requirement/Scenario、正式文档章节、实际文件/API/表/路由、RED/GREEN 命令与退出码、关键断言；实现后逐项回填。
  - 人工证据：不适用；如涉及页面/打印/Electron，回填 `humanVisualConclusion.checklist`，状态为 `pending-user-signoff`。
- [ ] 4.5 实现库存推荐查询，按商品、接入工序、缝边兼容性和 FIFO 推荐；只推荐不自动提交，支持一次选择多个批次。
  - 证据契约：Requirement/Scenario、正式文档章节、实际文件/API/表/路由、RED/GREEN 命令与退出码、关键断言；实现后逐项回填。
  - 人工证据：不适用；如涉及页面/打印/Electron，回填 `humanVisualConclusion.checklist`，状态为 `pending-user-signoff`。
- [ ] 4.6 实现 `POST /api/inventory-allocations`：稳定顺序悲观锁批次、重新校验余额/兼容性、生成出库流水、扣库存并在同一事务写订单履约接入。
  - 证据契约：Requirement/Scenario、正式文档章节、实际文件/API/表/路由、RED/GREEN 命令与退出码、关键断言；实现后逐项回填。
  - 人工证据：不适用；如涉及页面/打印/Electron，回填 `humanVisualConclusion.checklist`，状态为 `pending-user-signoff`。
- [ ] 4.7 实现接入矩阵：制作合格库存→捏毛装袋；捏毛装袋合格库存→不缝边可发货或缝边剪袋；缝边剪袋合格库存→可发货；每笔来源只接入一次。
  - 证据契约：Requirement/Scenario、正式文档章节、实际文件/API/表/路由、RED/GREEN 命令与退出码、关键断言；实现后逐项回填。
  - 人工证据：不适用；如涉及页面/打印/Electron，回填 `humanVisualConclusion.checklist`，状态为 `pending-user-signoff`。
- [ ] 4.8 实现草稿库存计划和确认时重验：草稿不占用；确认库存不足返回每批缺口，管理员明确重新选择或转生产，系统不得自动把缺口转生产。
  - 证据契约：Requirement/Scenario、正式文档章节、实际文件/API/表/路由、RED/GREEN 命令与退出码、关键断言；实现后逐项回填。
  - 人工证据：不适用；如涉及页面/打印/Electron，回填 `humanVisualConclusion.checklist`，状态为 `pending-user-signoff`。
- [ ] 4.9 实现 `POST /api/inventory-allocations/{id}/cancel`，仅未被后续生产/核验/发货消费时生成反向库存和履约事实；已消费返回 `STATE_CANNOT_CANCEL`。
  - 证据契约：Requirement/Scenario、正式文档章节、实际文件/API/表/路由、RED/GREEN 命令与退出码、关键断言；实现后逐项回填。
  - 人工证据：不适用；如涉及页面/打印/Electron，回填 `humanVisualConclusion.checklist`，状态为 `pending-user-signoff`。
- [ ] 4.10 实现库存流水冲销命令和查询，未消费流水可关联冲销，已被后续事实消费时拒绝；原流水、冲销、重录永久保留。
  - 证据契约：Requirement/Scenario、正式文档章节、实际文件/API/表/路由、RED/GREEN 命令与退出码、关键断言；实现后逐项回填。
  - 人工证据：不适用；如涉及页面/打印/Electron，回填 `humanVisualConclusion.checklist`，状态为 `pending-user-signoff`。
- [ ] 4.11 实现 `/inventory` 全页工作区：汇总、批次、流水、期初、盘点、订单领用和取消入口；展示出库前后数量、来源业务和订单明细追溯。
  - 证据契约：Requirement/Scenario、正式文档章节、实际文件/API/表/路由、RED/GREEN 命令与退出码、关键断言；实现后逐项回填。
  - 人工证据：不适用；如涉及页面/打印/Electron，回填 `humanVisualConclusion.checklist`，状态为 `pending-user-signoff`。
- [ ] 4.12 编写 MySQL 并发测试：两个事务竞争同一批次只允许一个成功；失败事务无负库存、无部分流水、无履约接入；来源唯一约束拒绝重复领用。
  - 证据契约：Requirement/Scenario、正式文档章节、实际文件/API/表/路由、RED/GREEN 命令与退出码、关键断言；实现后逐项回填。
  - 人工证据：不适用；如涉及页面/打印/Electron，回填 `humanVisualConclusion.checklist`，状态为 `pending-user-signoff`。
- [ ] 4.13 编写跨模块集成测试证明库存领用时已扣原库存，随后发货只消耗订单可发货，库存流水数量不因发货第二次减少。
  - 证据契约：Requirement/Scenario、正式文档章节、实际文件/API/表/路由、RED/GREEN 命令与退出码、关键断言；实现后逐项回填。
  - 人工证据：不适用；如涉及页面/打印/Electron，回填 `humanVisualConclusion.checklist`，状态为 `pending-user-signoff`。
- [ ] 4.14 阶段人工验收：期初入库、盘点、多个批次领用到不同工序、取消未消费领用和拒绝已消费领用；视觉结论待用户签字。
  - 证据契约：Requirement/Scenario、正式文档章节、实际文件/API/表/路由、RED/GREEN 命令与退出码、关键断言；实现后逐项回填。
  - 人工证据：不适用；如涉及页面/打印/Electron，回填 `humanVisualConclusion.checklist`，状态为 `pending-user-signoff`。

## 5. 阶段五：生产计划、核验、返工、重做与超额提醒（依赖阶段三；库存接入依赖阶段四）

- [ ] 5.1 编写 Flyway 迁移创建 `production_plans/production_plan_adjustments/production_verifications/rework_sources/remake_sources/overtime_preemptions/production_reminders/other_schedules/other_schedule_verifications/other_schedule_time_corrections`，落实唯一核验和来源余额约束。
  - 证据契约：Requirement/Scenario、正式文档章节、实际文件/API/表/路由、RED/GREEN 命令与退出码、关键断言；实现后逐项回填。
  - 人工证据：不适用；如涉及页面/打印/Electron，回填 `humanVisualConclusion.checklist`，状态为 `pending-user-signoff`。
- [ ] 5.2 实现生产计划查询/创建 `GET/POST /api/production-plans`，覆盖正常、返工、重做、超额、售后返工、售后补发类型及执行员工资格；核心归属字段创建后不可编辑。
  - 证据契约：Requirement/Scenario、正式文档章节、实际文件/API/表/路由、RED/GREEN 命令与退出码、关键断言；实现后逐项回填。
  - 人工证据：不适用；如涉及页面/打印/Electron，回填 `humanVisualConclusion.checklist`，状态为 `pending-user-signoff`。
- [ ] 5.3 实现待安排/当前可执行/等待上游计算，允许计划数量使用尚需安排总需求但核验必须受当时可执行数量限制；计划创建不产生完成、库存或履约事实。
  - 证据契约：Requirement/Scenario、正式文档章节、实际文件/API/表/路由、RED/GREEN 命令与退出码、关键断言；实现后逐项回填。
  - 人工证据：不适用；如涉及页面/打印/Electron，回填 `humanVisualConclusion.checklist`，状态为 `pending-user-signoff`。
- [ ] 5.4 实现 `POST /api/production-plans/{id}/verify` 一次性核验，校验完成=合格+返工+报废、未完成=计划-完成，事务内锁来源和履约余额后分流各结果。
  - 证据契约：Requirement/Scenario、正式文档章节、实际文件/API/表/路由、RED/GREEN 命令与退出码、关键断言；实现后逐项回填。
  - 人工证据：不适用；如涉及页面/打印/Electron，回填 `humanVisualConclusion.checklist`，状态为 `pending-user-signoff`。
- [ ] 5.5 实现逐工序合格流转：制作→捏毛装袋；捏毛装袋按冻结 E 分为不缝边可发货与缝边剪袋；缝边剪袋→可发货；不得将各工序完成相加。
  - 证据契约：Requirement/Scenario、正式文档章节、实际文件/API/表/路由、RED/GREEN 命令与退出码、关键断言；实现后逐项回填。
  - 人工证据：不适用；如涉及页面/打印/Electron，回填 `humanVisualConclusion.checklist`，状态为 `pending-user-signoff`。
- [ ] 5.6 实现返工来源查询/创建 `GET/POST /api/rework-sources` 和从来源创建计划 `POST /api/rework-sources/{id}/plans`，落实完整目标矩阵、原核验/上一返工关联、返工次数、原因及尚未安排余额；并发创建不得超过来源余额。
  - 证据契约：Requirement/Scenario、正式文档章节、实际文件/API/表/路由、RED/GREEN 命令与退出码、关键断言；实现后逐项回填。
  - 人工证据：不适用；如涉及页面/打印/Electron，回填 `humanVisualConclusion.checklist`，状态为 `pending-user-signoff`。
- [ ] 5.7 实现报废重做来源查询/创建 `GET/POST /api/remake-sources` 和从来源创建计划 `POST /api/remake-sources/{id}/plans`，默认报废工序起始，从制作开始必须填写原因；原报废事实永久保留，重做不增加订单需求。
  - 证据契约：Requirement/Scenario、正式文档章节、实际文件/API/表/路由、RED/GREEN 命令与退出码、关键断言；实现后逐项回填。
  - 人工证据：不适用；如涉及页面/打印/Electron，回填 `humanVisualConclusion.checklist`，状态为 `pending-user-signoff`。
- [ ] 5.8 实现 `POST /api/production-plans/{id}/cancel`，对应 `production-management`“待执行计划取消必须恢复来源”；仅待执行可取消，正常/返工/重做分别恢复来源，已核验返回 `STATE_NOT_CANCELABLE`。
  - 证据契约：Requirement/Scenario、正式文档章节、实际文件/API/表/路由、RED/GREEN 命令与退出码、关键断言；实现后逐项回填。
  - 人工证据：不适用；如涉及页面/打印/Electron，回填 `humanVisualConclusion.checklist`，状态为 `pending-user-signoff`。
- [ ] 5.9 实现未完成待处理 `GET /api/production-reminders/incomplete`、重新安排 `POST .../{id}/reschedule` 和暂不安排 `POST .../{id}/defer`；部分安排保留余量，暂不安排必须有原因且不删除待安排需求。
  - 证据契约：Requirement/Scenario、正式文档章节、实际文件/API/表/路由、RED/GREEN 命令与退出码、关键断言；实现后逐项回填。
  - 人工证据：不适用；如涉及页面/打印/Electron，回填 `humanVisualConclusion.checklist`，状态为 `pending-user-signoff`。
- [ ] 5.10 实现超额任务创建 `POST /api/overtime-tasks`：仅执行当天创建，只能选择未来正常计划未预占数量，跨订单/商品时每条仍明确来源计划；预占不修改原计划数量或履约事实。
  - 证据契约：Requirement/Scenario、正式文档章节、实际文件/API/表/路由、RED/GREEN 命令与退出码、关键断言；实现后逐项回填。
  - 人工证据：不适用；如涉及页面/打印/Electron，回填 `humanVisualConclusion.checklist`，状态为 `pending-user-signoff`。
- [ ] 5.11 实现超额任务核验 `POST /api/overtime-tasks/{id}/verify`：未完成释放预占；只有合格数量写正常履约并生成未来计划待调整提醒；返工/报废按独立来源处理，不作为计划减少依据。
  - 证据契约：Requirement/Scenario、正式文档章节、实际文件/API/表/路由、RED/GREEN 命令与退出码、关键断言；实现后逐项回填。
  - 人工证据：不适用；如涉及页面/打印/Electron，回填 `humanVisualConclusion.checklist`，状态为 `pending-user-signoff`。
- [ ] 5.12 实现超额提醒查询/处理 `GET /api/production-reminders/overtime`、`POST .../{id}/adjust-plan`、`POST .../{id}/no-adjustment`；调整保存前后值/原因/来源，无需调整必须填原因，零合格自动结束提醒。
  - 证据契约：Requirement/Scenario、正式文档章节、实际文件/API/表/路由、RED/GREEN 命令与退出码、关键断言；实现后逐项回填。
  - 人工证据：不适用；如涉及页面/打印/Electron，回填 `humanVisualConclusion.checklist`，状态为 `pending-user-signoff`。
- [ ] 5.13 实现其他排班创建、一次性总分钟核验、取消和工时更正；分钟 0-59、总分钟>0，确保不产生商品、库存或订单履约事实。
  - 证据契约：Requirement/Scenario、正式文档章节、实际文件/API/表/路由、RED/GREEN 命令与退出码、关键断言；实现后逐项回填。
  - 人工证据：不适用；如涉及页面/打印/Electron，回填 `humanVisualConclusion.checklist`，状态为 `pending-user-signoff`。
- [ ] 5.14 实现 `/production` 正式工作台：日期/员工/订单/工序计划列表、等待上游、独立未完成区域、返工/重做来源、超额提醒附着未来排班行和行内调整操作。
  - 证据契约：Requirement/Scenario、正式文档章节、实际文件/API/表/路由、RED/GREEN 命令与退出码、关键断言；实现后逐项回填。
  - 人工证据：不适用；如涉及页面/打印/Electron，回填 `humanVisualConclusion.checklist`，状态为 `pending-user-signoff`。
- [ ] 5.15 实现 `/production/plans/:id/verify` 核验页，清晰区分计划/可执行/等待数量与完成/合格/返工/报废/未完成，错误码定位对应字段。
  - 证据契约：Requirement/Scenario、正式文档章节、实际文件/API/表/路由、RED/GREEN 命令与退出码、关键断言；实现后逐项回填。
  - 人工证据：不适用；如涉及页面/打印/Electron，回填 `humanVisualConclusion.checklist`，状态为 `pending-user-signoff`。
- [ ] 5.16 编写领域和 MySQL 集成测试覆盖唯一核验、等式、可执行上限、Q/E 流转、返工矩阵、重做原因、来源余额竞争、取消恢复、未完成提醒、超额预占竞争和零合格提醒。
  - 证据契约：Requirement/Scenario、正式文档章节、实际文件/API/表/路由、RED/GREEN 命令与退出码、关键断言；实现后逐项回填。
  - 人工证据：不适用；如涉及页面/打印/Electron，回填 `humanVisualConclusion.checklist`，状态为 `pending-user-signoff`。
- [ ] 5.17 阶段人工验收：执行正常计划、等待上游、部分核验、返工、重做、取消、超额任务和其他排班；逐项确认来源/提醒/历史可追溯，视觉结论待签字。
  - 证据契约：Requirement/Scenario、正式文档章节、实际文件/API/表/路由、RED/GREEN 命令与退出码、关键断言；实现后逐项回填。
  - 人工证据：不适用；如涉及页面/打印/Electron，回填 `humanVisualConclusion.checklist`，状态为 `pending-user-signoff`。

## 6. 阶段六：订单发货、物流与发货更正（依赖阶段四和五）

- [ ] 6.1 编写 Flyway 迁移创建 `shipments/shipment_items/shipment_source_links/shipment_logistics_changes/shipment_corrections`，落实批次编号、状态、快照、来源关联和等量更正唯一约束；表归 `orders`。
  - 证据契约：Requirement/Scenario、正式文档章节、实际文件/API/表/路由、RED/GREEN 命令与退出码、关键断言；实现后逐项回填。
  - 人工证据：不适用；如涉及页面/打印/Electron，回填 `humanVisualConclusion.checklist`，状态为 `pending-user-signoff`。
- [ ] 6.2 实现订单发货查询/草稿 API `GET/POST /api/orders/{id}/shipments` 和草稿编辑，草稿不影响可发货/累计发货且可预览非正式清单。
  - 证据契约：Requirement/Scenario、正式文档章节、实际文件/API/表/路由、RED/GREEN 命令与退出码、关键断言；实现后逐项回填。
  - 人工证据：不适用；如涉及页面/打印/Electron，回填 `humanVisualConclusion.checklist`，状态为 `pending-user-signoff`。
- [ ] 6.3 实现 `POST /api/orders/{id}/shipments/{shipmentId}/confirm`：锁定履约余额，校验订单状态、每明细可发货、当前有效需求和缝边状态，整批原子写确认快照、来源链接和累计发货。
  - 证据契约：Requirement/Scenario、正式文档章节、实际文件/API/表/路由、RED/GREEN 命令与退出码、关键断言；实现后逐项回填。
  - 人工证据：不适用；如涉及页面/打印/Electron，回填 `humanVisualConclusion.checklist`，状态为 `pending-user-signoff`。
- [ ] 6.4 实现并验证确认上限：本次发货<=当前可发货，累计有效发货+本次<=当前有效订购；任一明细失败整批不生效。
  - 证据契约：Requirement/Scenario、正式文档章节、实际文件/API/表/路由、RED/GREEN 命令与退出码、关键断言；实现后逐项回填。
  - 人工证据：不适用；如涉及页面/打印/Electron，回填 `humanVisualConclusion.checklist`，状态为 `pending-user-signoff`。
- [ ] 6.5 实现物流修改 `PATCH .../logistics`，只允许公司、单号、运费和备注，必须填写原因并保留前后值；不得修改订单、商品、数量、日期和来源。
  - 证据契约：Requirement/Scenario、正式文档章节、实际文件/API/表/路由、RED/GREEN 命令与退出码、关键断言；实现后逐项回填。
  - 人工证据：不适用；如涉及页面/打印/Electron，回填 `humanVisualConclusion.checklist`，状态为 `pending-user-signoff`。
- [ ] 6.6 实现未关闭订单发货作废 `POST .../void`，先校验原批次明细无有效售后占用，再写反向履约恢复可发货和累计发货，不恢复原库存；有售后占用返回 `SHIPMENT_AFTER_SALES_LINKED`；原确认快照保留，作废批次不可再次确认。
  - 证据契约：Requirement/Scenario、正式文档章节、实际文件/API/表/路由、RED/GREEN 命令与退出码、关键断言；实现后逐项回填。
  - 人工证据：不适用；如涉及页面/打印/Electron，回填 `humanVisualConclusion.checklist`，状态为 `pending-user-signoff`。
- [ ] 6.7 实现已关闭订单发货更正 `POST .../corrections`，同一事务使原批次失效并创建等量替代；有有效售后引用而未处理来源关联时返回 `SHIPMENT_AFTER_SALES_LINKED`；不能立即替代时返回 `CORRECTION_REPLACEMENT_REQUIRED` 并引导售后。
  - 证据契约：Requirement/Scenario、正式文档章节、实际文件/API/表/路由、RED/GREEN 命令与退出码、关键断言；实现后逐项回填。
  - 人工证据：不适用；如涉及页面/打印/Electron，回填 `humanVisualConclusion.checklist`，状态为 `pending-user-signoff`。
- [ ] 6.8 实现 `/orders/:id` 内“发货与售后”只读 Tab 的发货批次区域：草稿、确认、来源追溯、物流修改、作废/更正、打印/PDF；显式进入订单上下文操作界面，不建立 `/shipments` 顶级工作区，不允许普通操作编辑已确认业务数量。
  - 证据契约：Requirement/Scenario、正式文档章节、实际文件/API/表/路由、RED/GREEN 命令与退出码、关键断言；实现后逐项回填。
  - 人工证据：不适用；如涉及页面/打印/Electron，回填 `humanVisualConclusion.checklist`，状态为 `pending-user-signoff`。
- [ ] 6.9 编写 HTTP/MySQL 并发测试覆盖超量、两个并发发货、整批回滚、领用后不二扣库存、无售后作废恢复、已被售后占用时拒绝作废/失效更正、关闭后等量更正和物流修改不影响数量。
  - 证据契约：Requirement/Scenario、正式文档章节、实际文件/API/表/路由、RED/GREEN 命令与退出码、关键断言；实现后逐项回填。
  - 人工证据：不适用；如涉及页面/打印/Electron，回填 `humanVisualConclusion.checklist`，状态为 `pending-user-signoff`。
- [ ] 6.10 阶段人工验收：分别从生产和库存形成可发货、分批确认、修改物流、作废未关闭批次和执行等量更正；确认库存只在领用时扣一次，视觉结论待签字。
  - 证据契约：Requirement/Scenario、正式文档章节、实际文件/API/表/路由、RED/GREEN 命令与退出码、关键断言；实现后逐项回填。
  - 人工证据：不适用；如涉及页面/打印/Electron，回填 `humanVisualConclusion.checklist`，状态为 `pending-user-signoff`。

## 7. 阶段七：收款、退款与订单关闭（依赖阶段六）

- [ ] 7.1 编写 Flyway 迁移创建 `payments/refunds` 及订单款项投影/索引，金额 `DECIMAL(19,4)`、来源关联、幂等唯一和退款累计约束；表归 `orders`。
  - 证据契约：Requirement/Scenario、正式文档章节、实际文件/API/表/路由、RED/GREEN 命令与退出码、关键断言；实现后逐项回填。
  - 人工证据：不适用；如涉及页面/打印/Electron，回填 `humanVisualConclusion.checklist`，状态为 `pending-user-signoff`。
- [ ] 7.2 实现 `POST /api/orders/{id}/payments`，仅已确认/允许补录的业务状态可登记，保存金额、日期、方式、备注和管理员；草稿返回 `PAYMENT_DRAFT_FORBIDDEN`，事实不可编辑删除。
  - 证据契约：Requirement/Scenario、正式文档章节、实际文件/API/表/路由、RED/GREEN 命令与退出码、关键断言；实现后逐项回填。
  - 人工证据：不适用；如涉及页面/打印/Electron，回填 `humanVisualConclusion.checklist`，状态为 `pending-user-signoff`。
- [ ] 7.3 实现 `POST /api/orders/{id}/refunds`，校验订单变更退款与售后退款合计<=累计收款，保存方式、原因、备注及来源类型；订单变更退款关联变更单并参与结清，售后退款关联售后单且订单仍已确认时也可登记；已取消/已关闭订单仅允许有依据的实际退款补录，原收款不变。
  - 证据契约：Requirement/Scenario、正式文档章节、实际文件/API/表/路由、RED/GREEN 命令与退出码、关键断言；实现后逐项回填。
  - 人工证据：不适用；如涉及页面/打印/Electron，回填 `humanVisualConclusion.checklist`，状态为 `pending-user-signoff`。
- [ ] 7.4 实现服务端订单结清公式：结清净额=累计订单收款-累计订单变更退款；订单待退款=max(累计订单收款-当前有效应收-累计订单变更退款,0)；售后退款另列累计实际净收，不冲减订单结清净额、不产生新的原订单待收/待退；收款状态按有效应收与结清净额派生。
  - 证据契约：Requirement/Scenario、正式文档章节、实际文件/API/表/路由、RED/GREEN 命令与退出码、关键断言；实现后逐项回填。
  - 人工证据：不适用；如涉及页面/打印/Electron，回填 `humanVisualConclusion.checklist`，状态为 `pending-user-signoff`。
- [ ] 7.5 实现 `POST /api/orders/{id}/close`，同一事务锁订单及履约/款项投影并重验全部有效需求已发货或明确取消、应收结清、无待退款和无未处理差额。
  - 证据契约：Requirement/Scenario、正式文档章节、实际文件/API/表/路由、RED/GREEN 命令与退出码、关键断言；实现后逐项回填。
  - 人工证据：不适用；如涉及页面/打印/Electron，回填 `humanVisualConclusion.checklist`，状态为 `pending-user-signoff`。
- [ ] 7.6 实现已取消/已关闭互斥终态和已关闭不重开；关闭后禁止新增原订单生产、发货和收款，允许独立物流修改、实际退款、更正和售后。
  - 证据契约：Requirement/Scenario、正式文档章节、实际文件/API/表/路由、RED/GREEN 命令与退出码、关键断言；实现后逐项回填。
  - 人工证据：不适用；如涉及页面/打印/Electron，回填 `humanVisualConclusion.checklist`，状态为 `pending-user-signoff`。
- [ ] 7.7 实现 `/orders/:id` 内“资金与利润”只读 Tab：逐笔收退款、订单结清净额、售后退款单列、累计实际净收、订单待退款、成本快照及预计利润拆解；订单关闭条件逐项可查看，不提供普通编辑/删除。
  - 证据契约：Requirement/Scenario、正式文档章节、实际文件/API/表/路由、RED/GREEN 命令与退出码、关键断言；实现后逐项回填。
  - 人工证据：不适用；如涉及页面/打印/Electron，回填 `humanVisualConclusion.checklist`，状态为 `pending-user-signoff`。
- [ ] 7.8 编写领域/HTTP/MySQL 测试覆盖多次收款、退款上限、来源必填、变更待退款、草稿拒绝、生产完成但未交付拒绝关闭、并发关闭重验及终态互斥。
  - 证据契约：Requirement/Scenario、正式文档章节、实际文件/API/表/路由、RED/GREEN 命令与退出码、关键断言；实现后逐项回填。
  - 人工证据：不适用；如涉及页面/打印/Electron，回填 `humanVisualConclusion.checklist`，状态为 `pending-user-signoff`。
- [ ] 7.9 阶段人工验收：执行未收/部分/全额收款、减单待退款、退款处理和关闭；确认报废、余量或生产完成不能单独关闭，视觉结论待签字。
  - 证据契约：Requirement/Scenario、正式文档章节、实际文件/API/表/路由、RED/GREEN 命令与退出码、关键断言；实现后逐项回填。
  - 人工证据：不适用；如涉及页面/打印/Electron，回填 `humanVisualConclusion.checklist`，状态为 `pending-user-signoff`。

## 8. 阶段八：发货后售后独立台账（依赖库存、生产、发货和退款）

- [ ] 8.1 编写 Flyway 迁移创建 `after_sales_cases/after_sales_items/after_sales_return_verifications/after_sales_fulfillment_entries/after_sales_shipment_links` 及更正记录，落实退回等式、原发货批次明细的必填引用、有效受理数量投影和来源唯一约束；表归 `orders`。
  - 证据契约：Requirement/Scenario、正式文档章节、实际文件/API/表/路由、RED/GREEN 命令与退出码、关键断言；实现后逐项回填。
  - 人工证据：不适用；如涉及页面/打印/Electron，回填 `humanVisualConclusion.checklist`，状态为 `pending-user-signoff`。
- [ ] 8.2 实现 `POST /api/orders/{id}/after-sales`，必须关联已确认且有效的原发货批次明细、原订单明细，锁定明细剩余可受理量并保存类型、问题、方案与补发需求；订单部分发货但未关闭也允许创建；草稿、作废、未发或超量来源返回 `AFTER_SALES_SOURCE_INVALID`/`AFTER_SALES_QUANTITY_EXCEEDED`。
  - 证据契约：Requirement/Scenario、正式文档章节、实际文件/API/表/路由、RED/GREEN 命令与退出码、关键断言；实现后逐项回填。
  - 人工证据：不适用；如涉及页面/打印/Electron，回填 `humanVisualConclusion.checklist`，状态为 `pending-user-signoff`。
- [ ] 8.3 实现 `POST .../{caseId}/verify-return`，校验客户退回=售后返工+售后报废，保存问题、原因、核验人/时间；退回不自动入库、不恢复原发货库存。
  - 证据契约：Requirement/Scenario、正式文档章节、实际文件/API/表/路由、RED/GREEN 命令与退出码、关键断言；实现后逐项回填。
  - 人工证据：不适用；如涉及页面/打印/Electron，回填 `humanVisualConclusion.checklist`，状态为 `pending-user-signoff`。
- [ ] 8.4 实现售后返工来源并复用生产计划一次核验与返工矩阵；最终合格按售后用途进入可补发，不自动进入通用库存，转库存必须显式新建库存批次。
  - 证据契约：Requirement/Scenario、正式文档章节、实际文件/API/表/路由、RED/GREEN 命令与退出码、关键断言；实现后逐项回填。
  - 人工证据：不适用；如涉及页面/打印/Electron，回填 `humanVisualConclusion.checklist`，状态为 `pending-user-signoff`。
- [ ] 8.5 实现售后报废事实和补发需求，报废不入库、不恢复库存、不计已补发；库存不足部分只能创建关联售后单和原订单明细的售后补发生产计划。
  - 证据契约：Requirement/Scenario、正式文档章节、实际文件/API/表/路由、RED/GREEN 命令与退出码、关键断言；实现后逐项回填。
  - 人工证据：不适用；如涉及页面/打印/Electron，回填 `humanVisualConclusion.checklist`，状态为 `pending-user-signoff`。
- [ ] 8.6 实现售后库存领用，兼容批次扣库存后只增加售后可补发；生产合格同样只增加可补发，二者均不得直接增加已补发。
  - 证据契约：Requirement/Scenario、正式文档章节、实际文件/API/表/路由、RED/GREEN 命令与退出码、关键断言；实现后逐项回填。
  - 人工证据：不适用；如涉及页面/打印/Electron，回填 `humanVisualConclusion.checklist`，状态为 `pending-user-signoff`。
- [ ] 8.7 实现 `POST .../{caseId}/replacement-shipments` 和确认命令，锁售后余额并校验本次<=可补发/待补发；确认后增加售后已补发，原订单订购、累计发货、未交付和应收不变。
  - 证据契约：Requirement/Scenario、正式文档章节、实际文件/API/表/路由、RED/GREEN 命令与退出码、关键断言；实现后逐项回填。
  - 人工证据：不适用；如涉及页面/打印/Electron，回填 `humanVisualConclusion.checklist`，状态为 `pending-user-signoff`。
- [ ] 8.8 实现关联售后单的退款，单列售后退款并更新累计实际净收；不冲减订单结清净额、不产生原订单新的待收/待退，订单无论仍在履约或已经关闭均保持主状态；首期不支持售后补差价、补应收或补收款。
  - 证据契约：Requirement/Scenario、正式文档章节、实际文件/API/表/路由、RED/GREEN 命令与退出码、关键断言；实现后逐项回填。
  - 人工证据：不适用；如涉及页面/打印/Electron，回填 `humanVisualConclusion.checklist`，状态为 `pending-user-signoff`。
- [ ] 8.9 实现售后更正记录，退回核验等不可覆盖事实出现错误时通过新更正事实处理，保留原值和来源链。
  - 证据契约：Requirement/Scenario、正式文档章节、实际文件/API/表/路由、RED/GREEN 命令与退出码、关键断言；实现后逐项回填。
  - 人工证据：不适用；如涉及页面/打印/Electron，回填 `humanVisualConclusion.checklist`，状态为 `pending-user-signoff`。
- [ ] 8.10 实现 `/orders/:id` 内“发货与售后”只读 Tab 的售后区域：从已确认发货批次明细查看售后占用、退回核验、返工/报废、库存/生产补发来源、可补发/已补发、补发发货、退款和历史；显式进入处理操作，不提供顶级售后业务模块。
  - 证据契约：Requirement/Scenario、正式文档章节、实际文件/API/表/路由、RED/GREEN 命令与退出码、关键断言；实现后逐项回填。
  - 人工证据：不适用；如涉及页面/打印/Electron，回填 `humanVisualConclusion.checklist`，状态为 `pending-user-signoff`。
- [ ] 8.11 编写领域/HTTP/MySQL 测试覆盖部分发货即可创建、无效/重复/超量来源拒绝、批次售后占用上限、退回等式、退回不入库、来源独立、补发确认时点、原订单统计不变、售后退款不改变结清口径和主状态、并发补发上限。
  - 证据契约：Requirement/Scenario、正式文档章节、实际文件/API/表/路由、RED/GREEN 命令与退出码、关键断言；实现后逐项回填。
  - 人工证据：不适用；如涉及页面/打印/Electron，回填 `humanVisualConclusion.checklist`，状态为 `pending-user-signoff`。
- [ ] 8.12 阶段人工验收：在部分发货且订单仍已确认时，从订单“发货与售后”Tab 对已确认批次创建售后并执行退回返工、报废、库存补发、生产补发、补发发货和退款；再验证剩余订单继续履约、售后与原订单台账分离、已关闭订单仍可按同一规则处理；视觉结论待签字。
  - 证据契约：Requirement/Scenario、正式文档章节、实际文件/API/表/路由、RED/GREEN 命令与退出码、关键断言；实现后逐项回填。
  - 人工证据：不适用；如涉及页面/打印/Electron，回填 `humanVisualConclusion.checklist`，状态为 `pending-user-signoff`。

## 9. 阶段九：查询导出、打印、发布与最终追踪（依赖阶段一至八）

- [ ] 9.1 实现 `GET /api/reports/{type}` 查询订单履约、库存、生产、发货、收退款和售后台账，筛选和分页均由服务端执行；结果只读且来自事实/投影。
  - 证据契约：Requirement/Scenario、正式文档章节、实际文件/API/表/路由、RED/GREEN 命令与退出码、关键断言；实现后逐项回填。
  - 人工证据：不适用；如涉及页面/打印/Electron，回填 `humanVisualConclusion.checklist`，状态为 `pending-user-signoff`。
- [ ] 9.2 实现 `GET /api/reports/{type}/export`，固定业务字段清单并验证不含物流公司、单号、运费、发货备注；金额保持字符串，历史资料使用快照。
  - 证据契约：Requirement/Scenario、正式文档章节、实际文件/API/表/路由、RED/GREEN 命令与退出码、关键断言；实现后逐项回填。
  - 人工证据：不适用；如涉及页面/打印/Electron，回填 `humanVisualConclusion.checklist`，状态为 `pending-user-signoff`。
- [ ] 9.3 实现订单/发货/台账打印与 PDF 数据准备，历史客户/商品/收货使用确认快照，发货物流使用当前有效物流；Electron 调用薄壳打印而不复制业务计算。
  - 证据契约：Requirement/Scenario、正式文档章节、实际文件/API/表/路由、RED/GREEN 命令与退出码、关键断言；实现后逐项回填。
  - 人工证据：不适用；如涉及页面/打印/Electron，回填 `humanVisualConclusion.checklist`，状态为 `pending-user-signoff`。
- [ ] 9.4 实现事实重建和投影一致性检查，至少覆盖履约余额、库存批次、售后可补发；不一致时产生失败证据而非静默覆盖。
  - 证据契约：Requirement/Scenario、正式文档章节、实际文件/API/表/路由、RED/GREEN 命令与退出码、关键断言；实现后逐项回填。
  - 人工证据：不适用；如涉及页面/打印/Electron，回填 `humanVisualConclusion.checklist`，状态为 `pending-user-signoff`。
- [ ] 9.5 完成生产结构化日志、指标、慢查询、HTTP 5xx、磁盘、连接池、文件不可写和备份失败告警配置；执行秘密扫描并证明日志脱敏。
  - 证据契约：Requirement/Scenario、正式文档章节、实际文件/API/表/路由、RED/GREEN 命令与退出码、关键断言；实现后逐项回填。
  - 人工证据：不适用；如涉及页面/打印/Electron，回填 `humanVisualConclusion.checklist`，状态为 `pending-user-signoff`。
- [ ] 9.6 建立发布流程：配置/磁盘/连接检查→MySQL+文件一致备份→临时 MySQL `flyway validate/migrate`→正式迁移→启动→Hibernate validate→健康检查→只读冒烟→恢复流量。
  - 证据契约：Requirement/Scenario、正式文档章节、实际文件/API/表/路由、RED/GREEN 命令与退出码、关键断言；实现后逐项回填。
  - 人工证据：不适用；如涉及页面/打印/Electron，回填 `humanVisualConclusion.checklist`，状态为 `pending-user-signoff`。
- [ ] 9.7 建立 MySQL 全量/发布前备份、文件快照、异地加密保存和恢复脚本；在隔离环境完成一次同恢复点演练并验证迁移、事实投影和关键只读路径。
  - 证据契约：Requirement/Scenario、正式文档章节、实际文件/API/表/路由、RED/GREEN 命令与退出码、关键断言；实现后逐项回填。
  - 人工证据：不适用；如涉及页面/打印/Electron，回填 `humanVisualConclusion.checklist`，状态为 `pending-user-signoff`。
- [ ] 9.8 建立 OpenAPI/契约清单，对 `design.md` API 矩阵逐项核对方法、路径、认证、幂等、状态、请求/响应金额字符串和错误码；缺少任何端点或场景时不得进入上线验收。
  - 证据契约：Requirement/Scenario、正式文档章节、实际文件/API/表/路由、RED/GREEN 命令与退出码、关键断言；实现后逐项回填。
  - 人工证据：不适用；如涉及页面/打印/Electron，回填 `humanVisualConclusion.checklist`，状态为 `pending-user-signoff`。
- [ ] 9.9 建立前端路由清单，对 `design.md` 页面矩阵逐项核对正式路由、可达操作、错误处理和浏览器/Electron 共用；预览路由或不可达页面不得作为验收证据。
  - 证据契约：Requirement/Scenario、正式文档章节、实际文件/API/表/路由、RED/GREEN 命令与退出码、关键断言；实现后逐项回填。
  - 人工证据：不适用；如涉及页面/打印/Electron，回填 `humanVisualConclusion.checklist`，状态为 `pending-user-signoff`。
- [ ] 9.10 建立双向追踪表，逐条连接六份 spec 的 Requirement/Scenario、API、Java 模块/应用服务、Flyway 表/约束、前端路由/操作、自动测试、人工验收和本任务编号；孤立规格或无规格实现均视为失败。
  - 证据契约：Requirement/Scenario、正式文档章节、实际文件/API/表/路由、RED/GREEN 命令与退出码、关键断言；实现后逐项回填。
  - 人工证据：不适用；如涉及页面/打印/Electron，回填 `humanVisualConclusion.checklist`，状态为 `pending-user-signoff`。
- [ ] 9.11 运行完整自动化门禁：`./mvnw test`、Modulith、Testcontainers 空库与升级迁移、并发门禁、前端 typecheck/unit/browser、Electron build/print、OpenAPI 契约和 `openspec validate build-yumi-v2-order-fulfillment --strict`，记录命令、退出码和失败数。
  - 证据契约：Requirement/Scenario、正式文档章节、实际文件/API/表/路由、RED/GREEN 命令与退出码、关键断言；实现后逐项回填。
  - 人工证据：不适用；如涉及页面/打印/Electron，回填 `humanVisualConclusion.checklist`，状态为 `pending-user-signoff`。
- [ ] 9.12 在正式路由执行基础资料→订单确认→库存或生产→发货→收退款→关闭→售后的完整人工路径；报告写逐项 `humanVisualConclusion.checklist`、已知偏差和取代关系，状态保持 `pending-user-signoff` 直到用户确认。
  - 证据契约：Requirement/Scenario、正式文档章节、实际文件/API/表/路由、RED/GREEN 命令与退出码、关键断言；实现后逐项回填。
  - 人工证据：不适用；如涉及页面/打印/Electron，回填 `humanVisualConclusion.checklist`，状态为 `pending-user-signoff`。
- [ ] 9.13 用户确认人工视觉结论后，补 `confirmedBy`、`confirmedOn`、`conclusion`，再勾选相关人工验收和本项；未确认不得以截图、无重叠或自动化通过代替。
  - 证据契约：Requirement/Scenario、正式文档章节、实际文件/API/表/路由、RED/GREEN 命令与退出码、关键断言；实现后逐项回填。
  - 人工证据：不适用；如涉及页面/打印/Electron，回填 `humanVisualConclusion.checklist`，状态为 `pending-user-signoff`。
- [ ] 9.14 最终范围审计：确认工资、完整财务、员工登录、复杂角色、离线同步、在线支付、原材料库存、自动批次合并、微服务、消息队列和 Kubernetes 未被提前实现，并列出后续 change 候选但不创建。
  - 证据契约：Requirement/Scenario、正式文档章节、实际文件/API/表/路由、RED/GREEN 命令与退出码、关键断言；实现后逐项回填。
  - 人工证据：不适用；如涉及页面/打印/Electron，回填 `humanVisualConclusion.checklist`，状态为 `pending-user-signoff`。

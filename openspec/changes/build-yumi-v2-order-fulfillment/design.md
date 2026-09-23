## Context

本 change 是 YUMI V2 的绿地重建。正式需求、领域数量模型、逻辑数据库、部署拓扑和路线图位于 `docs/requirements`、`docs/architecture` 和 `docs/delivery`；更细的已确认业务规则位于项目记忆 `yumi-v2-order-field-confirmations.md`。当前制品已有九阶段骨架，但缺少可冻结的接口、页面、错误和证据契约。

目标运行形态是单云服务器上的 Java 模块化单体：浏览器与 Electron 共享 React/TypeScript 前端，通过 HTTPS 调用 Spring Boot；Spring Boot 通过 JPA/Flyway 访问 MySQL；Electron 只负责窗口、打印、文件选择和系统集成。

## Goals / Non-Goals

**Goals:**

- 为六份 capability spec 提供可测试的行为、输入、输出、拒绝条件和状态约束。
- 为每项业务能力冻结 HTTP 方法、路径、认证、幂等键、请求/响应重点、成功状态和错误码。
- 为每个阶段冻结页面/路由/操作入口，明确订单内部组件不得被误建成顶级模块；订单详情采用一组订单内多 Tab，视觉基线为常规 Ant Design 后台管理布局、全页白色底、浅色侧栏与顶部面包屑，使用现成组件并通过按钮层级、Tag 样式、间距、边框和主题 token 表达层次，不并排展示 A/B 方案或重复订单页签。
- 保持共同数量 Q/E、库存领用一次扣减、生产核验等式、订单关闭条件和售后独立台账；已确认发货后即可受理对应已发部分售后。
- 让实现任务同时指向 Requirement/Scenario、正式文档章节、后端包/Flyway、API、前端、自动化测试和人工验收。
- 以 Java 21、Spring Boot 3.5.x、Spring Modulith、Spring Data JPA、Flyway、Maven、MySQL 8.x、JUnit 5、Testcontainers 为实现基线。

**Non-Goals:**

- 本 change 不实现应用代码、SQL、页面或部署脚本。
- 不迁移旧 SQLite，不兼容旧页面、旧 IPC 或旧表结构。
- 不实现员工登录、复杂角色、工资、完整财务、在线支付、原材料库存、离线同步、微服务、消息队列或 Kubernetes。
- 不允许实现阶段自行新增状态、字段、接口语义或把独立表解释为独立顶级模块。

## Decisions

### 1. 一个 change，九个有依赖阶段

保持 `platform-foundation`、`master-data-management`、`order-lifecycle`、`inventory-management`、`production-management`、订单发货、收退款与关闭、售后、reporting/operations 的九阶段顺序。阶段五可以提前创建等待上游计划，但库存接入场景依赖阶段四；阶段六依赖库存和生产；阶段八依赖库存、生产、发货和退款。每项任务必须在 `tasks.md` 中写明前置任务和完成证据。

### 2. 业务 API 使用命令端点，不提供通用状态覆盖

查询使用 `GET`；业务变化使用语义命令 `POST`，例如确认、核验、领用、关闭、作废和更正。成功响应至少返回业务编号、当前派生状态、权威数量/金额和审计时间；写命令要求 `Authorization` 和 `Idempotency-Key`。重复幂等键返回第一次成功结果或同一业务冲突，不重复写事实。

### 3. 错误码分层且稳定

错误响应统一为 `{code, message, fieldErrors, requestId}`。`AUTH_*` 表示认证，`VALIDATION_*` 表示边界输入，`STATE_*` 表示生命周期，`CONFLICT_*` 表示并发/版本/幂等，`QUANTITY_*` 表示数量不变量，`SOURCE_*` 表示来源余额或重复消费，`FINANCE_*` 表示收退款，`MIGRATION_*` 表示发布门禁。前端按 code 显示可执行动作，不按 message 反解析业务。

### 4. 单库事务拥有者是发起业务命令的应用服务

订单确认、订单变更确认、库存领用/取消、生产核验、发货确认/作废/更正、收退款、售后核验/补发、订单关闭和超额预占均在一个 Spring `@Transactional` 事务内完成。事务内以稳定顺序锁定订单明细履约余额、库存批次、返工/重做来源余额、计划或资金投影；使用 `@Version`、`PESSIMISTIC_WRITE`/`FOR UPDATE`、唯一约束和幂等键。失败必须整体回滚。

### 5. 事实不可变，投影可重建

库存流水、履约事实、生产核验、发货确认、收款、退款、售后核验和更正记录不提供普通编辑/删除。当前数量和状态投影由事实在同一事务中更新，并提供重建/一致性检查。发货确认只消耗订单可发货投影；库存领用已经扣减原批次，发货不得再次扣减原库存。

### 6. API 契约矩阵

以下是实现前必须冻结的最小命令/查询面；每个路径都必须在对应 spec 和 tasks 中有 Requirement/Scenario、后端、前端和测试追踪。

| 能力 | 方法与路径 | 认证/幂等 | 成功结果 | 主要拒绝码 |
| --- | --- | --- | --- | --- |
| 登录 | `POST /api/session` | 匿名/否 | 会话与管理员摘要 | `AUTH_INVALID` |
| 商品/客户/员工 | `GET/POST/PATCH /api/products|customers|employees` | 管理员/写入幂等 | 当前资料与编号 | `VALIDATION_INVALID`, `STATE_DISABLED`, `CONFLICT_DUPLICATE` |
| 订单 | `GET/POST /api/orders`、`GET /api/orders/{id}`、`PATCH /api/orders/{id}` | 管理员/草稿写入幂等 | 草稿、明细、金额 | `STATE_NOT_EDITABLE`, `QUANTITY_INVALID` |
| 确认 | `POST /api/orders/{id}/confirm` | 管理员/必须幂等 | 快照与已确认订单 | `STATE_NOT_CONFIRMABLE`, `SNAPSHOT_FAILED` |
| 变更 | `POST /api/orders/{id}/change-orders`、`POST /api/order-changes/{id}/confirm` | 管理员/必须幂等 | 变更及新投影 | `STATE_NOT_CHANGEABLE`, `QUANTITY_BELOW_SHIPPED`, `REFUND_PENDING` |
| 订单取消 | `POST /api/orders/{id}/cancel` | 管理员/必须幂等 | 已取消或拒绝 | `STATE_CANCEL_NOT_ALLOWED`, `QUANTITY_REQUIRES_DISPOSITION` |
| 履约视图 | `GET /api/orders/{id}/fulfillment` | 管理员/否 | Q/E、工序、需求和发货派生状态 | `ORDER_NOT_FOUND` |
| 库存 | `GET/POST /api/inventory/batches`、`POST /api/inventory/adjustments` | 管理员/写入幂等 | 批次和流水 | `STOCK_NEGATIVE`, `FACT_IMMUTABLE` |
| 领用 | `POST /api/inventory-allocations`、`POST /api/inventory-allocations/{id}/cancel` | 管理员/必须幂等 | 领用、反向流水、履约接入 | `STOCK_INSUFFICIENT`, `SOURCE_ALREADY_CONSUMED`, `STATE_CANNOT_CANCEL` |
| 生产计划 | `GET/POST /api/production-plans`、`POST /api/production-plans/{id}/cancel` | 管理员/写入幂等 | 计划与等待上游状态 | `EMPLOYEE_NOT_ELIGIBLE`, `SOURCE_INSUFFICIENT`, `STATE_NOT_CANCELABLE` |
| 核验 | `POST /api/production-plans/{id}/verify` | 管理员/必须幂等 | 一次性核验与来源 | `VERIFICATION_EQUATION_INVALID`, `STATE_ALREADY_VERIFIED`, `QUANTITY_NOT_EXECUTABLE` |
| 返工/重做 | `POST /api/rework-sources`、`POST /api/remake-sources` | 管理员/必须幂等 | 来源余额与计划入口 | `REWORK_TARGET_INVALID`, `REMAKE_REASON_REQUIRED` |
| 超额 | `POST /api/overtime-tasks`、`POST /api/overtime-tasks/{id}/verify` | 管理员/必须幂等 | 预占或计划调整提醒 | `OVERTIME_DATE_INVALID`, `OVERTIME_RESERVATION_EXCEEDED` |
| 发货 | `GET/POST /api/orders/{id}/shipments`、`POST /api/orders/{id}/shipments/{shipmentId}/confirm` | 管理员/必须幂等 | 冻结发货快照 | `SHIPMENT_EXCEEDS_AVAILABLE`, `SHIPMENT_EXCEEDS_DEMAND` |
| 发货作废/更正 | `POST .../void`、`POST .../corrections`、`PATCH .../logistics` | 管理员/必须幂等 | 反向事实或等量替代 | `STATE_CLOSED_REQUIRES_CORRECTION`, `CORRECTION_REPLACEMENT_REQUIRED`, `SHIPMENT_AFTER_SALES_LINKED` |
| 收款/退款 | `POST /api/orders/{id}/payments`、`POST /api/orders/{id}/refunds` | 管理员/必须幂等 | 不可变资金事实；售后退款单列于订单结清 | `PAYMENT_DRAFT_FORBIDDEN`, `REFUND_EXCEEDS_RECEIPTS`, `REFUND_REFERENCE_REQUIRED` |
| 关闭 | `POST /api/orders/{id}/close` | 管理员/必须幂等 | 已关闭或条件明细 | `CLOSE_FULFILLMENT_PENDING`, `CLOSE_SETTLEMENT_PENDING`, `CLOSE_REFUND_PENDING` |
| 售后 | `POST /api/orders/{id}/after-sales`、`POST .../{caseId}/verify-return` | 管理员/必须幂等 | 关联有效已确认发货批次明细的独立售后台账 | `AFTER_SALES_SOURCE_INVALID`, `AFTER_SALES_QUANTITY_EXCEEDED`, `AFTER_SALES_EQUATION_INVALID` |
| 售后补发 | `POST .../{caseId}/replacement-shipments`、`POST .../confirm` | 管理员/必须幂等 | 售后已补发增加，原订单不变 | `AFTER_SALES_REPLACEMENT_INSUFFICIENT` |
| 查询导出 | `GET /api/reports/{type}`、`GET .../export` | 管理员/否 | 服务端事实导出 | `REPORT_TYPE_INVALID` |
| 健康/迁移 | `GET /actuator/health/liveness|readiness` | 受保护运维 | 健康结果 | `MIGRATION_INVALID` |

### 7. 前端页面、路由和操作矩阵

React 页面必须由业务入口驱动，Electron 复用相同路由和 API；物流字段只在发货详情/打印/PDF显示，业务导出隐藏物流字段。`/orders/:id` 使用订单内一组多 Tab 承载只读事实，Tab 切换不创建详情路由；新建、编辑、确认和处理必须由显式入口进入独立操作状态，不在详情预置表单。订单详情使用常规 Ant Design 后台管理布局：全页白色底、浅色侧栏、顶部面包屑和单组订单页签；使用 Ant Design 现成组件，通过按钮层级、Tag 样式、间距、边框和主题 token 表达视觉层次，不在同一页面并排展示 A/B 方案或重复订单页签。`.superpowers/brainstorm/39592-1790147910/content/order-detail-antd-admin-v1.html` 是当前视觉基线草图；结构与业务分组可作为实现和人工验收参考，具体尺寸可在不改变上述基线的前提下调整。

| 路由 | 页面 | 关键操作 | 验收观察点 |
| --- | --- | --- | --- |
| `/login` | 登录 | 登录/错误重试 | 未认证不可进入业务页 |
| `/catalog/products` | 商品 | 新建、编辑、启停、历史 | 停用商品不能进入新订单 |
| `/catalog/customers` | 客户 | 新建、重复提示、编辑、详情汇总 | 不自动合并重复客户 |
| `/catalog/employees` | 员工 | 工种、离职、重新入职、历史 | 离职或无资格员工不能新排班 |
| `/orders` | 订单列表 | 筛选、新建、打开订单 | 主状态/生产/发货进度分列 |
| `/orders/new` | 订单步骤工作区 | 客户、明细、Q/E、金额、收货、确认 | 服务端金额和快照权威 |
| `/orders/:id` | 订单详情 | 总览、商品与履约、发货与售后、资金与利润、资料与变更 Tab；显式进入变更、取消、发货、收退款、售后、关闭操作 | 总览可核对 12+ 明细；履约按阶段看事实；只读与操作分离；已确认发货可受理售后 |
| `/orders/:id/changes/:changeId` | 变更确认操作 | 变更前后、超出处理、确认 | 减单必须逐项处理余量；确认后回到订单只读详情 |
| `/inventory` | 库存工作区 | 批次、流水、调整、领用 | 领用扣一次，发货不二扣；订单内低频领用就近进入 |
| `/production` | 生产工作台 | 排班、等待上游、核验、提醒 | 未完成和超额提醒独立可处理 |
| `/production/plans/:id/verify` | 核验操作 | 完成/合格/返工/报废 | 等式与一次核验错误可见 |
| `/reports` | 台账与导出 | 查询、导出、打印/PDF | 导出无物流字段 |
| Electron shell | 桌面壳 | 打印、文件选择、窗口 | 不直连数据库、不复制业务规则 |

订单内发货批次、售后单、资金流水和变更事实均从相应 Tab 查看，不设 `/shipments` 顶级工作区；需要复杂录入时可使用订单上下文的独立操作页，但不为只读事实逐级叠加路由。

### 7.1 售后来源和结清口径

创建售后必须引用已确认且有效的发货批次明细，并在事务内锁定该明细的有效已发余额与售后已占用量；新受理量不能超过剩余可受理量。售后可在订单仍部分履约时并行进行；原发货已被有效售后引用时，不得直接作废或更正为无效来源。订单结清净额为累计订单收款减累计订单变更退款，订单待退款为 `max(累计订单收款 - 当前有效应收 - 累计订单变更退款, 0)`；售后退款独立关联售后单，进入累计实际净收，不冲减结清净额、不产生新的订单待收/待退。全部退款仍不得超过累计订单收款。订单关闭重验只使用订单结清口径，售后占用和售后退款不改变原订单履约、应收或主状态。

### 8. 数据库与 Flyway 设计

数据库表按顶级模块归属：`identity`、`catalog`、`orders`、`inventory`、`production`、`files`。`shipments`、`payments`、`refunds`、`after_sales_*` 和履约表虽独立建表，仍由 `orders` 拥有。金额为 `DECIMAL(19,4)`，比例为 `DECIMAL(9,6)`，数量为非负整数；唯一约束覆盖业务编号、一次核验、来源消费、幂等键和订单状态转换。Flyway 是唯一结构来源，Hibernate 仅 `ddl-auto: validate`。

### 9. 测试和人工证据

自动化证据包括 JUnit 领域公式、Spring Boot 事务集成、Testcontainers MySQL 迁移/约束/锁、Spring Modulith 模块边界、HTTP 契约、React 类型与浏览器测试、Electron 构建/打印测试、事实重建和并发测试。人工视觉结论不能由机器测试替代；每个需要人眼确认的任务必须记录 `humanVisualConclusion.checklist`，状态先为 `pending-user-signoff`，用户确认后补 `confirmedBy`、`confirmedOn` 和 `conclusion`。

## Risks / Trade-offs

- [契约矩阵与实现漂移] → 每个 API 必须同时有 spec 场景、后端任务、前端入口、HTTP 测试和完成证据；缺任一项不得勾选任务。
- [订单模块过大] → 只在 `orders` 内拆内部组件，使用公开应用服务和模块边界测试，禁止把物理表误当顶级模块。
- [数量重复计算] → 以领域数量模型的 Q/E 和事实重建测试为门禁；工序数量不得相加，发货不得再次扣库存。
- [并发超卖] → 事务内锁权威余额和来源行，Testcontainers 覆盖领用、核验、发货、关闭和超额预占竞争。
- [前端无法到达验收入口] → 人工验收只能使用正式路由；预览路由不作为完成证据。
- [数据库迁移不可逆] → 发布前临时库校验，已执行版本不得修改，失败时停止发布并保留恢复点。

## Migration Plan

1. 先完成阶段一基础工程、认证、错误、迁移、审计和客户端边界，再进入基础资料。
2. 依次实施订单核心、库存、生产、订单发货、收退款与关闭、售后、报表与运行保障；每阶段必须先完成前置阶段的自动和人工证据。
3. 每次发布先备份 MySQL 与文件，在临时同版本 MySQL 执行 `flyway validate/migrate`，再迁移正式库、启动应用、检查 Hibernate validate/健康状态并执行只读冒烟。
4. 不提供旧系统数据回迁或双写回滚；迁移失败停止发布，已执行结构只能通过新增版本修正。

## Open Questions

无。会改变规格、接口矩阵、页面矩阵或阶段依赖的问题已经确定；实现阶段仅可选择不改变外部契约的类名、DTO 内部组织和页面视觉细节。

## Context

本 change 是 YUMI V2 的绿地重建。正式需求、领域数量模型、逻辑数据库、部署拓扑和路线图位于 `docs/requirements`、`docs/architecture` 和 `docs/delivery`；更细的已确认业务规则位于项目记忆 `yumi-v2-order-field-confirmations.md`。当前制品已有九阶段骨架，但缺少可冻结的接口、页面、错误和证据契约。

目标运行形态是单云服务器上的 Java 模块化单体：浏览器与 Electron 共享 React/TypeScript 前端，通过 HTTPS 调用 Spring Boot；Spring Boot 通过 JPA/Flyway 访问 MySQL；Electron 只负责窗口、打印、文件选择和系统集成。

## Goals / Non-Goals

**Goals:**

- 为六份 capability spec 提供可测试的行为、输入、输出、拒绝条件和状态约束。
- 为每项业务能力冻结 HTTP 方法、路径、认证、幂等键、请求/响应重点、成功状态和错误码。
- 为每个阶段冻结页面/路由/操作入口，明确订单内部组件不得被误建成顶级模块；订单详情采用一组订单内多 Tab，视觉基线为常规 Ant Design 后台管理布局、全页白色底、浅色侧栏与顶部面包屑，使用现成组件并通过按钮层级、Tag 样式、间距、边框和主题 token 表达层次，不并排展示 A/B 方案或重复订单页签。
- 保持共同数量（订购数量与缝边数量）、库存领用一次扣减、生产任务逐明细核验等式、订单关闭条件和售后独立台账；已确认发货后即可受理对应已发部分售后。
- 让实现任务同时指向 Requirement/Scenario、正式文档章节、后端包/Flyway、API、前端、自动化测试和人工验收。
- 以 Java 21、Spring Boot 3.5.x、Spring Modulith、Spring Data JPA、Flyway、Maven、MySQL 8.x、JUnit 5、Testcontainers 为实现基线。

**Non-Goals:**

- 本 change 不实现应用代码、SQL、页面或部署脚本。
- 不迁移旧 SQLite，不兼容旧页面、旧 IPC 或旧表结构。
- 不实现员工登录、复杂角色、工资、完整财务、在线支付、原材料库存、离线同步、微服务、消息队列或 Kubernetes。
- 不允许实现阶段自行新增状态、字段、接口语义或把独立表解释为独立顶级模块。

## Decisions

### 1. 一个 change，九个有依赖阶段

保持 `platform-foundation`、`master-data-management`、`order-lifecycle`、`inventory-management`、`production-management`、订单发货、收退款与关闭、售后、reporting/operations 的九阶段顺序。阶段五可以提前创建等待上游的生产任务，但库存接入场景依赖阶段四；阶段六依赖库存和生产；阶段八依赖库存、生产、发货和退款。每项任务必须在 `tasks.md` 中写明前置任务和完成证据。

### 2. 业务 API 使用命令端点，不提供通用状态覆盖

查询使用 `GET`；业务变化使用语义命令 `POST`，例如确认、核验、领用、关闭、作废和更正。成功响应至少返回业务编号、当前派生状态、权威数量/金额和审计时间；写命令要求 `Authorization` 和 `Idempotency-Key`。重复幂等键返回第一次成功结果或同一业务冲突，不重复写事实。

所有 API 响应（成功与失败）统一使用同一信封：`{code, message, fieldErrors, requestId, data}`。成功时 `code="OK"`、`message=""`、`fieldErrors=[]`、`data` 为业务负载；失败时 `code` 为稳定错误码、`message` 为可读描述、`fieldErrors` 携带字段定位、`data=null`。HTTP 状态码保持语义（200/201/204、400/401/404/409、500/503），`204` 无响应体。前端只在 HTTP 封装层解析信封并统一处理错误提示，调用处仅处理当前业务的提示；每个响应同时携带 `X-Request-Id` 头作为日志与审计的追踪入口。

### 3. 错误码分层且稳定

错误响应统一为 `{code, message, fieldErrors, requestId, data}`（`data=null`）。`AUTH_*` 表示认证，`VALIDATION_*` 表示边界输入，`STATE_*` 表示生命周期，`CONFLICT_*` 表示并发/版本/幂等，`QUANTITY_*` 表示数量不变量，`SOURCE_*` 表示来源余额或重复消费，`FINANCE_*` 表示收退款，`MIGRATION_*` 表示发布门禁。前端按 code 显示可执行动作，不按 message 反解析业务。

### 4. 单库事务拥有者是发起业务命令的应用服务

订单确认、订单变更确认、库存领用/取消、生产任务创建/取消、生产任务逐明细核验、发货确认/作废/更正、收退款、售后核验/补发、订单关闭和超额预占均在一个 Spring `@Transactional` 事务内完成。事务内以稳定顺序锁定订单明细履约余额、库存批次、返工来源余额、生产任务明细或资金投影；普通生产任务类型仅为 `NORMAL`/`REWORK`，明细保存来源、明线/暗线、标准分钟和标准工时快照，`NORMAL` 按产品当日产能校验，`REWORK` 不占正常产能；超额是独立预占，不是普通生产任务类型。使用 `@Version`、`PESSIMISTIC_WRITE`/`FOR UPDATE`、唯一约束和幂等键。失败必须整体回滚。

### 5. 事实不可变，投影可重建

库存流水、履约事实、生产核验、发货确认、收款、退款、售后核验和更正记录不提供普通编辑/删除。当前数量和状态投影由事实在同一事务中更新，并提供重建/一致性检查。发货确认只消耗订单可发货投影；库存领用已经扣减原批次，发货不得再次扣减原库存。

### 5.1 统一计算模块与服务端试算（2026-09-24 修订）

依据 `docs/architecture/formula-management-design.md`，新增 `com.yumi.calculation` 支撑模块，内部按 product/order/inventory/production 分类维护公式；未建设分类随业务接入，不创建空实现。业务服务单向调用公开命名接口，计算模块只接收不可变数值输入并返回结果，不访问数据库、业务实体、当前时间或登录上下文。事务、事实筛选、引用解析、并发锁、状态与持久化仍归业务模块。商品原 `catalog/product/internal/ProductPricing` 迁入集中模块，旧实现删除；未来工资/财务按此边界接入，但本期不实施。

商品创建、编辑和对应试算共用输入解析逻辑与集中计算入口：新建使用当前设置，编辑未换引用保持商品星级/档位快照，仅换成不同引用时取当前值，材料价格只在明确刷新时重读。编辑页在检测到商品快照与当前全局设置不一致时给出提示，管理员可一键请求按最新全局设置重算（星级时长、档位值与材料单价一并刷新）；未请求时继续沿用快照，保存后快照才更新为当前全局值。同一请求在一致读取边界解析配置；正式保存在事务内重新校验和计算，不采信前端派生金额。配置不变时试算和保存逐项相同；两次请求间配置改变时以保存结果为准，页面提示金额变化。

新增 `POST /api/products/preview` 和 `POST /api/products/{id}/preview`，前者接收计算字段及引用，后者另带商品版本号并沿用 PATCH 缺省合并语义。名称/图片等非计算字段未填不阻止试算；计算字段缺失、非法或引用无效返回 400 `VALIDATION_INVALID` + fieldErrors，商品不存在返回 404 `NOT_FOUND`，旧版本返回 409 `CONFLICT_VERSION`，未登录返回 401 `AUTH_REQUIRED`。成功 200 统一信封，data 包含完整成本分项、参考售价/缝边收费、标准数量、利润和利润率及百分比展示文本；金额与比例均为字符串。

这两个 POST 是只读试算，不是业务写命令：不要求 Idempotency-Key，不写幂等记录和业务写审计，不分配编号、不改商品或快照；保留认证、安全审计与 requestId 日志。过滤器仅对这两个精确方法/路径组合豁免写入策略，不用宽泛的 `/preview` 后缀匹配，其他写命令的幂等和审计不变。

前端删除 computePreview 和本地公式副本，保持现有布局，计算字段有效后约 300ms 防抖请求；输入改变即将旧结果标记为非当前，取消旧请求并以请求序号拒绝过期响应。支持待填写、计算中、失败重试；断网不本地计算、不显示伪造零值；保存后取消在途试算并显示保存结果。此决策取代 2.10 历史证据中的 decimal.js A 案，历史记录保留但不再作为新实现要求。

设置页增加“公式说明”只读 Tab。页面通过认证的 `GET /api/settings/formulas` 读取 calculation 模块发布的静态目录，按业务分组展示稳定标识、公式名称、输入字段及单位、表达式、逐步舍入规则、结果含义和固定示例；不得编辑、保存、发布或修改公式。接口不读取公式数据库、不产生幂等记录、业务写审计、编号或业务事实，前端不复制公式计算逻辑。计算模块新增公式时必须同步目录和测试样例，未建设的工资/财务公式不展示空条目。

公式逐步舍入、常量及快照语义不变；不增加动态编辑、数据库公式表、公式版本表或历史批量重算。只有 Git/发布记录和结果快照时不承诺历史算法重演。集中迁移必须补充公式目录、算例、模块边界、试算/保存一致性、公式说明查询和浏览器/Electron 证据，详见 2.13–2.20。

### 5.2 静态数据与商品字段口径（2026-09-24 修订）

依据 `docs/architecture/static-data-and-product-fields-design.md`，把星级、包装档位、缝边种类、员工工种统一为**静态数据**：类别由系统预置且 code 定死，类别不可增删改名；星级、包装档位、缝边种类条目由用户自建，员工工种为系统预置 4 条（`MAKING`/`PACKING_BAG`/`SEAM_CUTTING`/`OTHER`，仅可改名，2026-09-25 起取消启用状态；员工关系按 `work_type_id` 存储、接口按 code 出入参）。条目被引用时禁删、名称在类别内唯一、改名改值不回溯既有快照，所有增删改记变更日志。

商品字段按归属划分：**商品属性**（名称、销售单价、克重、模具摊销费、商品说明）由商品持有并可改；**全局口径**（胶水损耗率、日常杂费、房租水电）在商品表单只读展示，保存时由服务端取当前全局值并冻结进商品快照；**全局默认 + 可改**（装箱人工费、运输包装费、包装提成）由服务端预填当前全局默认值、允许商品覆盖；**引用型**（制品星级、包装档位）从静态数据选择并冻结名称与标准分钟。

缝边**决策权在订单、商品提供默认值**：商品新增「默认缝边剪袋类型」（可空＝默认不缝边剪袋）与「缝边价格（元/件）」作为订单缝边定制的默认值；商品不保存缝边数量与缝边成本，商品总成本与参考售价按**不缝边剪袋**口径保存，试算与详情另给「缝边剪袋」变体单列展示。缝边作为**订单明细行的定制服务**在订单阶段实现：选缝边种类、填数量（可小于明细数量）与收费单价，默认值取自商品且可覆盖，选中种类带出单件缝边人工成本（= 缝边标准分钟 × 全局时薪 ÷ 60）作提示；缝边成本＝该单件成本 × 数量并**计入订单成本**，缝边收费计入应收，订单利润＝应收 − 商品成本 − 缝边成本。缝边种类的删除守卫为“被订单明细行或商品默认缝边剪袋类型引用”。

包装提成从包装档位移到商品：`packaging_tiers` 只提供标准分钟，FP-PROD-08 改为 `档位标准分钟 × 0.25 + 商品提成`。

开发期未上线、不考虑历史数据：改写 `V4`/`V5` 迁移为最终结构并清库重建，不做数据迁移；`database-design.md` 的“已执行迁移不得修改”限定为**上线后**。详见 2.21–2.27。

### 6. API 契约矩阵

> 错误码口径（2026-09-25 按实现校正）：矩阵中原先登记的 `STATE_DISABLED`、`SNAPSHOT_FAILED`、`STOCK_NEGATIVE`、`FACT_IMMUTABLE`、`SOURCE_ALREADY_CONSUMED`、`MIGRATION_INVALID` **在实现中从未返回**——对应行为或由其他错误码承载（如负数调整返回 `VALIDATION_INVALID`、重复冲销返回 `CONFLICT_DUPLICATE`、来源唯一由数据库唯一键保证），或结构上不可达（快照/迁移失败属启动期或数据库故障，应用直接失败而不返回该码）。已按下表逐行改为**实现实际返回**的码。

以下是实现前必须冻结的最小命令/查询面；每个路径都必须在对应 spec 和 tasks 中有 Requirement/Scenario、后端、前端和测试追踪。

| 能力 | 方法与路径 | 认证/幂等 | 成功结果 | 主要拒绝码 |
| --- | --- | --- | --- | --- |
| 登录 | `POST /api/session` | 匿名/否 | 会话与管理员摘要 | `AUTH_INVALID` |
| 商品试算 | `POST /api/products/preview`、`POST /api/products/{id}/preview` | 管理员/不要求幂等键（只读，精确豁免写过滤器） | 200；完整计算结果，金额/比例字符串，不产生业务写入 | `AUTH_REQUIRED`, `VALIDATION_INVALID`, `NOT_FOUND`, `CONFLICT_VERSION` |
| 公式说明 | `GET /api/settings/formulas` | 管理员/否 | 200；按业务分组的只读公式目录、输入/单位、舍入规则和固定示例，不产生业务写入 | `AUTH_REQUIRED`, `NOT_FOUND` |
| 商品/客户/员工 | `GET/POST/PATCH /api/products|customers|employees` | 管理员/写入幂等 | 当前资料与编号 | `VALIDATION_INVALID`（含停用商品被新订单引用）, `CONFLICT_DUPLICATE` |
| 订单 | `GET/POST /api/orders`、`GET /api/orders/{id}`、`PATCH /api/orders/{id}` | 管理员/草稿写入幂等 | 草稿、明细、金额 | `STATE_NOT_EDITABLE`, `QUANTITY_INVALID` |
| 确认 | `POST /api/orders/{id}/confirm` | 管理员/必须幂等 | 快照与已确认订单 | `STATE_NOT_CONFIRMABLE`（快照写入失败属数据库故障，由 500 兜底，不单独返回错误码） |
| 变更 | `POST /api/orders/{id}/change-orders`、`POST /api/order-changes/{id}/confirm` | 管理员/必须幂等 | 变更及新投影 | `STATE_NOT_CHANGEABLE`, `QUANTITY_BELOW_SHIPPED`, `REFUND_PENDING` |
| 订单取消 | `POST /api/orders/{id}/cancel` | 管理员/必须幂等 | 已取消或拒绝 | `STATE_CANCEL_NOT_ALLOWED`, `QUANTITY_REQUIRES_DISPOSITION` |
| 履约视图 | `GET /api/orders/{id}/fulfillment` | 管理员/否 | 订购数量/缝边数量、工序、需求和发货派生状态 | `ORDER_NOT_FOUND` |
| 库存 | `GET/POST /api/inventory/batches`、`POST /api/inventory/adjustments` | 管理员/写入幂等 | 批次和流水 | `VALIDATION_INVALID`（负数/非法调整）, `CONFLICT_DUPLICATE`（重复冲销） |
| 领用 | `POST /api/inventory-allocations`、`POST /api/inventory-allocations/{id}/cancel` | 管理员/必须幂等 | 领用、反向流水、履约接入 | `STOCK_INSUFFICIENT`, `STATE_CANNOT_CANCEL`（同一来源重复接入由来源唯一键保证） |
| 生产任务 | `GET/POST /api/production-tasks`、`POST /api/production-tasks/{id}/items/{itemId}/cancel` | 管理员/写入幂等 | 多订单多产品任务、`NORMAL`/`REWORK` 类型、明线/暗线、任务明细与等待上游状态 | `EMPLOYEE_NOT_ELIGIBLE`, `SOURCE_INSUFFICIENT`, `STATE_NOT_CANCELABLE`, `REWORK_SOURCE_INVALID` |
| 逐明细核验 | `POST /api/production-tasks/{id}/verify` | 管理员/必须幂等 | 一次性逐明细核验、完成/合格/返工/报废事实、返工来源与数量回转 | `VERIFICATION_EQUATION_INVALID`, `STATE_ALREADY_VERIFIED`, `QUANTITY_NOT_EXECUTABLE`, `SCRAP_QUANTITY_INVALID` |
| 超额预占与其他排班 | `POST /api/overtime-tasks`、`POST /api/overtime-tasks/{id}/verify`、`POST /api/other-schedule-tasks`、`POST /api/other-schedule-tasks/{id}/verify` | 管理员/必须幂等 | 独立超额预占或任务调整提醒、其他排班工时核验；超额不进入普通生产任务类型 | `OVERTIME_DATE_INVALID`, `OVERTIME_RESERVATION_EXCEEDED`, `WORKING_MINUTES_INVALID` |
| 发货 | `GET/POST /api/orders/{id}/shipments`、`POST /api/orders/{id}/shipments/{shipmentId}/confirm` | 管理员/必须幂等 | 冻结发货快照 | `SHIPMENT_EXCEEDS_AVAILABLE`, `SHIPMENT_EXCEEDS_DEMAND` |
| 发货作废/更正 | `POST .../void`、`POST .../corrections`、`PATCH .../logistics` | 管理员/必须幂等 | 反向事实或等量替代 | `STATE_CLOSED_REQUIRES_CORRECTION`, `CORRECTION_REPLACEMENT_REQUIRED`, `SHIPMENT_AFTER_SALES_LINKED` |
| 收款/退款 | `POST /api/orders/{id}/payments`、`POST /api/orders/{id}/refunds` | 管理员/必须幂等 | 不可变资金事实；售后退款单列于订单结清 | `PAYMENT_DRAFT_FORBIDDEN`, `REFUND_EXCEEDS_RECEIPTS`, `REFUND_REFERENCE_REQUIRED` |
| 关闭 | `POST /api/orders/{id}/close` | 管理员/必须幂等 | 已关闭或条件明细 | `CLOSE_FULFILLMENT_PENDING`, `CLOSE_SETTLEMENT_PENDING`, `CLOSE_REFUND_PENDING` |
| 售后 | `POST /api/orders/{id}/after-sales`、`POST .../{caseId}/verify-return` | 管理员/必须幂等 | 关联有效已确认发货批次明细的独立售后台账；发货可用数量依赖生产任务明细产出 | `AFTER_SALES_SOURCE_INVALID`, `AFTER_SALES_QUANTITY_EXCEEDED`, `AFTER_SALES_EQUATION_INVALID` |
| 售后补发 | `POST .../{caseId}/replacement-shipments`、`POST .../confirm` | 管理员/必须幂等 | 售后已补发增加，原订单不变 | `AFTER_SALES_REPLACEMENT_INSUFFICIENT` |
| 查询导出 | `GET /api/reports/{type}`、`GET .../export` | 管理员/否 | 服务端事实导出 | `REPORT_TYPE_INVALID` |
| 全局设置 | `GET/PATCH /api/settings` | 管理员/写入幂等 | 单价与默认值（胶水/色浆单价、损耗率、四项单件费用默认） | `VALIDATION_INVALID` |
| 静态数据 | `GET /api/settings/static-data`、`GET /api/settings/static-data/{code}`、`POST .../{code}/items`、`PATCH/DELETE .../items/{id}` | 管理员/写入幂等 | 类别清单与条目：星级/包装档位/缝边种类由用户自建，员工工种仅可改名与启停 | `VALIDATION_INVALID`, `CONFLICT_DUPLICATE`, `CONFLICT_REFERENCED` |
| 健康/迁移 | `GET /actuator/health/liveness|readiness` | 受保护运维 | 健康结果 | 迁移校验失败时**应用启动即失败**（不提供服务，等价于「就绪失败且不接受业务写入」） |

### 7. 前端页面、路由和操作矩阵

React 页面必须由业务入口驱动，Electron 复用相同路由和 API；物流字段只在发货详情/打印/PDF显示，业务导出隐藏物流字段。`/orders/:id` 使用订单内一组多 Tab 承载只读事实，Tab 切换不创建详情路由；新建、编辑、确认和处理必须由显式入口进入独立操作状态，不在详情预置表单。订单详情使用常规 Ant Design 后台管理布局：全页白色底、浅色侧栏、顶部面包屑和单组订单页签；使用 Ant Design 现成组件，通过按钮层级、Tag 样式、间距、边框和主题 token 表达视觉层次，不在同一页面并排展示 A/B 方案或重复订单页签。`.superpowers/brainstorm/39592-1790147910/content/order-detail-antd-admin-v1.html` 是当前视觉基线草图；结构与业务分组可作为实现和人工验收参考，具体尺寸可在不改变上述基线的前提下调整。

| 路由 | 页面 | 关键操作 | 验收观察点 |
| --- | --- | --- | --- |
| `/login` | 登录 | 登录/错误重试 | 未认证不可进入业务页 |
| `/catalog/products` | 商品 | 新建、编辑、启停、历史、服务端试算 | 停用商品不能进入新订单；预估与保存同一后端公式；旧响应不覆盖新输入，断网不本地计算；全局口径字段（损耗率/杂费/房租水电）只读展示，包装提成取全局默认可改，商品含「默认缝边剪袋类型 + 缝边价格」默认值但不含缝边数量/成本 |
| `/catalog/customers` | 客户 | 新建、重复提示、编辑、详情汇总 | 不自动合并重复客户 |
| `/catalog/employees` | 员工 | 工种（显示名称、提交 code）、离职、重新入职、历史 | 离职或无资格员工不能新排班 |
| `/orders` | 订单列表 | 筛选、新建、打开订单 | 主状态/生产/发货进度分列 |
| `/orders/new` | 订单步骤工作区 | 客户、明细、订购数量与缝边数量、金额、收货、确认 | 服务端金额和快照权威 |
| `/orders/:id` | 订单详情 | 总览、商品与履约、发货与售后、资金与利润、资料与变更 Tab；显式进入变更、取消、发货、收退款、售后、关闭操作 | 总览可核对 12+ 明细；履约按阶段看事实；只读与操作分离；已确认发货可受理售后 |
| `/orders/:id/changes/:changeId` | 变更确认操作 | 变更前后、超出处理、确认 | 减单必须逐项处理余量；确认后回到订单只读详情 |
| `/inventory` | 库存工作区 | 批次、流水、调整、领用 | 领用扣一次，发货不二扣；订单内低频领用就近进入 |
| `/production` | 生产工作台 | 查看多订单多产品任务、明线/暗线、等待上游、提醒 | 未完成、超额和其他排班提醒独立可处理 |
| `/production/tasks/new` | 生产任务新建 | 选择多个订单/产品明细、`NORMAL`/`REWORK`、来源、执行员工、工序与日期，提交任务 | 全页工作区可一次配置多条任务明细；正常任务显示产能与标准工时约束，返工任务不占正常产能 |
| `/production/tasks/:id` | 生产任务只读详情 | 查看任务头、全部任务明细、来源、明线/暗线、标准分钟/标准工时、核验事实 | 只读详情，不预置编辑表单；每条明细的核验状态与数量事实可追溯 |
| `/production/tasks/:id/verify` | 多明细核验操作 | 按任务明细填写完成、合格、返工、报废数量与返工来源，提交一次性核验 | 逐明细校验等式；报废事实和数量回转可见；已核验任务不可重复核验 |
| `/reports` | 台账与导出 | 查询、导出、打印/PDF | 导出无物流字段 |
| `/settings` | 全局设置 | 「单价与默认值」设置值；「静态数据」Tab 先列类别（星级/包装档位/缝边种类/员工工种）、再按类别管理条目；「公式说明」只读 Tab | 全局变更不回溯既有商品快照；类别不可增删改名、被引用条目禁删、改名不回溯；公式说明可查看输入、单位、舍入和示例，但不能编辑或改变运行时公式 |
| Electron shell | 桌面壳 | 打印、文件选择、窗口 | 不直连数据库、不复制业务规则 |

订单内发货批次、售后单、资金流水和变更事实均从相应 Tab 查看，不设 `/shipments` 顶级工作区；需要复杂录入时可使用订单上下文的独立操作页，但不为只读事实逐级叠加路由。

### 7.1 售后来源和结清口径

创建售后必须引用已确认且有效的发货批次明细，并在事务内锁定该明细的有效已发余额与售后已占用量；新受理量不能超过剩余可受理量。售后可在订单仍部分履约时并行进行；原发货已被有效售后引用时，不得直接作废或更正为无效来源。售后来源只追溯有效发货批次明细，生产任务明细不直接成为售后来源，但任务明细产出是发货可用数量的上游事实。订单结清净额为累计订单收款减累计订单变更退款，订单待退款为 `max(累计订单收款 - 当前有效应收 - 累计订单变更退款, 0)`；售后退款独立关联售后单，进入累计实际净收，不冲减结清净额、不产生新的订单待收/待退。全部退款仍不得超过累计订单收款。订单关闭重验只使用订单结清口径，售后占用和售后退款不改变原订单履约、应收或主状态。

### 8. 数据库与 Flyway 设计

数据库表按顶级模块归属：`identity`、`catalog`、`orders`、`inventory`、`production`、`files`。生产侧统一以 `production_tasks` 和 `task_items` 承载多订单多产品 `NORMAL`/`REWORK` 任务、来源及逐明细事实；超额预占、其他排班和提醒按各自事实表承载，不把超额伪装成普通生产任务类型。`shipments`、`payments`、`refunds`、`after_sales_*` 和履约表虽独立建表，仍由 `orders` 拥有。金额为 `DECIMAL(19,4)`，比例为 `DECIMAL(9,6)`，数量为非负整数；唯一约束覆盖业务编号、一次逐明细核验、返工来源消费、幂等键和订单状态转换。Flyway 是唯一结构来源，Hibernate 仅 `ddl-auto: validate`。

### 9. 测试和人工证据

自动化证据包括 JUnit 领域公式、Spring Boot 事务集成、Testcontainers MySQL 迁移/约束/锁、Spring Modulith 模块边界、HTTP 契约、React 类型与浏览器测试、Electron 构建/打印测试、事实重建和并发测试。人工视觉结论不能由机器测试替代；每个需要人眼确认的任务必须记录 `humanVisualConclusion.checklist`，状态先为 `pending-user-signoff`，用户确认后补 `confirmedBy`、`confirmedOn` 和 `conclusion`。

## Risks / Trade-offs

- [契约矩阵与实现漂移] → 每个 API 必须同时有 spec 场景、后端任务、前端入口、HTTP 测试和完成证据；缺任一项不得勾选任务。
- [订单模块过大] → 只在 `orders` 内拆内部组件，使用公开应用服务和模块边界测试，禁止把物理表误当顶级模块。
- [数量重复计算] → 以领域数量模型的订购数量/缝边数量口径和事实重建测试为门禁；工序数量不得相加，发货不得再次扣库存。
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

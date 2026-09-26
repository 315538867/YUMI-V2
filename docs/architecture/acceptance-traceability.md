# 验收追踪：API 契约、前端路由与规格双向追踪（任务 9.8 / 9.9 / 9.10）

本文件是阶段九的**追踪证据**，三节分别对应任务 9.8（OpenAPI/契约清单）、9.9（前端路由清单）、9.10（双向追踪表）。
对照源：`openspec/changes/build-yumi-v2-order-fulfillment/design.md` §6/§7 与六份 spec 的 Requirement/Scenario。
实现侧证据来自代码（控制器注解、`ROUTE_PATHS`、迁移文件、测试类），**不重复业务口径**；口径以各模块施工文档与 spec 为准。

约定：**设计矩阵中的每一行都必须有实现**（9.8 的硬要求）；实现中出现的额外面（子能力、运维端点）必须在 spec 或 tasks 中有追踪，否则视为「无规格实现」。

---

## 1. 任务 9.8：OpenAPI/契约清单

### 1.1 设计矩阵（`design.md` §6）逐行核对

| # | 能力（design.md §6） | 实际端点 | 认证/幂等 | 实现位置 | 自动测试 |
| --- | --- | --- | --- | --- | --- |
| 1 | 登录 | `POST /api/session`（另有 `GET` 当前会话、`DELETE` 登出） | 匿名/否 | `identity/SessionController` | `SessionAuthenticationTest` |
| 2 | 商品试算 | `POST /api/products/preview`、`POST /api/products/{id}/preview` | 管理员/只读豁免幂等 | `catalog/product/ProductController` | `ProductPreviewApiTest`、`ProductPricingTest` |
| 3 | 公式说明 | `GET /api/settings/formulas` | 管理员/否 | `catalog/settings/SettingsController` + `calculation/FormulaCatalog` | `FormulaCatalogApiTest` |
| 4 | 商品/客户/员工 | `GET/POST /api/products`、`PATCH /api/products/{id}`、`POST /api/products/{id}/enable|disable`；`GET/POST /api/customers`、`PATCH /api/customers/{id}`；`GET/POST /api/employees`、`PATCH /api/employees/{id}`、`POST /api/employees/{id}/leave|rehire`、`GET /api/employees/{id}/eligibility` | 管理员/写入幂等 | `catalog/{product,customer,employee}/*Controller` | `ProductApiTest`、`CustomerApiTest`、`EmployeeApiTest`、`EmployeeEligibilityTest` |
| 5 | 订单 | `POST /api/orders`、`GET /api/orders`、`GET /api/orders/{id}`、`PATCH /api/orders/{id}` | 管理员/草稿写入幂等 | `orders/order/OrderController` | `OrderApiTest`、`OrderMigrationTest` |
| 6 | 确认 | `POST /api/orders/{id}/confirm` | 管理员/必须幂等 | `orders/order/OrderService.confirm` | `OrderConfirmationTest`、`OrderPricingBaselineTest` |
| 7 | 变更 | `POST/GET /api/orders/{orderId}/change-orders`、`GET/PATCH /api/order-changes/{id}`、`POST /api/order-changes/{id}/confirm` | 管理员/必须幂等 | `orders/change/OrderChangeController` | `OrderFulfillmentChangeTest` |
| 8 | 订单取消 | `POST /api/orders/{id}/cancel` | 管理员/必须幂等 | `orders/order/OrderService.cancel` | `OrderApiTest` |
| 9 | 履约视图 | `GET /api/orders/{id}/fulfillment` | 管理员/否 | `orders/order/OrderController` | `OrderConfirmationTest` |
| 10 | 库存 | `GET/POST /api/inventory/batches`、`GET /api/inventory/summary`、`GET /api/inventory/movements`、`POST /api/inventory/adjustments`、`POST /api/inventory/movements/{id}/reverse`、`GET /api/inventory/recommendations` | 管理员/写入幂等 | `inventory/InventoryController` | `InventoryApiTest`、`InventoryMigrationTest` |
| 11 | 领用 | `POST/GET /api/inventory-allocations`、`POST /api/inventory-allocations/{id}/cancel` | 管理员/必须幂等 | `inventory/InventoryAllocationController` | `InventoryAllocationTest`、`InventoryConcurrencyTest` |
| 12 | 生产任务 | `GET/POST /api/production-tasks`、`GET /api/production-tasks/{id}`、`POST /api/production-tasks/{taskId}/items/{itemId}/cancel` | 管理员/写入幂等 | `production/task/*` | `ProductionTaskApiTest`、`ProductionTaskItemCancelConcurrencyTest` |
| 13 | 核验 | `POST /api/production-tasks/{id}/verify` | 管理员/必须幂等 | `production/verification/*` | `ProductionVerificationApiTest`、`ProductionIdempotencyTest` |
| 14 | 返工/重做 | `GET/POST /api/rework-sources`、`POST /api/rework-sources/{id}/plans`；`GET/POST /api/remake-sources`、`POST /api/remake-sources/{id}/plans` | 管理员/必须幂等 | `production/source/*` | `ProductionSourceTest` |
| 15 | 超额 | `POST /api/overtime-tasks`、`POST /api/overtime-tasks/{id}/verify` | 管理员/必须幂等 | `production/overtime/*` | `OvertimeTaskTest` |
| 16 | 发货 | `GET/POST /api/orders/{id}/shipments`、`POST /api/orders/{id}/shipments/{shipmentId}/confirm` | 管理员/必须幂等 | `orders/shipment/*` | `ShipmentApiTest`、`ShipmentConcurrencyTest` |
| 17 | 发货作废/更正 | `PATCH .../{shipmentId}`、`PATCH .../{shipmentId}/logistics`、`POST .../{shipmentId}/void`、`POST .../{shipmentId}/corrections` | 管理员/必须幂等 | `orders/shipment/ShipmentController` | `ShipmentApiTest` |
| 18 | 收款/退款 | `POST /api/orders/{id}/payments`、`POST /api/orders/{id}/refunds` | 管理员/必须幂等 | `orders/settlement/SettlementController` | `SettlementApiTest`、`SettlementConcurrencyTest` |
| 19 | 关闭 | `POST /api/orders/{id}/close` | 管理员/必须幂等 | `orders/settlement/SettlementService.close` | `SettlementApiTest` |
| 20 | 售后 | `GET/POST /api/orders/{id}/after-sales`、`GET /api/after-sales/{caseId}`、`POST /api/after-sales/{caseId}/verify-return`、`POST /api/after-sales/{caseId}/corrections` | 管理员/必须幂等 | `orders/aftersales/*` | `AfterSalesApiTest`、`AfterSalesMigrationTest` |
| 21 | 售后补发 | `POST /api/after-sales/{caseId}/replacement-shipments`、`POST .../{shipmentId}/confirm` | 管理员/必须幂等 | `orders/aftersales/AfterSalesService` | `AfterSalesApiTest`、`AfterSalesConcurrencyTest` |
| 22 | 查询导出 | `GET /api/reports/{type}`、`GET /api/reports/{type}/export`、`GET /api/reports/consistency` | 管理员/否 | `reports/*` | `ReportApiTest` |
| 23 | 全局设置 | `GET /api/settings`、`PATCH /api/settings` | 管理员/写入幂等 | `catalog/settings/SettingsController` | `SettingsApiTest` |
| 24 | 静态数据 | `GET /api/settings/static-data`、`GET /api/settings/static-data/{code}`、`POST .../{code}/items`、`PATCH/DELETE .../items/{id}` | 管理员/写入幂等 | `catalog/settings/StaticDataController` | `StaticDataApiTest` |
| 25 | 健康/迁移 | `GET /api/actuator/health/liveness|readiness` | 受保护运维 | `shared/observability` + actuator | `ActuatorProbeTest`、`ActuatorReadinessDbDownTest` |

**结论：设计矩阵 25 行全部有实现，无缺行。**

### 1.2 实现中超出设计矩阵的端点（需有 spec/tasks 追踪，逐条列出）

| 端点 | 追踪依据 |
| --- | --- |
| `GET /api/session`、`DELETE /api/session` | `platform-foundation`「系统必须提供受保护的管理员访问」（登出/会话查询属同一 Requirement） |
| `POST /api/files`、`GET /api/files/{id}` | `master-data-management`「系统必须管理商品、客户和员工资料」（图片/附件）+ 任务 2.x；`FileApiTest` |
| `GET /api/production-reminders/incomplete|overtime`、`POST .../reschedule|defer|adjust-plan|no-adjustment` | `production-management`「未完成数量必须返回对应待处理来源」「超额提醒必须支持人工处理」（任务 5.9/5.12） |
| `GET/POST /api/other-schedules`、`POST /{id}/verify|cancel|corrections` | `production-management`「其他排班只能一次工时核验」（任务 5.13–5.15）；`OtherScheduleTest` |
| `GET /api/inventory/summary|movements|recommendations`、`POST /api/inventory/movements/{id}/reverse` | `inventory-management`「库存流水只能通过冲销更正」「库存数量必须由不可变流水决定」（任务 4.x） |
| `POST /api/inventory/after-sales-allocations` | `order-lifecycle`「售后必须独立于原订单履约」（补发来源；任务 8.6） |
| `GET /api/after-sales/{caseId}/production-sources`、`POST .../production-sources/plans` | `production-management`「生产计划必须绑定有效来源和执行资格」（售后返工/售后补发生产；任务 8.4/8.5） |
| `GET /api/reports/consistency` | `reporting-and-operations`「查询和导出必须基于服务端事实」（一致性检查；任务 9.2/9.4） |
| `GET /api/orders/{orderId}/change-orders`（列表）、`GET /api/order-changes/{id}` | `order-lifecycle`「已确认订单变化必须使用订单变更」（查询面） |
| `GET /api/employees/{id}/eligibility` | `production-management`「生产计划必须绑定有效来源和执行资格」（资格查询） |

**结论：无孤立端点（每个额外面都能追到 Requirement + 任务号）。**

### 1.3 统一契约要素核对

| 要素 | 口径 | 证据 |
| --- | --- | --- |
| 响应信封 | `{code,message,fieldErrors,requestId,data}`，成功与失败同构 | `SuccessEnvelopeContractTest`、`UnifiedErrorContractTest`；导出（`text/csv`、octet-stream）豁免包装 |
| 金额字符串 | 金额/比例以字符串输出（scale4 HALF_UP） | `ProductPricingTest`、`ReportApiTest`（`receivableAmount="100.0000"`） |
| 幂等 | 写命令要求 `Idempotency-Key`，重复键返回首次结果 | `IdempotencyReplayTest` |
| 写审计 | 写命令落审计（操作人、requestId） | `WriteCommandAuditTest` |
| 错误码 | 全部登记在 `shared/error/ErrorCode`（含 HTTP 状态映射） | `UnifiedErrorContractTest`；阶段八新增 `AFTER_SALES_*` 全部已登记 |
| 认证 | 除 `/api/session` 与 actuator 探针外一律要求会话 | `SessionAuthenticationTest`、`ActuatorProbeTest` |

**OpenAPI 说明**：首期**未生成 OpenAPI 文档文件**（无 springdoc 依赖）；契约以「控制器注解 + `design.md` §6 矩阵 + 本清单 + 契约测试」四者一致为准。这是首期的**已知偏差**，已在 9.8 记录，不阻塞验收（无外部消费方）。

---

## 2. 任务 9.9：前端路由清单

正式路由表 `frontend/src/routes/paths.ts` 的 `ROUTE_PATHS` 共 **13 条**，与 `design.md` §7 页面矩阵一致（`routes/paths.test.ts` 断言条数与内容）：

| # | 路由 | 页面 | 关键操作 | 错误处理 | 自动测试 |
| --- | --- | --- | --- | --- | --- |
| 1 | `/login` | 登录 | 登录/错误重试 | 401 → 表单错误提示 | `client.test.ts` |
| 2 | `/catalog/products` | 商品 | 新建/编辑/启停/试算/公式说明只读 | `fieldErrors` 逐字段展示 | `previewInput.test.ts`、`previewScheduler.test.ts` |
| 3 | `/catalog/customers` | 客户 | 新建/编辑（重复只提示不合并） | 同上 | — |
| 4 | `/catalog/employees` | 员工 | 新建/编辑/离职/复职 | 同上 | — |
| 5 | `/orders` | 订单列表 | 新建入口、按状态/客户筛选 | 同上 | — |
| 6 | `/orders/new` | 订单工作区 | 5 步：资料→商品→缝边→库存计划（可选）→确认；草稿保存 | 同上 | `planInput.test.ts` |
| 7 | `/orders/:id` | 订单详情（多 Tab） | 只读事实 + 显式操作：确认、变更、发货与售后（含生产补发）、收退款、关闭 | 同上 | — |
| 8 | `/orders/:id/changes/:changeId` | 订单变更工作区 | 变更录入/确认 | 同上 | — |
| 9 | `/inventory` | 库存 | 建批次/调整/领用/冲销/汇总 | 同上 | — |
| 10 | `/production` | 生产工作台 | 计划/核验/返工/重做/超额/提醒/其他排班 | 同上 | `verifyMath.test.ts` |
| 11 | `/production/plans/:id/verify` | 生产核验工作区 | 一次性核验录入 | 等式校验内联提示 | `verifyMath.test.ts` |
| 12 | `/reports` | 报表 | 六类台账查询/导出/一致性检查/打印 | `REPORT_TYPE_INVALID` 提示 | — |
| 13 | `/settings` | 设置 | 全局设置、静态数据（两级+弹窗）、公式说明只读 | 同上 | — |

**核对结论**：13 条路由全部为**正式路由**（无预览路由）；每条都可达且有对应后端端点；未认证时除 `/login` 外一律回落登录页（`isProtectedPath`）。
**Electron 共用**：Electron 薄壳**已存在**（`frontend/electron/main.cjs`、`preload.cjs`；守卫测试 `electron/shell-guard.test.ts` 5 用例断言「不含数据库凭据/MySQL 直连、不复制业务规则、只依赖 electron 与 Node 内建模块、`contextIsolation` 开且 `nodeIntegration` 关、preload 只经 `contextBridge` 暴露打印/文件选择/系统信息」）。窗口 `loadURL(WEB_URL)` 加载**同一路由与同一 `/api/**`**（`platform-foundation`「浏览器和 Electron 必须使用同一业务 API」）。打印统一走 `src/lib/print.ts`：有 `window.yumiShell.print` 时调用主进程 `print` IPC，否则回退 `window.print()`（`src/lib/print.test.ts` 2 用例）；入口为 `/reports`、`/orders/:id`、发货面板。
**已知偏差（记录不掩盖）**：**未做打包/分发**（无 electron-builder 配置），首期以源码方式运行薄壳；PDF 采用系统打印对话框「另存为 PDF」，不引入服务端 PDF 生成。

---

## 3. 任务 9.10：双向追踪表

六份 spec 共 **51 个 Requirement**，逐条连接实现与验证。列含义：Requirement → 阶段/任务 → 后端实现（模块/API）→ 数据（Flyway/表）→ 前端 → 自动测试 → 人工验收。

### 3.1 `platform-foundation`（5）

| Requirement | 任务 | 后端 | 数据 | 前端 | 自动测试 | 人工 |
| --- | --- | --- | --- | --- | --- | --- |
| 系统必须提供受保护的管理员访问 | 1.2–1.6 | `identity/*`（会话、密码哈希、过滤器链） | `admin_accounts`（V1/V3） | `/login` | `SessionAuthenticationTest` | 9.12 |
| 系统必须提供统一响应契约 | 1.7–1.9 | `shared/error/{ApiEnvelope,EnvelopeAdvice,ApiException,ErrorCode}` | — | `api/client.ts`、`api/errors.ts` | `SuccessEnvelopeContractTest`、`UnifiedErrorContractTest` | 9.12 |
| 系统必须通过版本化迁移管理数据库结构 | 1.10–1.11 | Flyway + Hibernate `validate` | `V1`–`V14` | — | `FlywayFoundationMigrationTest`、各 `*MigrationTest` | — |
| 浏览器和 Electron 必须使用同一业务 API | 1.12、9.9 | 同一 `/api/**`；CORS/会话 Cookie | — | `ROUTE_PATHS` 13 条；Electron 薄壳 `loadURL(WEB_URL)` | `routes/paths.test.ts`、`electron/shell-guard.test.ts` | 9.12（浏览器路径；打包分发未做） |
| 系统必须提供运行和恢复门禁 | 1.13、9.5–9.7 | actuator 健康/就绪、结构化日志与脱敏 | — | — | `ActuatorProbeTest`、`ActuatorReadinessDbDownTest`、`StructuredLogMaskingTest`、`MySqlInfrastructureTest` | 9.6/9.7（发布与备份演练未做） |

### 3.2 `master-data-management`（11）

| Requirement | 任务 | 后端 | 数据 | 前端 | 自动测试 |
| --- | --- | --- | --- | --- | --- |
| 系统必须管理商品、客户和员工资料 | 2.1–2.6 | `catalog/{product,customer,employee}/*`、`files/FileController` | `products`、`customers`、`employees`、`employee_work_types` | `/catalog/*` | `ProductApiTest`、`CustomerApiTest`、`EmployeeApiTest`、`FileApiTest` |
| 基础资料金额和成本必须保持精度 | 2.7 | `calculation/DecimalPolicy.money`（scale4 HALF_UP） | DECIMAL 列 | 金额字符串展示 | `ProductPricingTest`、`ProductPricingBaselineTest` |
| 静态数据必须由系统固定类别、条目按类别管理 | 2.21–2.23 | `catalog/settings/StaticDataController` | `static_data_categories`/`items`、`work_types` | `/settings` 静态数据 Tab | `StaticDataApiTest` |
| 商品字段必须区分商品属性、全局口径与静态数据引用 | 2.24–2.26 | `catalog/product/*`（字段归属与冻结） | `products` 列组 | `/catalog/products` 表单三列 | `ProductSnapshotMatrixTest`、`ProductSeamDefaultApiTest` |
| 业务公式必须集中且只有一份实现 | 2.13–2.17 | `calculation/*`（FP-PROD/FP-ORDER） | 无公式表（代码即唯一实现） | 不复制计算 | `FormulaCatalogApiTest`、`ProductPricingTest` |
| 系统必须提供只读公式说明 | 2.18 | `GET /api/settings/formulas` | — | `/settings` 公式说明 Tab | `FormulaCatalogApiTest` |
| 商品试算与保存必须复用统一计算入口 | 2.16、2.19 | `POST /api/products/preview`、`{id}/preview` | — | `/catalog/products` 试算面板 | `ProductPreviewApiTest`、`previewInput.test.ts`、`previewScheduler.test.ts` |
| 商品预估必须防止过期响应覆盖 | 2.20 | —（前端请求序号守卫） | — | `previewScheduler.ts` | `previewScheduler.test.ts` |
| 客户重复只能提示不能自动合并 | 2.5 | `catalog/customer/*`（重名提示） | `customers` | `/catalog/customers` | `CustomerApiTest`、`CustomerSummaryTest` |
| 订单确认必须冻结基础资料快照 | 3.4 | `orders/order/OrderSnapshotRepository` | `order_*_snapshots` | 详情只读快照 | `OrderConfirmationTest`、`ProductSnapshotMatrixTest` |
| 员工档案与管理员身份必须分离 | 2.6、1.x | `employees` vs `admin_accounts`（无员工登录） | 两套表 | 无员工登录入口 | `EmployeeApiTest` |

### 3.3 `order-lifecycle`（12）

| Requirement | 任务 | 后端 | 数据 | 前端 | 自动测试 |
| --- | --- | --- | --- | --- | --- |
| 订单确认必须冻结需求和流程 | 3.4 | `OrderService.confirm`、`OrderSnapshotRepository` | `orders`、`order_items`、快照表（V7） | `/orders/:id` 确认入口 | `OrderConfirmationTest` |
| 工序必须共享订单明细数量 | 3.x、5.x | `ProductionNodes`、`OrderProductionReference.demand` | `order_item_fulfillment_balances`（V7） | 详情履约 Tab | `OrderConfirmationTest`、`ProductionPlanApiTest` |
| 缝边必须作为订单明细行的定制服务 | 2.27、3.x | `orders/order/*`（缝边种类/数量/单价） | `order_items.seam_*` | 订单工作区第 3 步 | `ProductSeamDefaultApiTest`、`OrderPricingBaselineTest` |
| 已确认订单变化必须使用订单变更 | 3.6–3.8 | `orders/change/*` | `order_changes`（V7） | `/orders/:id/changes/:changeId` | `OrderFulfillmentChangeTest` |
| 订单取消必须区分草稿和已确认 | 3.9 | `OrderService.cancel` | `orders.status` | 详情取消入口 | `OrderApiTest` |
| 订单必须维护可发货和发货上限 | 3.x、6.x | `FulfillmentRepository` | `order_item_fulfillment_balances.shippable_quantity` | 履约 Tab | `ShipmentApiTest`、`OrderConfirmationTest` |
| 发货事实和物流修改必须分离 | 6.5–6.7 | `orders/shipment/*`（`logistics` 修改留痕） | `shipment_logistics_changes`（V11） | 发货与售后 Tab | `ShipmentApiTest` |
| 订单关闭必须同时满足履约和款项条件 | 7.x | `SettlementService.close` | `order_settlement_balances`（V12） | 详情关闭入口 | `SettlementApiTest` |
| 收款和退款必须是不可变事实 | 7.x | `orders/settlement/*` | `payments`、`refunds`（V12） | 资金与利润 Tab | `SettlementApiTest`、`SettlementConcurrencyTest` |
| 售后必须独立于原订单履约 | 8.1–8.11 | `orders/aftersales/*`、`orders/ledger/AfterSalesLedger`、`production/aftersales/*` | `after_sales_*`（V13）、`after_sales_production_sources`（V14） | 发货与售后 Tab 售后区域 | `AfterSalesApiTest`、`AfterSalesMigrationTest`、`AfterSalesConcurrencyTest`、`AfterSalesProductionTest` |
| 订单详情必须区分只读事实与显式操作 | 3.x、8.10 | 详情只读读模型 + 显式命令 | — | `/orders/:id` 多 Tab | —（人工视觉，见 8.10/8.12/9.12） |
| 更正必须保留原始事实 | 6.8、8.9 | `ShipmentService.correct`、`AfterSalesService.correct` | `shipment_corrections`（V11）、`after_sales_corrections`（V13） | 发货/售后更正入口 | `ShipmentApiTest`、`AfterSalesApiTest` |

### 3.4 `inventory-management`（6）

| Requirement | 任务 | 后端 | 数据 | 前端 | 自动测试 |
| --- | --- | --- | --- | --- | --- |
| 库存数量必须由不可变流水决定 | 4.2–4.3 | `inventory/InventoryService` | `inventory_movements`/`_lines`、`inventory_batches`（V8） | `/inventory` | `InventoryMigrationTest`、`InventoryApiTest` |
| 订单库存领用必须原子扣减并接入 | 4.5–4.7 | `InventoryAllocationController`、`FulfillmentLedger` | `inventory_allocations`/`_lines` | 订单工作区第 4 步、`/inventory` | `InventoryAllocationTest`、`InventoryConcurrencyTest` |
| 草稿库存计划不占用库存 | 4.8 | `OrderService.persistPlan`、`InventoryPlanReference` | `order_inventory_plan_lines`（V9） | 订单工作区第 4 步 | `OrderInventoryPlanTest` |
| 发货不得重复扣减原库存 | 4.13、6.x | `ShipmentService.confirm`（只消耗可发货） | `shipment_source_links`（V11） | 发货 Tab | `ShipmentApiTest` |
| 领用取消必须使用反向事实 | 4.9 | `InventoryAllocationController.cancel` | 反向 `inventory_movements` | `/inventory` 取消入口 | `InventoryAllocationTest` |
| 库存流水只能通过冲销更正 | 4.10 | `POST /api/inventory/movements/{id}/reverse` | `reverses_movement_id` | `/inventory` 冲销入口 | `InventoryApiTest` |

### 3.5 `production-management`（11）

| Requirement | 任务 | 后端 | 数据 | 前端 | 自动测试 |
| --- | --- | --- | --- | --- | --- |
| 生产任务必须绑定有效来源和执行资格 | 5.2–5.14、8.4/8.5 | `production/task/*`、`production/source/*`、`production/aftersales/*` | `production_tasks`、`production_task_items`、`rework_sources`、`after_sales_production_sources`（V14） | `/production`、`/production/tasks/new`、`/production/tasks/:id/verify` | `ProductionTaskApiTest`、`ProductionReworkScrapTest`、`AfterSalesProductionTest` |
| 任务头状态与明细事实必须分离 | 5.2、5.7 | `ProductionTaskService.derivedStatus`、`ProductionFlowService` | `production_task_items.status` + 派生量（无任务头状态列） | `/production` 列表 | `ProductionTaskApiTest`、`ProductionVerificationApiTest` |
| 生产计划只能一次核验 | 5.4 | `ProductionVerificationService` | `uk_production_verifications_plan`（V10） | 核验工作区 | `ProductionVerificationTest` |
| 待执行计划取消必须恢复来源 | 5.8 | `ProductionPlanCancellationService` | 来源 `arranged_quantity` 回退 | `/production` 取消入口 | `ProductionPlanApiTest` |
| 工序合格必须按冻结流程流转 | 5.5 | `qualifiedFlows`（捏毛装袋按缝边数量分流） | `making/packing/seam_inflow` | 履约 Tab | `ProductionVerificationTest` |
| 返工必须受目标矩阵和来源余额限制 | 5.6 | `ReworkSourceService`、`ProductionNodes.canRework` | `rework_sources` | `/production` 返工来源 | `ProductionSourceTest` |
| 报废必须保留不可变事实并只回转同工序 | 5.12 | `ProductionQuantityReturnRepository`、`ProductionScrapController` | `scrap_records`、`production_quantity_returns` | `/production/tasks/:id` 事实时间线 | `ProductionReworkScrapTest` |
| 未完成数量必须返回对应待处理来源 | 5.9 | `handleIncomplete` + 提醒 | `production_reminders`（V10） | `/production` 提醒工作台 | `ProductionReminderTest` |
| 超额任务只产生业务预占和人工调整提醒 | 5.10–5.11 | `production/overtime/*` | `overtime_preemptions`（V10） | `/production` 超额入口 | `OvertimeTaskTest` |
| 超额提醒必须支持人工处理 | 5.12 | `ProductionReminderController`（overtime 两命令） | `production_reminders` | `/production` 提醒处理 | `OvertimeTaskTest`、`ProductionReminderTest` |
| 其他排班只能一次工时核验 | 5.13–5.15 | `production/otherschedule/*` | `other_schedules` 三表（V10） | `/production` 其他排班 | `OtherScheduleTest` |

### 3.6 `reporting-and-operations`（6）

| Requirement | 任务 | 后端 | 数据 | 前端 | 自动测试 | 人工 |
| --- | --- | --- | --- | --- | --- | --- |
| 查询和导出必须基于服务端事实 | 9.1、9.2、9.4 | `reports/ReportController`（查询/导出/一致性） | 只读各事实表与投影 | `/reports` | `ReportApiTest` | 9.12 |
| 打印和 PDF 必须展示有效快照 | 9.3 | 打印内容取自既有只读接口（订单详情/发货详情/台账均已返回确认快照） | 快照列（`order_*_snapshots`、发货累计/未交付快照） | `/reports`、`/orders/:id`、发货面板的「打印 / PDF」→ `src/lib/print.ts` | `print.test.ts`、`electron/shell-guard.test.ts` | 9.12（浏览器与薄壳打印；打包未做） |
| 发布必须经过迁移和健康门禁 | 9.6 | 发布流程（**未实现**，属运维侧） | Flyway `validate/migrate` | — | `FlywayFoundationMigrationTest`、`ActuatorProbeTest` | 9.6 未做 |
| 备份必须覆盖数据库和文件 | 9.7 | 备份/恢复脚本（**未实现**，属运维侧） | — | — | `MySqlInfrastructureTest`（连接与库结构） | 9.7 未做 |
| 日志和监控不得泄露秘密 | 9.5 | `shared/observability`（结构化日志 + 脱敏） | — | — | `StructuredLogMaskingTest` | 9.5 指标/告警未做 |
| 人工视觉结论必须单独签字 | 9.12、9.13 | — | — | 全部页面 | — | 5.17/6.10/7.9/8.12/9.12 待用户签字 |

### 3.7 孤立项审计

- **有规格无实现**：仅 `reporting-and-operations` 的「发布必须经过迁移和健康门禁」「备份必须覆盖数据库和文件」两条的**运维执行部分**未做（9.6/9.7），以及 9.5 的指标/告警/慢查询配置与 Electron **打包分发**——均已在本表与对应任务中显式记录为**未做项**，不作为通过证据。
- **有实现无规格**：无（见 §1.2 的逐条追踪）。
- **无自动测试的 Requirement**：「订单详情必须区分只读事实与显式操作」（人工视觉）、「人工视觉结论必须单独签字」（流程）——两者按规格本身即要求人工结论，由 8.10/8.12/9.12 承担。

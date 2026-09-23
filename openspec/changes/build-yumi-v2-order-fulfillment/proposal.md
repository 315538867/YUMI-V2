## Why

YUMI V2 是绿地重建，现有统一 change 已确定业务边界，但当前制品仍停留在阶段路线图级别，无法让实现人员在不猜测的情况下完成接口、数据库、前端和验收。此次重构把已确认的订单履约规则固化为可测试的行为契约、接口矩阵、页面矩阵和细粒度施工任务，并保持一个 change、九个依赖阶段。

## What Changes

- **BREAKING** 将本 change 的实施入口从粗粒度模块任务改为“需求/场景 → API → 后端与迁移 → 前端 → 自动化测试 → 人工验收 → 完成证据”的可追踪任务。
- 补齐管理员认证、统一错误响应、幂等键、审计、健康检查、Flyway 和浏览器/Electron 边界契约。
- 补齐商品、客户、员工、工种、变更审计和订单确认快照行为。
- 补齐订单草稿、确认、共同数量、订单变更、减单/取消、收退款、发货、关闭及关闭后售后的完整行为。
- 补齐库存批次、不可变流水、期初/盘点、库存领用、取消领用和“库存领用只扣一次、发货不再扣库存”规则。
- 补齐生产计划、执行条件、一次性核验、返工目标矩阵、报废重做、未完成提醒、超额预占与计划调整、其他排班工时核验。
- 补齐订单模块内部发货、收退款、关闭和售后补发台账的 API、页面入口、状态派生和错误码。
- 补齐查询、导出、打印/PDF、发布门禁、备份恢复和人工视觉验收证据要求。
- 明确 Java 21、Spring Boot 3.5.x、Spring Modulith、Spring Data JPA、Flyway、Maven、MySQL 8.x、React、TypeScript、Electron 的目标实现边界；不实现应用代码。

## Capabilities

### New Capabilities

- `platform-foundation`: 管理员认证、审计、编号、统一错误、幂等、迁移、健康检查、共享前端和 Electron 边界。
- `master-data-management`: 商品、客户、员工、工种、金额精度和基础资料历史。
- `order-lifecycle`: 订单、快照、共同数量、订单变更、取消、履约、发货、收退款、关闭和售后独立台账。
- `inventory-management`: 独立库存批次、不可变流水、库存调整、领用、取消领用和来源追溯。
- `production-management`: 生产计划、逐工序核验、返工、报废重做、超额任务、其他排班和提醒。
- `reporting-and-operations`: 服务端查询导出、打印/PDF、健康检查、发布和备份恢复。

### Modified Capabilities

无。当前项目不存在既有 OpenSpec capability；六份规格均为本 change 的新增能力，现有文件内容被完整重写以形成最终契约。

## Impact

- 影响 `openspec/changes/build-yumi-v2-order-fulfillment/` 下 proposal、design、六份规格和 tasks；本次不新增应用代码。
- 实现阶段将新增 Java/Maven 后端、React/TypeScript 前端、Electron 薄壳、Flyway SQL、MySQL 约束和测试工程。
- API 为 HTTPS JSON 业务命令与查询；金额使用十进制字符串；所有写命令要求认证、审计和幂等键。
- 顶级模块为 `identity`、`catalog`、`orders`、`inventory`、`production`、`files`；发货、履约、收退款、售后和关闭属于 `orders` 内部组件。
- 参考基线：`docs/requirements/order-fulfillment-requirements.md`、`docs/architecture/system-architecture.md`、`docs/architecture/domain-and-quantity-model.md`、`docs/architecture/database-design.md`、`docs/architecture/deployment-architecture.md`、`docs/delivery/implementation-roadmap.md` 和项目确认记录 `yumi-v2-order-field-confirmations.md`。
- 首期不包含旧数据迁移、旧 IPC/页面兼容、员工登录、复杂权限、工资、完整财务、在线支付、原材料库存、离线同步、微服务、消息队列或 Kubernetes。

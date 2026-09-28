## Why

YUMI V2 是绿地重建，现有统一 change 已确定业务边界，但当前制品仍停留在阶段路线图级别，无法让实现人员在不猜测的情况下完成接口、数据库、前端和验收。此次重构把已确认的订单履约规则固化为可测试的行为契约、接口矩阵、页面矩阵和细粒度施工任务，并保持一个 change、九个依赖阶段。

## What Changes

- 新增后端 `calculation` 统一计算模块，商品及后续订单、库存、生产的业务公式集中实现，未来工资和财务按相同边界接入；本期不建设动态公式编辑或规则引擎。
- 将商品本地 decimal.js 预估替换为认证的服务端试算，试算与保存共用输入解析和唯一计算入口；新增 `POST /api/products/preview`、`POST /api/products/{id}/preview`，保持历史快照且不批量重算。
- 在 `/settings` 增加只读“公式说明”入口，由后端计算模块提供公式目录、输入/单位、舍入规则、适用业务和固定示例；不提供公式编辑、发布、生效时间或历史重算。
- 在阶段二追加 2.13–2.20 集中计算任务，保留既有完成记录；后续阶段公式任务必须依赖集中计算基线。方案依据 `docs/architecture/formula-management-design.md`。
- 新增「静态数据」：星级、包装档位、缝边种类、员工工种四类由系统固定类别 code，类别不可增删改名；星级/包装档位/缝边种类条目由用户自建，员工工种为系统预置 4 条（code 定死、仅可改名与停用）。设置页改为 Tab 结构，静态数据 Tab 下先列类别、再按类别管理条目。方案依据 `docs/architecture/static-data-and-product-fields-design.md`。
- 商品字段按归属划分：胶水损耗率、日常杂费、房租水电改为**全局只读**（商品表单只展示、保存时取当前全局值）；装箱人工费、运输包装费保持**全局默认 + 商品可改**；模具摊销费保持商品可改；**包装提成从包装档位移到商品**。
- 缝边整体**移出商品、落到订单**：缝边是订单里的定制服务，在订单明细行上选择缝边种类、填写数量（可小于明细数量）与收费单价，选中种类后带出成本单价作提示；缝边成本（种类成本单价 × 数量，允许为 0）**计入订单成本**，缝边收费计入应收，订单利润 = 应收 − 商品成本 − 缝边成本。商品与商品快照不再含缝边字段，商品总成本与参考售价口径不变。
- 包装提成新增**全局默认值** `packaging_commission_default`（元/件，对应真实核算表的“打包提成”参数），商品表单预填、可改；商品新增「**默认缝边剪袋类型**」（可空＝默认不缝边剪袋）与「**缝边价格**」作为订单缝边定制的默认值，缝边的取舍、数量与收费仍由订单明细行决定，商品自身总成本按不缝边剪袋口径保存、缝边剪袋变体单列展示。
- 商品公式与真实核算表 `新核算表.xlsx` 逐项对照：色浆成本为 `胶水用量 × 色浆单价`（胶水用量已含损耗率，**不重复计损耗**）；核算表内部的定价分析字段（最低批发价、单克价、最高/最低双利润与利润率）**不纳入系统**。
- 开发期未上线，**不考虑历史数据**：改写 `V4`/`V5` 迁移为最终结构（新增 `seam_types`、`work_types`，`packaging_tiers` 去提成，`products` 去缝边列、`packaging_commission` 语义改为商品提成，`employee_work_types` 改为引用 `work_types`）并清库重建，不做数据迁移。

- **BREAKING** 将本 change 的实施入口从粗粒度模块任务改为“需求/场景 → API → 后端与迁移 → 前端 → 自动化测试 → 人工验收 → 完成证据”的可追踪任务。
- 补齐管理员认证、统一错误响应、幂等键、审计、健康检查、Flyway 和浏览器/Electron 边界契约。
- 补齐商品、客户、员工、工种、变更审计和订单确认快照行为。
- 补齐订单草稿、确认、共同数量、订单变更、减单/取消、收退款、分批发货、关闭及发货后独立售后的完整行为。
- 补齐库存批次、不可变流水、期初/盘点、库存领用、取消领用和“库存领用只扣一次、发货不再扣库存”规则。
- 补齐多订单多产品生产任务、`NORMAL`/`REWORK` 任务类型、产品日产能、工序标准分钟与标准工时、明线/暗线、逐明细核验、发生问题工序内部返工、报废事实与数量回转、未完成提醒、独立超额预占与任务调整、其他排班工时核验。
- 将售后受理时点改为已确认发货后，按有效发货明细限制售后占用；区分订单结清净额与售后退款，并补齐发货、收退款、关闭和售后补发台账的 API、页面入口、状态派生和错误码。
- 订单详情采用订单内只读多 Tab 与显式操作入口；前端先按常规 Ant Design 后台管理布局落地：全页白色底、单组订单页签、浅色侧栏与顶部面包屑，使用 Ant Design 现成组件，通过按钮层级、Tag 样式、间距、边框和主题 token 表达视觉层次；不在同一页面并排展示两套 A/B 方案或重复订单页签。
- 补齐查询、导出、打印/PDF、发布门禁、备份恢复和人工视觉验收证据要求。
- 明确 Java 21、Spring Boot 3.5.x、Spring Modulith、Spring Data JPA、Flyway、Maven、MySQL 8.x、React、TypeScript、Electron 的目标实现边界；不实现应用代码。

## Capabilities

### New Capabilities

- `platform-foundation`: 管理员认证、审计、编号、统一错误、幂等、迁移、健康检查、共享前端和 Electron 边界。
- `master-data-management`: 商品、客户、员工、工种、金额精度和基础资料历史。
- `order-lifecycle`: 订单、快照、共同数量、订单变更、取消、履约、发货、收退款、关闭和售后独立台账。
- `inventory-management`: 独立库存批次、不可变流水、库存调整、领用、取消领用和来源追溯。
- `production-management`: 多订单多产品生产任务、`NORMAL`/`REWORK`、产品日产能、工序标准分钟与标准工时、明线/暗线、逐明细核验、发生问题工序内部返工、报废事实与数量回转、独立超额预占、其他排班和提醒。
- `reporting-and-operations`: 服务端查询导出、打印/PDF、健康检查、发布和备份恢复。

### Modified Capabilities

无。当前项目不存在既有 OpenSpec capability；六份规格均为本 change 的新增能力，现有文件内容被完整重写以形成最终契约。

## Impact

- 影响 `docs/requirements/order-fulfillment-requirements.md` 与相关架构、路线图文档，以及 `openspec/changes/build-yumi-v2-order-fulfillment/` 下 proposal、design、订单生命周期规格和 tasks；本次不新增应用代码。
- 实现阶段将新增 Java/Maven 后端、React/TypeScript 前端、Electron 薄壳、Flyway SQL、MySQL 约束和测试工程。
- API 为 HTTPS JSON 业务命令与查询；金额使用十进制字符串；所有写命令要求认证、审计和幂等键；公式说明为认证只读查询，不产生业务写入。
- 业务顶级模块为 `identity`、`catalog`、`orders`、`inventory`、`production`、`files`；新增独立无持久化的支撑模块 `calculation`，业务模块单向调用其公开计算接口，并由其提供只读公式目录，通用基础设施仍在 `shared`；发货、履约、收退款、售后和关闭属于 `orders` 内部组件。
- 参考基线：`docs/requirements/order-fulfillment-requirements.md`、`docs/architecture/system-architecture.md`、`docs/architecture/domain-and-quantity-model.md`、`docs/architecture/database-design.md`、`docs/architecture/deployment-architecture.md`、`docs/delivery/implementation-roadmap.md` 和项目确认记录 `yumi-v2-order-field-confirmations.md`。
- 首期不包含旧数据迁移、旧 IPC/页面兼容、员工登录、复杂权限、工资、完整财务、在线支付、原材料库存、离线同步、微服务、消息队列或 Kubernetes。

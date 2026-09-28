# YUMI V2 订单模块详细设计

## 1. 文档定位

本文是 YUMI V2 目标架构细化的第三册，冻结 `orders` 模块的数据库表、事实与快照、当前投影、公开 Java 契约、跨模块事务、锁定计划、错误码、权限能力和验证门禁。

本文必须与以下文档共同使用：

- `making-centered-target-architecture.md`；
- `database-and-contract-foundation.md`；
- `data-facts-snapshots-projections.md`；
- `module-contracts-and-transaction-matrix.md`；
- `master-data-and-calculation-design.md`；
- `backend-architecture.md`；
- `http-api-architecture.md`；
- `platform-capabilities-architecture.md`。

本文不定义生产来源、排班任务、真实现金、售后案例或经营成本的内部表。上述数据分别由 `production`、`scheduling`、`finance`、`after-sales` 和 `cost-ledger` 拥有。

## 2. 权威边界

### 2.1 `orders` 拥有

- 普通订单身份、客户快照、收货快照和订单当前状态投影；
- 订单商品及确认时的商品制作规格快照；
- 按数量划分的交付约定组和不可变约定版本；
- 交付约定的工序、材料承担、成交价格和预计成本快照；
- 订单确认、取消和变更事实；
- 订单变更影响评估结果及管理员任务取消确认；
- 普通发货草稿、发货占用引用、普通发货事实、发货明细及发货更正；
- 当前有效订购数量、累计有效普通发货数量和交付履约投影；
- 订单原确认应收、金额调整、收款、订单价款退款、多收款退回和资金更正业务事实；
- 订单付款义务和付款履约投影；
- 管理员确认订单完成事实和完成失效事实；
- 已有订单接管事实、接管快照和接管差额更正。

### 2.2 `orders` 不拥有

- 商品、客户和员工当前主数据；
- 生产来源、实物物理阶段、质量、报废和来源消费事实；
- 排班任务、产能分配、任务取消和核验事实；
- 银行、微信、支付宝或现金的真实收付事实；
- 订单完成后新产生的售后义务、退回、重新交付、额外补发及售后资金；
- 正式工资、已归集经营成本和跨模块经营报表。

### 2.3 跨模块权威关系

```text
master-data 提供当前商品、客户和预算定义
orders 在确认或变更时复制为自己的冻结快照
production 拥有制作需求、生产来源、来源占用与消费
scheduling 拥有任务、任务取消和核验
finance 拥有真实现金收付
orders 拥有客户订单应收义务与订单收付款业务事实
订单完成后的新事项由 after-sales 拥有
```

## 3. 核心不变量

```text
普通新订单从零生产进度开始
同一订单商品可以按数量拆成多个交付约定组
交付约定版本只追加，不覆盖历史版本
客户供料只改变材料承担方，不删除材料需求
订单确认后使用冻结快照，不回读主数据替换历史
订单变更不得覆盖已确认发货事实
发货资格由当前交付约定、实物阶段、包装事实、质量和来源余额动态判断
发货草稿可以产生正式来源占用，但草稿本身不是发货事实
普通发货事实由 orders 拥有，生产来源消费事实由 production 拥有
实物已经发出时不得通过账面发货更正恢复生产来源
订单完成必须同时满足工作室交付履约和客户付款履约
订单完成时间是管理员确认完成事实的发生时间
订单完成后的新事项进入 after-sales，原订单不重新打开
已有订单只接管当前事实，不补造历史任务、核验、逐笔发货和逐笔资金流水
```

## 4. 标识、编号和公共类型

内部主键、稳定业务标识、金额、比例、数量和时间遵守公共基线。

人工业务编号至少包括：

```text
订单号          YM + 5 位以上递增序号
订单变更单号    OC + 6 位以上递增序号
普通发货单号    SH + 6 位以上递增序号
已有订单接管号  LI + 6 位以上递增序号
```

编号创建后不可修改、不可复用。编号与 `business_id` 分离。

订单数量使用非负整数。金额使用 `DECIMAL(19,4)`，比例使用 `DECIMAL(12,8)`。外部金额和比例以十进制字符串传输。

### 4.1 简式字段清单解释

除 `orders` 等使用完整字段表格的核心根外，本文以代码块列出其余表的完整字段。所有简式字段清单统一受以下类型、空值和默认规则约束；字段后标注“可空”或条件非空时，以该标注为准：

| 字段模式 | 数据库类型 | 默认空值规则 |
|---|---|---|
| 内部 `id`、模块内 `*_id` | BIGINT | 否，正数；仅模块内关联和锁定 |
| `business_id`、`fact_id`、`snapshot_id`、`projection_id`、`draft_id`、`line_id`、`link_id`、`term_id`、`impact_id`、`confirmation_id` | VARCHAR(64) | 否，全局稳定且不可变 |
| 明确命名为跨模块 `*_business_id`、`*_fact_id`、`*_reservation_business_id` | VARCHAR(64) | 否，除非标注可空 |
| `*_no` | VARCHAR(32) | 否，人工业务编号 |
| `*_code`、`*_type`、`status` | VARCHAR(64) | 否，受控稳定代码 |
| `*_quantity`、`*_count`、`line_no`、`sort_order` | INT 或累计场景 BIGINT | 否；当前量非负，正式动作数量大于 0 |
| `*_amount`、`unit_price`、`*_cost`、`*_value` | DECIMAL(19,4) | 否；差额允许有符号，其余按语义非负 |
| `*_rate` | DECIMAL(12,8) | 否，范围由对应业务规则限定 |
| `*_minutes` | INT | 否，单位为分钟 |
| `*_date`、`*_on` | DATE | 否，除非标注可空 |
| `occurred_at`、`recorded_at`、`created_at`、`updated_at`、`confirmed_at` | DATETIME(6) | 否，UTC；可变记录才有 `updated_at` |
| `version`、`*_version` | BIGINT | 否，非负 |
| `reason_text`、`note`、地址和物流展示文本 | VARCHAR(1000) 或更窄的明确实现长度 | `reason_text` 在拒绝、更正、取消类事实中非空；普通备注按标注可空 |
| `*_fingerprint` | VARCHAR(128) | 否，规范化输入或条件摘要 |

没有标注可空的字段一律 `NOT NULL`。此外，所有分类为不可变事实或事实明细头的表都实际包含 `operation_type VARCHAR(64) NOT NULL`；所有分类为草稿、当前意图或当前投影的可变表都实际包含 `created_by VARCHAR(64) NOT NULL` 和 `updated_by VARCHAR(64) NOT NULL`。正文简式清单不再逐表重复这三个继承列，但 Flyway、行模型和契约测试必须包含并验证它们。

业务状态、金额、版本和操作者不使用数据库业务默认值，由命令显式写入。所有稳定标识使用大小写敏感比较规则，模块内父子字段使用物理外键且不级联删除正式事实、快照和投影。每个简式清单后的专用规则继续覆盖本表的唯一约束、检查约束和条件非空组合。

## 5. 表总览

| 表 | 分类 | 说明 |
|---|---|---|
| `orders` | 当前业务根投影 | 订单身份、当前状态、版本和关键汇总 |
| `order_confirmation_facts` | 不可变事实 | 普通订单确认或已有订单接管确认 |
| `order_party_snapshots` | 不可变快照 | 客户和本次收货资料 |
| `order_items` | 当前业务明细 | 订单商品身份和当前有效数量汇总 |
| `order_item_snapshots` | 不可变快照 | 商品名称、编码和核心制作规格 |
| `delivery_agreement_groups` | 当前投影 | 按数量划分的交付约定组 |
| `delivery_agreement_versions` | 不可变快照 | 每组历次生效约定版本 |
| `delivery_agreement_process_snapshots` | 快照明细 | 约定采用的工序、分钟和包装规格 |
| `delivery_agreement_material_terms` | 快照明细 | 具体材料需求、单价和承担方 |
| `delivery_agreement_cost_snapshots` | 不可变快照 | 约定版本预计成本和利润汇总 |
| `delivery_agreement_cost_lines` | 快照明细 | 工序、人工、材料和分摊结果 |
| `order_change_orders` | 草稿与当前意图 | 订单变更草稿、当前状态及正式确认事实引用 |
| `order_change_item_actions` | 变更草稿明细 | 组拆分、合并、新建、减量和商业变更意图 |
| `order_change_agreement_terms` | 变更草稿明细 | 新建或更新交付约定的阶段、价格和备注 |
| `order_change_process_terms` | 变更草稿明细 | 变更拟采用的工序、标准分钟和包装规格 |
| `order_change_material_terms` | 变更草稿明细 | 变更拟采用的具体材料需求、参数和承担方 |
| `order_change_impacts` | 影响快照 | 提交前生产与排班影响评估 |
| `order_change_task_confirmations` | 影响确认 | 管理员对具体任务版本的取消确认 |
| `order_change_confirmation_facts` | 不可变事实 | 订单变更正式生效事实 |
| `order_charge_decisions` | 不可变事实 | 处理费、材料费、减免和协商结果 |
| `order_receivable_facts` | 不可变事实 | 原确认应收和金额调整 |
| `order_refund_decision_facts` | 不可变事实 | 订单价款退款义务的建立、调整和取消 |
| `order_payment_facts` | 不可变事实 | 收款、退款支付、退回和更正业务事实 |
| `order_payment_obligations` | 当前投影 | 应收、净收、待收、待退和付款履约 |
| `shipment_drafts` | 草稿 | 普通发货草稿 |
| `shipment_draft_items` | 草稿明细 | 草稿发货数量与约定组 |
| `shipment_reservation_links` | 正式占用引用 | 草稿与 production 来源占用的关联 |
| `shipments` | 不可变事实头 | 已确认普通发货事实 |
| `shipment_items` | 事实明细 | 约定组和本次发货数量 |
| `shipment_agreement_snapshots` | 不可变快照 | 发货时采用的约定版本摘要 |
| `shipment_source_links` | 来源关系 | 发货明细与 production 来源消费结果 |
| `shipment_corrections` | 不可变事实 | 发货取消、失效或差额更正 |
| `order_fulfillment_projections` | 当前投影 | 交付履约和普通生产阻塞摘要 |
| `order_completion_facts` | 不可变事实 | 完成确认和完成失效 |
| `order_cancellation_facts` | 不可变事实 | 已确认订单整体取消 |
| `legacy_order_intakes` | 不可变事实根 | 已有订单接管确认及接管快照 |
| `legacy_order_intake_items` | 接管明细 | 商品、约定、历史发货和当前数量 |
| `legacy_order_intake_state_lines` | 接管快照明细 | 当前物理和质量状态数量 |
| `legacy_order_intake_financials` | 接管快照 | 接管前资金累计和当前应收 |
| `legacy_order_intake_corrections` | 不可变事实 | 接管值差额修正 |

## 6. 订单业务根和确认快照

### 6.1 `orders`

| 字段 | 类型 | 空值 | 含义 |
|---|---|---|---|
| `id` | BIGINT | 否 | 内部主键 |
| `business_id` | VARCHAR(64) | 否 | 稳定订单标识 |
| `order_no` | VARCHAR(32) | 否 | 人工订单号 |
| `order_origin` | VARCHAR(32) | 否 | `NEW_ORDER`、`LEGACY_INTAKE` |
| `customer_business_id` | VARCHAR(64) | 否 | 客户稳定标识，跨模块逻辑引用 |
| `party_snapshot_id` | VARCHAR(64) | 否 | 当前确认客户与收货快照 |
| `order_date` | DATE | 否 | 下单业务日期 |
| `expected_delivery_date` | DATE | 是 | 期望交付日期 |
| `status` | VARCHAR(32) | 否 | `DRAFT`、`CONFIRMED`、`IN_FULFILLMENT`、`COMPLETED`、`CANCELLED` |
| `current_receivable_amount` | DECIMAL(19,4) | 否 | 当前有效应收投影，非负 |
| `orders_delivery_quantity_satisfied` | BOOLEAN | 否 | orders 自有数量交付义务是否满足，不代表跨模块完整交付履约 |
| `payment_fulfilled` | BOOLEAN | 否 | 客户付款履约投影 |
| `active_completion_fact_id` | VARCHAR(64) | 是 | 当前有效完成事实 |
| `note` | VARCHAR(1000) | 是 | 当前有效订单整体备注 |
| `version` | BIGINT | 否 | 订单聚合版本 |
| `last_fact_id` | VARCHAR(64) | 否 | 最近直接影响订单的事实 |
| `created_at` | DATETIME(6) | 否 | 创建时间 |
| `created_by` | VARCHAR(64) | 否 | 创建人 |
| `updated_at` | DATETIME(6) | 否 | 最后修改时间 |
| `updated_by` | VARCHAR(64) | 否 | 最后修改人 |

约束：

- `UNIQUE(business_id)`；
- `UNIQUE(order_no)`；
- `CHECK(current_receivable_amount >= 0)`；
- `CHECK(expected_delivery_date IS NULL OR expected_delivery_date >= order_date)`；
- `COMPLETED` 必须存在有效完成事实；
- `CANCELLED` 与 `COMPLETED` 互斥；
- 订单不物理删除；

### 6.2 `order_confirmation_facts`

```text
id
fact_id
order_id
fact_type = ORDER_CONFIRMED | LEGACY_ORDER_CONFIRMED
party_snapshot_id
original_receivable_fact_id
business_date
occurred_at
recorded_at
operator_id
command_execution_id
rule_version
created_at
```

- 每个订单只能有一个初始确认事实；
- 普通草稿确认后不可恢复为草稿；
- 确认事实与快照、交付约定、应收事实、投影、幂等和审计同事务提交。

### 6.3 `order_party_snapshots`

```text
id
snapshot_id
order_id
snapshot_version
customer_business_id
customer_no
customer_name
contact_name
contact_phone
recipient_name
recipient_phone
shipping_address
shipping_note
source_customer_version
created_by_fact_id
created_at
```

快照创建后不可更新。订单变更涉及收货资料时创建新版本，历史发货继续引用发货时快照。

## 7. 订单商品与交付约定

### 7.1 `order_items`

```text
id
business_id
order_id
line_no
product_business_id
current_item_snapshot_id
current_effective_quantity
current_agreement_quantity
cumulative_effective_shipped_quantity
version
created_at
updated_at
```

- `UNIQUE(business_id)`；
- `UNIQUE(order_id, line_no)`；
- 三个数量均非负；
- 当前交付约定组有效数量合计必须等于 `current_agreement_quantity`；
- 当前有效数量不得低于累计有效普通发货数量；
- 数量投影变化必须来自确认、变更、发货或接管更正事实。

### 7.2 `order_item_snapshots`

```text
id
snapshot_id
order_item_id
snapshot_version
product_business_id
product_no
product_name
description
standard_weight_grams
making_standard_minutes
mould_count
daily_batch_count
source_product_version
created_by_fact_id
created_at
```

订单不保存商品当前可变对象引用作为历史解释。商品停用或改名不改变本快照。

### 7.3 `delivery_agreement_groups`

```text
id
business_id
order_item_id
group_no
current_version_id
current_effective_quantity
cumulative_effective_shipped_quantity
status = ACTIVE | SUPERSEDED | CANCELLED
version
created_at
updated_at
```

- `UNIQUE(business_id)`；
- `UNIQUE(order_item_id, group_no)`；
- 当前有效数量不得低于累计有效发货数量；
- 组是订单内稳定数量归属，不因新版本改变标识；
- 拆分产生新组；合并通过旧组失效和新组建立表达，不改写旧组历史。

### 7.4 `delivery_agreement_versions`

```text
id
snapshot_id
agreement_group_id
agreement_version
source_profile_business_id（可空）
source_profile_version（可空）
effective_quantity
delivery_stage_code
unit_price
line_revenue_amount
note
source_order_change_id（可空）
created_by_fact_id
created_at
```

- `UNIQUE(snapshot_id)`；
- `UNIQUE(agreement_group_id, agreement_version)`；
- 交付阶段只能为 `MAKING_COMPLETED`、`BAGGING_COMPLETED`、`SEAM_CUTTING_COMPLETED`；
- 金额非负；
- 已生效版本不可更新；
- 版本中的数量和组投影必须在同一事务保持一致。

### 7.5 工序、材料与预计成本快照

`delivery_agreement_process_snapshots`：

```text
id
snapshot_id
agreement_version_id
process_code
standard_minutes
commission_amount
sort_order
formula_rule_version
rounding_version
created_at
```

`delivery_agreement_material_terms`：

```text
id
snapshot_id
agreement_version_id
process_code
material_business_id
material_code
material_name
usage_formula_code
cost_formula_code
base_usage
calculated_standard_usage
unit_code
budget_unit_price
loss_rate
bearer_type = STUDIO | CUSTOMER
full_resource_unit_cost
studio_borne_unit_cost
customer_supplied_unit_value
created_at
```

`delivery_agreement_cost_snapshots`：

```text
id
snapshot_id
agreement_version_id
formula_rule_version
rounding_version
full_resource_unit_cost
studio_borne_unit_cost
customer_supplied_unit_value
expected_unit_revenue
expected_unit_profit
expected_profit_rate（收入为 0 时可空）
full_resource_total_cost
studio_borne_total_cost
customer_supplied_total_value
expected_total_revenue
expected_total_profit
calculation_input_fingerprint
created_at
```

`delivery_agreement_cost_lines`：

```text
id
line_id
cost_snapshot_id
line_no
process_code
cost_component_type = LABOR | MATERIAL | ALLOCATION
material_business_id（材料项非空）
bearer_type
unit_amount
total_amount
formula_code
formula_rule_version
rounding_version
created_at
```

所有快照和明细只追加。总额按该约定版本的 `effective_quantity` 计算。客户供料标准价值保留，但不计入工作室承担预计成本。

## 8. 普通订单确认

普通订单确认输入至少包含：

- 草稿订单版本；
- 客户及本次收货资料；
- 至少一个订单商品；
- 每个商品的一个或多个交付约定组；
- 各组数量、交付阶段、工序、材料承担、成交价格和备注；
- 期望交付日期；
- 整体备注；
- 临时文件标识（如有）。

确认事务：

```text
orders 锁定订单草稿和全部草稿明细
→ master-data 查询并校验当前客户、商品、方案和设置
→ calculation 重新计算每组预计成本、收入和利润
→ orders 创建确认事实、客户快照、商品快照、约定版本和应收事实
→ production 初始化普通制作与包装需求
→ orders 更新当前订单、商品、约定、履约和付款投影
→ 幂等、审计、文件关联和提交后动作注册一起提交
```

普通订单确认不创建排班任务，不创建生产完成事实，不创建真实现金事实。

## 9. 订单变更

### 9.1 `order_change_orders`

```text
id
business_id
change_no
order_id
status = DRAFT | IMPACT_EVALUATED | CONFIRMED | CANCELLED
change_type
reason_text
base_order_version
impact_snapshot_version（可空）
confirmed_fact_id（可空）
version
created_at
created_by
updated_at
updated_by
```

草稿可修改和取消；确认后不可编辑。变更单不是通用 JSON 差异包。

### 9.2 `order_change_item_actions`

每行表达一个明确动作：

```text
id
business_id
change_order_id
action_type = CREATE_GROUP | SPLIT_GROUP | MERGE_GROUPS | UPDATE_AGREEMENT | REDUCE_QUANTITY | UPDATE_RECEIVING | UPDATE_NOTE
source_agreement_group_id（可空）
target_group_client_ref（新组草稿引用，可空）
quantity
receiving_snapshot_draft_id（仅收货变更时非空）
reason_text
line_no
created_at
updated_at
```

- `UNIQUE(business_id)`；
- `UNIQUE(change_order_id, line_no)`；
- 数量动作必须提供大于 0 的 `quantity`；
- 新组使用 `target_group_client_ref` 在同一变更草稿内关联从属条款，不使用数据库内部主键进入公开契约。

`order_change_agreement_terms`：

```text
id
term_id
change_action_id
delivery_stage_code
unit_price
note
source_profile_business_id（可空）
source_profile_version（可空）
created_at
updated_at
```

`order_change_process_terms`：

```text
id
term_id
agreement_term_id
process_code
standard_minutes
commission_amount
packaging_specification_code（可空）
sort_order
formula_rule_version
rounding_version
created_at
updated_at
```

`order_change_material_terms`：

```text
id
term_id
agreement_term_id
process_code
material_business_id
usage_formula_code
cost_formula_code
base_usage（可空）
calculated_standard_usage
unit_code
budget_unit_price
loss_rate
bearer_type = STUDIO | CUSTOMER
sort_order
created_at
updated_at
```

这些表是可编辑变更草稿明细，不是冻结快照。正式确认时必须在锁内重新读取主数据、调用 calculation 重算，并复制为新的不可变交付约定版本、工序、材料和成本快照。草稿内部使用模块内物理外键和级联删除，不使用 JSON 或 EAV。

### 9.3 `order_change_impacts`

```text
id
impact_id
change_order_id
impact_version
order_version
production_projection_version
impact_type
agreement_group_id（可空）
affected_quantity
reassignable_quantity
restoration_required_quantity
frozen_quantity
production_gap_delta
summary_code
created_at
```

影响评估是提交前快照，不是提交授权。正式确认必须重新锁定并重算。

### 9.4 `order_change_task_confirmations`

```text
id
confirmation_id
change_order_id
impact_version
schedule_task_business_id
expected_task_version
administrator_decision = CONFIRM_CANCEL
confirmed_by
confirmed_at
```

确认只绑定本次变更、影响版本、具体任务和任务版本。任务变化后确认失效。

### 9.5 `order_change_confirmation_facts`

```text
id
fact_id
change_order_id
order_id
base_order_version
impact_version
resulting_order_version
operator_id
command_execution_id
occurred_at
recorded_at
created_at
```

- `UNIQUE(fact_id)`；
- `UNIQUE(change_order_id)`，一张变更单只能正式生效一次；
- 变更单当前状态、约定新版本、生产与排班参与事实、收费决定和应收调整都引用同一 `command_execution_id`；
- 事实创建后不可更新或删除。

### 9.6 `order_charge_decisions`

```text
id
fact_id
change_order_id
order_id
charge_type = PROCESSING_FEE | CONSUMED_MATERIAL | DISCOUNT | NEGOTIATED_ADJUSTMENT
responsibility_type = CUSTOMER | STUDIO | SHARED | WAIVED
amount_delta
reason_text
operator_id
command_execution_id
occurred_at
recorded_at
created_at
```

原因和责任不自动决定金额。金额决策进入应收调整事实，但不覆盖原成交价款。

### 9.7 正式变更事务

```text
orders 锁定订单、约定组、发货、应收投影和变更单
→ scheduling 按任务内部主键升序锁定全部受影响未核验任务
→ scheduling 重验管理员确认、任务状态和任务版本
→ production 按来源内部主键升序锁定生产根、来源、占用和投影
→ production 重验实物归组、可还原、冻结和缺口
→ scheduling 统一取消全部已确认任务
→ production 释放任务来源占用并执行归组、还原或缺口调整
→ orders 追加变更确认事实、约定新版本、收费和应收调整事实
→ 更新订单、约定、履约和付款投影
→ 整体提交
```

任一任务已经核验、管理员未确认、任务版本变化或任一数量不足时整次拒绝，不允许部分提交。未核验任务没有“已开工但不可取消”的独立状态。

## 10. 普通发货草稿与占用

### 10.1 `shipment_drafts`

```text
id
draft_id
shipment_no（确认前预分配或确认时分配，策略统一）
order_id
status = DRAFT | CONFIRMED | CANCELLED
shipping_date
logistics_company（可空）
tracking_no（可空）
freight_amount（可空）
note（可空）
version
created_at
created_by
updated_at
updated_by
```

首期草稿不自动过期。已确认或已取消草稿不可编辑。

### 10.2 `shipment_draft_items`

```text
id
draft_item_id
shipment_draft_id
agreement_group_id
agreement_version_id
shipment_quantity
line_no
version
created_at
updated_at
```

同一草稿内同一交付约定组唯一，数量必须大于 0。

### 10.3 `shipment_reservation_links`

```text
id
link_id
shipment_draft_item_id
production_reservation_business_id
reserved_quantity
status = ACTIVE | CONSUMED | RELEASED
created_by_command_execution_id
released_by_command_execution_id（可空）
consumed_by_shipment_fact_id（可空）
version
created_at
updated_at
```

orders 只保存 production 占用的稳定标识和数量，不复制生产来源余额。创建或修改草稿时通过 production 端口正式占用；取消草稿释放；确认发货消费。

## 11. 普通发货事实

### 11.1 `shipments`

```text
id
fact_id
shipment_no
order_id
source_draft_id
shipment_date
party_snapshot_id
logistics_company
tracking_no
freight_amount
note
operator_id
command_execution_id
occurred_at
recorded_at
created_at
```

一行是不可变已确认发货事实，不使用可编辑状态覆盖历史。

### 11.2 `shipment_items`

```text
id
shipment_item_id
shipment_fact_id
order_item_id
agreement_group_id
agreement_version_id
shipment_quantity
cumulative_shipped_after
unshipped_after
line_no
created_at
```

- `UNIQUE(shipment_item_id)`；
- 同一发货事实和交付约定组唯一；
- 数量必须大于 0；
- 累计发货不得超过当前有效约定数量。

### 11.3 `shipment_agreement_snapshots`

保存发货时的约定版本摘要：

```text
id
snapshot_id
shipment_item_id
agreement_version_snapshot_id
delivery_stage_code
product_no
product_name
material_term_summary
unit_price
shipment_amount
recipient_name
recipient_phone
shipping_address
created_at
```

正式打印和历史查询读取本快照，不回读当前客户、商品或约定版本。

### 11.4 `shipment_source_links`

```text
id
link_id
shipment_item_id
production_source_business_id
production_consumption_fact_id
consumed_quantity
created_at
```

- `UNIQUE(shipment_item_id, production_source_business_id)`；
- 链接是跨模块逻辑引用；
- 来源消费事实由 production 拥有；
- 同一发货明细来源数量合计必须等于发货数量。

### 11.5 确认发货事务

```text
orders 锁定订单、约定组、草稿、草稿明细和履约投影
→ 重验订单状态、expectedVersion、约定版本和占用完整性
→ production 锁定占用、指定来源和来源投影
→ production 重验阶段、质量、归属、规格和数量
→ production 消费占用并追加来源消费事实
→ orders 追加发货事实、明细、约定快照和来源链接
→ orders 更新累计发货和交付履约投影
→ 幂等、审计和文件关联同事务提交
```

## 12. 发货更正

`shipment_corrections`：

```text
id
fact_id
order_id
original_shipment_fact_id
correction_type = CANCEL | INVALIDATE | QUANTITY_CORRECTION | LOGISTICS_CORRECTION
physical_shipment_status = NOT_DISPATCHED | DISPATCHED
quantity_delta（数量更正时）
reason_text
replacement_shipment_fact_id（等量替代时可空）
operator_id
command_execution_id
occurred_at
recorded_at
created_at
```

规则：

- 原发货事实和快照永久保留；
- 仅物流公司、物流单号、运费和发货备注变化不改数量事实，仍以追加物流更正表达；
- 实物尚未发出时，数量取消或更正可以在同一事务调用 production 恢复来源；
- 实物已经发出时不得恢复来源，必须通过退回或售后处理；
- 存在有效售后引用时必须先解除或处理售后下游关系；
- 发货支撑有效完成事实时，先追加订单完成失效事实，再执行发货更正；
- 更正后重新计算交付履约，但不得自动重新完成订单。

发货更正事务：

```text
orders 锁定订单、原发货事实、当前有效更正链、完成事实和履约投影
→ orders 重验更正类型、实物是否发出、售后引用和 expectedVersion
→ 原发货支撑有效完成时，通过 orders 内部完成失效端口追加 COMPLETION_INVALIDATED
→ 需要恢复来源时，production 锁定原消费事实、来源和来源投影
→ production 重验来源未被后续用途冲突消费并追加来源恢复事实
→ orders 追加发货更正事实并更新累计有效发货和履约投影
→ 幂等、审计和必要文件关联同事务提交
```

完整命令由 orders 使用 `REQUIRED` 开启；orders 内部完成失效端口和 production 来源恢复端口使用 `MANDATORY`。同一发货更正不得在锁定 production 后回头获取新的 orders 业务锁。

## 13. 交付履约投影

`order_fulfillment_projections` 每个订单一行，只保存 orders 自有事实直接派生的交付义务：

```text
id
projection_id
order_id
current_effective_ordered_quantity
cumulative_effective_shipped_quantity
remaining_delivery_quantity
active_shipment_reservation_quantity
pending_change_count
orders_delivery_quantity_satisfied
orders_delivery_quantity_satisfied_at（可空）
version
last_fact_id
last_command_execution_id
created_at
updated_at
```

orders 不复制 production 的制作/包装缺口、质量阻塞或 scheduling 的未核验任务计数作为自己的长期投影，因为这些值会在 production 核验、质量处置和 scheduling 任务命令中独立变化。订单详情可以只读组合这些摘要；订单完成确认必须在同一事务按全局锁序调用 production 和 scheduling 的完成阻塞查询，读取并锁定对应权威投影版本，然后与 orders 自有投影共同判断：

- 当前有效数量全部有效发货，或未发部分已通过订单变更正式减量；
- 没有普通制作和包装缺口；
- 没有阻塞质量状态；
- 没有相关未核验任务和发货占用；
- 没有待确认订单变更。

本投影从 orders 有效确认、变更、发货、更正、取消和发货占用关系重建。production 和 scheduling 摘要只通过公开查询或完成校验端口获得，orders 不直接读取其表，也不把跨模块查询值变成自身权威事实。

## 14. 订单应收与资金业务事实

### 14.1 `order_receivable_facts`

```text
id
fact_id
order_id
fact_type = ORIGINAL_RECEIVABLE | AMOUNT_ADJUSTMENT | ADJUSTMENT_CORRECTION
source_order_change_id（可空）
source_charge_decision_fact_id（可空）
amount_delta
reason_text
corrects_fact_id（可空）
operator_id
command_execution_id
occurred_at
recorded_at
rule_version
created_at
```

当前订单应收等于原确认应收加全部有效金额调整。原确认应收每个订单唯一。

### 14.2 `order_refund_decision_facts`

```text
id
fact_id
order_id
fact_type = PRICE_REFUND_DECIDED | PRICE_REFUND_ADJUSTED | PRICE_REFUND_CANCELLED
source_receivable_fact_id（可空）
refund_amount_delta
reason_text
corrects_fact_id（可空）
operator_id
command_execution_id
occurred_at
recorded_at
created_at
```

- 退款决定表达 orders 拥有的订单价款退款义务，不表示现金已经支付；
- 调整和取消通过追加差额事实表达，不覆盖原决定；
- 当前有效退款义务不得为负，也不得低于已支付的订单价款退款；
- 新增退款义务立即阻断付款履约完成；若订单已有有效完成事实，必须在同一命令中先追加完成失效事实。

### 14.3 `order_payment_facts`

```text
id
fact_id
order_id
fact_type = RECEIPT | PRICE_REFUND_PAID | OVERPAYMENT_RETURN | PAYMENT_CORRECTION
amount
payment_method_code
business_date
finance_cash_fact_id
source_receivable_fact_id（可空）
source_refund_decision_fact_id（退款支付时非空）
corrects_fact_id（可空）
reason_text
operator_id
command_execution_id
occurred_at
recorded_at
created_at
```

- 金额必须大于 0，方向由类型决定；
- `PRICE_REFUND_PAID` 减少订单价款净收并抵减指定退款义务的待支付余额；
- `OVERPAYMENT_RETURN` 处理多收款，不再次减少订单应收；
- 真实现金事实由 finance 拥有，本表保存其稳定标识；
- 已确认事实不更新或删除，错误使用更正事实。

### 14.4 `order_payment_obligations`

```text
id
projection_id
order_id
original_receivable_amount
receivable_adjustment_amount
current_receivable_amount
cumulative_receipt_amount
current_price_refund_obligation_amount
cumulative_price_refund_paid_amount
cumulative_overpayment_return_amount
net_order_price_received
outstanding_amount
overpayment_pending_return_amount
price_refund_pending_payment_amount
pending_adjustment_count
pending_correction_count
payment_fulfilled
payment_fulfilled_at（可空）
version
last_fact_id
last_command_execution_id
created_at
updated_at
```

```text
订单价款净收 = 累计订单收款 - 累计订单价款退款支付
订单价款退款待支付 = 当前有效退款义务 - 累计有效退款支付
```

付款履约完成要求净收等于当前有效应收，且没有尾款、多付款待退、订单价款退款待支付、待确认金额调整和待处理资金更正。

### 14.5 收付款事务

```text
orders 锁定订单、应收事实链和付款义务投影
→ 校验订单状态、金额、expectedVersion 和更正关系
→ finance 按统一锁序写入真实现金收入或支出事实
→ orders 追加订单资金事实并更新付款义务投影
→ 同事务提交幂等和审计
```

订单资金命令不修改售后资金，不把真实现金事实复制为订单权威事实。

## 15. 订单完成

`order_completion_facts`：

```text
id
fact_id
order_id
fact_type = COMPLETED | COMPLETION_INVALIDATED
fulfillment_projection_version
fulfillment_last_fact_id
fulfillment_snapshot_fingerprint
payment_projection_version
payment_last_fact_id
payment_snapshot_fingerprint
delivery_fulfilled_at
payment_fulfilled_at
completion_reason（完成失效时必填）
invalidates_fact_id（失效时非空）
operator_id
command_execution_id
occurred_at
recorded_at
created_at
```

确认完成事务：

```text
orders 锁定订单、交付履约投影、付款义务投影和待确认变更
→ scheduling 锁定并返回该订单当前未核验任务摘要及版本
→ production 锁定并返回该订单制作缺口、包装缺口和质量阻塞摘要及版本
→ orders 重验数量交付、发货占用和待确认变更
→ orders 重验客户付款履约与待处理更正
→ 生成两类完成条件规范化指纹
→ 追加 COMPLETED 事实
→ 更新订单为 COMPLETED
```

`fulfillment_snapshot_fingerprint` 规范化冻结当前有效订购、累计有效发货、剩余交付、活动发货占用、待确认变更以及 production/scheduling 提供的缺口、质量阻塞和未核验任务摘要；`payment_snapshot_fingerprint` 规范化冻结当前应收、净收、待收、多收款待退、价款退款待支付和待处理更正。两个指纹必须同时保存各自投影版本和末事实标识，完成查询可以返回结构化条件快照，但事实表不复制其他模块的可变投影作为 orders 权威。

完成时间取 `COMPLETED.occurred_at`。完成后不允许普通订单新增数量、普通生产需求、普通发货或订单收款；后续新事项进入 after-sales。数据错误使完成条件不成立时追加 `COMPLETION_INVALIDATED`，不得删除原完成事实，也不得自动重新完成。

## 16. 订单取消

草稿订单可以直接删除草稿从属数据，但一旦存在正式确认事实便不能物理删除。

已确认订单仅在 production 需求尚未被排班占用、核验产出、来源调整、发货或资金事实消费时允许整体取消。订单确认时产生的需求初始化事实本身不阻止取消，但必须由 orders 在同一事务调用 production 关闭全部未消费需求并将缺口归零；存在任务、核验、生产来源、发货、收付款或其他实际履约事实时，必须通过订单变更处理剩余义务。取消使用专用 `order_cancellation_facts`：

```text
id
fact_id
order_id
reason_text
operator_id
command_execution_id
occurred_at
recorded_at
created_at
```

取消事务固定为：orders 锁定订单、交付约定和履约投影，重验没有任务、核验、生产来源、发货和资金事实；production 随后锁定普通需求及缺口投影，追加需求关闭事实并将未消费需求置为关闭；orders 追加取消事实并更新订单、交付约定和履约投影，整体提交。完整命令使用 `REQUIRED`，production 参与端口使用 `MANDATORY`。取消后不得新增普通履约和订单资金事实，历史快照永久保留。

## 17. 已有订单接管

### 17.1 `legacy_order_intakes`

```text
id
fact_id
intake_no
order_id
source_reference（可空）
source_order_date
intake_business_date
reason_text
operator_id
command_execution_id
occurred_at
recorded_at
created_at
```

入口永久保留，不设置临时初始化开关。它使用独立权限，但不是仅部署期权限。

### 17.2 接管明细

`legacy_order_intake_items`：

```text
id
intake_item_id
intake_fact_id
order_item_id
product_snapshot_id
current_effective_quantity
historically_shipped_quantity
not_made_quantity
current_unshipped_product_quantity
delivery_stage_code
agreement_group_id
created_at
```

数量守恒：

```text
当前有效订购数量
= 接管前累计有效发货
+ 尚未制作
+ 当前订单内未发制品
```

`legacy_order_intake_state_lines`：

```text
id
state_line_id
intake_item_id
physical_stage_code
quality_status_code
packaging_specification_snapshot
quantity
production_source_business_id
created_at
```

当前状态数量合计必须等于 `current_unshipped_product_quantity`。历史发货和当前制品不得重复。

### 17.3 `legacy_order_intake_financials`

```text
id
snapshot_id
intake_fact_id
original_or_current_receivable_amount
historical_receipt_amount
historical_price_refund_amount
historical_overpayment_return_amount
current_price_refund_obligation_amount
historical_price_refund_paid_amount
current_outstanding_amount
price_refund_pending_payment_amount
overpayment_pending_return_amount
payment_fulfilled
created_at
```

接管只保存累计数，不伪造接管前逐笔收退款和真实现金流水。`legacy_order_intake_financials` 是接管订单付款投影的冻结期初基线；付款义务重建固定为“接管期初基线 + 接管更正差额 + 接管后 order_receivable_facts、order_refund_decision_facts 和 order_payment_facts”，不能只回放接管后资金事实。若接管时需要把当前现金余额纳入 finance，使用明确的期初现金事实，而不是伪造历史逐笔交易。

### 17.4 `legacy_order_intake_corrections`

```text
id
fact_id
intake_fact_id
correction_scope = QUANTITY | STATE | SHIPPED_TOTAL | RECEIVABLE | RECEIPT_TOTAL | REFUND_TOTAL
intake_item_id（可空）
quantity_delta（可空）
amount_delta（可空）
reason_text
corrects_fact_id（可空）
operator_id
command_execution_id
occurred_at
recorded_at
created_at
```

接管值不能覆盖。更正必须保证：

- 不低于接管后已被生产、发货、售后和资金事实消费的数量或金额；
- 不产生负数量、负余额或重复来源；
- 同事务更新 orders 和 production 各自投影；
- 不补造接管前历史任务、核验、发货或资金流水。

### 17.5 接管确认事务

```text
orders 锁定接管草稿和编号
→ 校验只接管仍有履约、付款或后续管理事项的订单
→ 校验数量和资金守恒
→ orders 创建订单、快照、约定、期初应收与累计资金接管事实
→ production 创建当前生产来源、物理质量状态和需求缺口
→ 必要时 finance 创建明确的期初现金事实
→ orders 更新履约和付款投影
→ 不创建排班任务
→ 整体提交
```

已有订单默认可按商品汇总为一个交付约定组；只有后续交付阶段、包装规格、材料承担或价格确实不同才拆组。

## 18. 公开应用接口

所有正式应用命令使用专用 `Command` 和 `Result`，接收 `RequestContext` 与 `IdempotencyInput`。

```java
interface OrderApplication {
    CreateOrderDraftResult createDraft(CreateOrderDraftCommand command, RequestContext context);
    UpdateOrderDraftResult updateDraft(UpdateOrderDraftCommand command, RequestContext context);
    ConfirmOrderResult confirm(ConfirmOrderCommand command, RequestContext context, IdempotencyInput idempotency);
    CancelOrderResult cancel(CancelOrderCommand command, RequestContext context, IdempotencyInput idempotency);
}

interface OrderChangeApplication {
    CreateOrderChangeDraftResult createDraft(CreateOrderChangeDraftCommand command, RequestContext context);
    EvaluateOrderChangeResult evaluate(EvaluateOrderChangeCommand command, RequestContext context);
    ConfirmOrderChangeResult confirm(ConfirmOrderChangeCommand command, RequestContext context, IdempotencyInput idempotency);
    CancelOrderChangeDraftResult cancelDraft(CancelOrderChangeDraftCommand command, RequestContext context);
}

interface ShipmentApplication {
    CreateShipmentDraftResult createDraft(CreateShipmentDraftCommand command, RequestContext context, IdempotencyInput idempotency);
    UpdateShipmentDraftResult updateDraft(UpdateShipmentDraftCommand command, RequestContext context, IdempotencyInput idempotency);
    CancelShipmentDraftResult cancelDraft(CancelShipmentDraftCommand command, RequestContext context, IdempotencyInput idempotency);
    ConfirmShipmentResult confirm(ConfirmShipmentCommand command, RequestContext context, IdempotencyInput idempotency);
    CorrectShipmentResult correct(CorrectShipmentCommand command, RequestContext context, IdempotencyInput idempotency);
}

interface OrderSettlementApplication {
    RecordOrderReceiptResult recordReceipt(RecordOrderReceiptCommand command, RequestContext context, IdempotencyInput idempotency);
    DecidePriceRefundResult decidePriceRefund(DecidePriceRefundCommand command, RequestContext context, IdempotencyInput idempotency);
    PayPriceRefundResult payPriceRefund(PayPriceRefundCommand command, RequestContext context, IdempotencyInput idempotency);
    ReturnOverpaymentResult returnOverpayment(ReturnOverpaymentCommand command, RequestContext context, IdempotencyInput idempotency);
    CorrectOrderPaymentResult correct(CorrectOrderPaymentCommand command, RequestContext context, IdempotencyInput idempotency);
}

interface OrderCompletionApplication {
    CompleteOrderResult complete(CompleteOrderCommand command, RequestContext context, IdempotencyInput idempotency);
}

interface LegacyOrderIntakeApplication {
    ConfirmLegacyOrderIntakeResult confirm(ConfirmLegacyOrderIntakeCommand command, RequestContext context, IdempotencyInput idempotency);
    CorrectLegacyOrderIntakeResult correct(CorrectLegacyOrderIntakeCommand command, RequestContext context, IdempotencyInput idempotency);
}
```

完成失效不是外部通用应用命令。发货更正、资金更正或其他 orders 所有的完整命令需要使完成失效时，通过下述内部参与端口加入同一 orders 事务。

## 19. 跨模块参与端口

由能力提供方拥有接口和 DTO：

```java
interface ProductionDemandPort {
    ProductionDemandInitialization initializeOrderDemand(InitializeOrderDemand command);
    ProductionDemandAdjustment applyOrderChange(ApplyOrderChangeDemand command);
    ProductionDemandClosure closeUnconsumedOrderDemand(CloseUnconsumedOrderDemand command);
    LegacyProductionInitialization initializeLegacyOrder(InitializeLegacyProduction command);
}

interface ProductionOrderChangeImpactQuery {
    ProductionOrderChangeImpact evaluate(EvaluateProductionOrderChange command);
}

interface SchedulingOrderChangeImpactQuery {
    SchedulingOrderChangeImpact evaluate(EvaluateSchedulingOrderChange command);
}

interface SchedulingTaskCancellationPort {
    TaskCancellationResult cancelForOrderChange(CancelTasksForOrderChange command);
}

interface SchedulingOrderCompletionPort {
    SchedulingCompletionBlockers lockAndGetCompletionBlockers(CheckOrderCompletionScheduling command);
}

interface ProductionOrderCompletionPort {
    ProductionCompletionBlockers lockAndGetCompletionBlockers(CheckOrderCompletionProduction command);
}

interface ShipmentReservationPort {
    ShipmentReservationResult reserve(ReserveShipmentSources command);
    ShipmentReservationResult replace(ReplaceShipmentReservations command);
    ShipmentReservationRelease release(ReleaseShipmentReservations command);
    ShipmentSourceConsumption consume(ConsumeShipmentSources command);
    ShipmentSourceRestoration restore(RestoreShipmentSources command);
}

interface OrderCompletionInvalidationPort {
    InvalidateOrderCompletionResult invalidate(InvalidateOrderCompletionCommand command);
}

interface FinanceOrderCashPort {
    CashFactReference recordOrderReceipt(RecordOrderCashReceipt command);
    CashFactReference recordOrderPayment(RecordOrderCashPayment command);
    CashFactReference correctOrderCash(CorrectOrderCash command);
    OpeningCashFactReference recordLegacyOpeningBalance(RecordLegacyOpeningCashBalance command);
}
```

`OrderCompletionInvalidationPort` 由 orders 拥有，只允许 orders 内部完整更正命令调用，使用 `MANDATORY` 加入既有 orders 事务；它不提供 HTTP 入口，也不接受外部幂等输入。其他跨模块写端口同样使用 `MANDATORY`，不解释外部幂等键，不再编排第三模块，只写提供方拥有的表。影响评估查询只读，不提供写入或锁定授权。

## 20. 查询接口

```java
interface OrderDetailQuery {
    OrderDetailView get(OrderId orderId, QueryContext context);
}

interface OrderWorkbenchQuery {
    CursorPage<OrderSummaryView> search(OrderSearchQuery query, QueryContext context);
}

interface OrderDemandQuery {
    OrderDemandView get(OrderId orderId);
}

interface OrderAgreementQuery {
    DeliveryAgreementView get(DeliveryAgreementGroupId agreementGroupId);
    List<DeliveryAgreementView> listActive(OrderId orderId);
}

interface OrderFulfillmentQuery {
    OrderFulfillmentView get(OrderId orderId);
}

interface OrderPaymentQuery {
    OrderPaymentView get(OrderId orderId);
}

interface ShipmentQuery {
    ShipmentDetailView get(ShipmentId shipmentId, QueryContext context);
    CursorPage<ShipmentSummaryView> search(ShipmentSearchQuery query, QueryContext context);
}
```

订单详情由 orders 单层组合主数据展示、生产摘要、排班影响和资金义务。普通查询不得跨模块 SQL 联表，也不把查询结果当作正式命令授权；订单完成使用专用 completion 参与端口在正式事务中锁定并重验 scheduling 与 production 权威摘要，不复用普通查询结果。

## 21. 锁定计划

### 21.1 模块内顺序

```text
订单根
→ 订单商品
→ 交付约定组
→ 变更、发货草稿或资金事实链
→ 当前履约与付款投影
```

同类型记录按内部主键升序锁定。

### 21.2 跨模块顺序

```text
orders
→ scheduling（订单变更影响任务时）
→ production
→ finance
```

单个命令只进入实际需要的模块，但不得改变相对顺序。`orders` 不能在锁定 production 后回头锁 scheduling。

### 21.3 锁定索引

至少建立：

- `orders(business_id)`、`orders(order_no)`；
- `order_items(order_id, id)`；
- `delivery_agreement_groups(order_item_id, status, id)`；
- `order_change_orders(order_id, status, id)`；
- `order_change_item_actions(change_order_id, line_no)`；
- `shipment_drafts(order_id, status, id)`；
- `shipment_reservation_links(shipment_draft_item_id, status, id)`；
- `shipments(order_id, id)`；
- `shipment_corrections(original_shipment_fact_id, id)`；
- `order_receivable_facts(order_id, id)`；
- `order_refund_decision_facts(order_id, id)`；
- `order_payment_facts(order_id, id)`；
- `order_completion_facts(order_id, id)`；
- 两个订单投影的 `order_id` 唯一索引；
- 接管和更正按 `order_id`、`intake_fact_id` 的稳定索引。

## 22. 幂等与领域唯一性

- 订单确认每个订单只能成功一次；
- 同一变更单只能确认一次；
- 同一发货草稿只能生成一个正式发货事实；
- 同一 production 来源消费事实只能链接到一个有效普通发货用途；
- 同一订单只能存在一个当前有效完成事实；
- 同一退款决定命令只产生一个 orders 退款义务事实，不产生 finance 现金事实；
- 同一收款、退款支付或多收款退回命令只产生一个 orders 资金事实和一个 finance 现金事实；
- 同一接管命令只产生一个正式订单和一个接管事实；
- 参与模块通过 `command_execution_id + operation_type` 和业务唯一约束防止重复参与。

不产生正式事实、投影或占用的普通草稿保存只要求版本控制。发货草稿的创建、修改和取消会创建、替换或释放 production 正式占用，因此必须支持 `Idempotency-Key`；参与端口还必须以草稿明细稳定标识、用途和命令执行标识建立领域唯一约束。订单确认、变更确认、发货确认与更正、收付款、完成和接管更正同样必须支持 `Idempotency-Key`。

## 23. 错误码

| 错误码 | 分类 | 含义 |
|---|---|---|
| `ORDER_NOT_FOUND` | NOT_FOUND | 订单不存在 |
| `ORDER_VERSION_CONFLICT` | CONCURRENT_CONFLICT | 订单版本冲突 |
| `ORDER_STATE_CONFLICT` | BUSINESS_REJECTION | 当前状态不允许该动作 |
| `ORDER_ALREADY_CONFIRMED` | BUSINESS_REJECTION | 订单已确认 |
| `ORDER_ALREADY_COMPLETED` | BUSINESS_REJECTION | 订单已完成，后续新事项应进入售后 |
| `ORDER_CANCELLATION_NOT_ALLOWED` | BUSINESS_REJECTION | 已存在正式履约或资金事实，不能整体取消 |
| `DELIVERY_AGREEMENT_INVALID` | VALIDATION | 约定数量、阶段、工序或材料条款不合法 |
| `DELIVERY_AGREEMENT_QUANTITY_MISMATCH` | BUSINESS_REJECTION | 约定组数量合计不守恒 |
| `ORDER_CHANGE_IMPACT_STALE` | CONCURRENT_CONFLICT | 变更影响快照已失效 |
| `ORDER_CHANGE_TASK_CONFIRMATION_REQUIRED` | BUSINESS_REJECTION | 受影响任务未获管理员取消确认 |
| `ORDER_CHANGE_TASK_NOT_CANCELLABLE` | BUSINESS_REJECTION | 任务已核验，不能再取消 |
| `ORDER_CHANGE_WOULD_UNDERRUN_SHIPPED` | BUSINESS_REJECTION | 变更后数量低于累计有效发货 |
| `SHIPMENT_DRAFT_NOT_FOUND` | NOT_FOUND | 发货草稿不存在 |
| `SHIPMENT_RESERVATION_INSUFFICIENT` | BUSINESS_REJECTION | 发货来源占用不足或失效 |
| `SHIPMENT_ELIGIBILITY_REJECTED` | BUSINESS_REJECTION | 阶段、质量、规格或归属不满足交付 |
| `SHIPMENT_QUANTITY_EXCEEDED` | BUSINESS_REJECTION | 发货超过约定剩余数量 |
| `SHIPMENT_ALREADY_DISPATCHED` | BUSINESS_REJECTION | 实物已发出，不能恢复来源 |
| `SHIPMENT_REFERENCED_BY_AFTER_SALES` | BUSINESS_REJECTION | 发货已被售后引用 |
| `ORDER_RECEIVABLE_INVALID` | BUSINESS_REJECTION | 应收调整非法或产生负应收 |
| `ORDER_PAYMENT_AMOUNT_INVALID` | VALIDATION | 收退款金额非法 |
| `ORDER_REFUND_OBLIGATION_INVALID` | BUSINESS_REJECTION | 退款义务为负、低于已支付金额或引用关系非法 |
| `ORDER_PAYMENT_OBLIGATION_PENDING` | BUSINESS_REJECTION | 订单仍有待收、待退或资金更正 |
| `ORDER_DELIVERY_OBLIGATION_PENDING` | BUSINESS_REJECTION | 工作室交付履约未完成 |
| `ORDER_COMPLETION_CONDITION_NOT_MET` | BUSINESS_REJECTION | 双向履约条件未满足 |
| `LEGACY_ORDER_INTAKE_NOT_REQUIRED` | BUSINESS_REJECTION | 纯历史已完结订单不应接管 |
| `LEGACY_ORDER_QUANTITY_UNBALANCED` | BUSINESS_REJECTION | 接管数量不守恒 |
| `LEGACY_ORDER_FINANCIAL_UNBALANCED` | BUSINESS_REJECTION | 接管资金累计不守恒 |
| `LEGACY_ORDER_CORRECTION_BLOCKED` | BUSINESS_REJECTION | 接管值已被下游事实消费，不能按该差额更正 |

参与模块错误保持原错误码，不由 orders 包装为同义码。

## 24. 权限能力

```text
ORDER_VIEW
ORDER_CREATE
ORDER_UPDATE_DRAFT
ORDER_CONFIRM
ORDER_CANCEL

ORDER_CHANGE_VIEW
ORDER_CHANGE_CREATE
ORDER_CHANGE_EVALUATE
ORDER_CHANGE_CONFIRM

SHIPMENT_VIEW
SHIPMENT_DRAFT_MANAGE
SHIPMENT_CONFIRM
SHIPMENT_CORRECT
SHIPMENT_LOGISTICS_CORRECT

ORDER_SETTLEMENT_VIEW
ORDER_RECEIPT_RECORD
ORDER_PRICE_REFUND_DECIDE
ORDER_PRICE_REFUND_PAY
ORDER_OVERPAYMENT_RETURN
ORDER_PAYMENT_CORRECT

ORDER_COMPLETION_CONFIRM
ORDER_COMPLETION_INVALIDATE_INTERNAL

LEGACY_ORDER_INTAKE_VIEW
LEGACY_ORDER_INTAKE_CONFIRM
LEGACY_ORDER_INTAKE_CORRECT
```

`ORDER_COMPLETION_INVALIDATE_INTERNAL` 不作为普通页面按钮权限；仅由受控数据更正命令在满足下游规则时调用。已有订单接管使用永久业务权限，不使用临时初始化开关。

## 25. 批量边界

首期不提供批量订单确认、批量订单变更、批量发货、批量收退款和批量完成。它们涉及不同业务根、来源、任务与资金事实，隐式批量会扩大锁范围并产生不明确的部分成功语义。

允许的批量仅限一个订单内的多商品、多交付约定组和一次发货的多明细；整个命令先校验、统一锁定、任一明细失败则整体回滚。

## 26. 投影重建与一致性检查

按单订单业务根提供受控检查和重建：

- 从有效确认、变更、发货、取消和更正事实重建订单商品与约定当前数量；
- 从有效发货事实和发货更正重建累计发货；
- 普通新订单从应收、退款决定和资金事实重建付款义务；已有订单以 `legacy_order_intake_financials` 为期初基线，叠加接管更正和接管后事实重建；
- 从完成和失效事实重建订单完成状态；
- orders 自有履约投影只重建订购、发货、发货占用和待确认变更维度，不复制 production/scheduling 当前投影；
- 与 production 的需求、来源消费和发货占用按稳定业务标识对账；
- 与 finance 的现金事实按 `finance_cash_fact_id` 对账；
- 与 scheduling 的待处理任务摘要对账。

跨模块一致性检查只报告差异，不直接静默覆盖。修复必须通过所属模块更正命令或受控投影重建。

## 27. Flyway 与数据库约束

迁移必须：

- 建立本文专用事实、快照、草稿、占用引用和投影表；
- 对模块内稳定父子关系使用物理外键；
- 跨模块只保存稳定业务标识，不建立未登记物理外键；
- 为不可变事实禁止业务更新和物理删除的 Repository 路径；
- 建立数量非负、金额范围、状态组合和版本约束；
- 建立幂等参与、事实唯一性和来源重复消费防线；
- 不建立公共库存、固定路线、统一 `SHIPPABLE` 节点、通用事件表或通用资金流水表；
- 不使用触发器编排生产、排班和 finance。

## 28. 验证门禁

### 28.1 普通订单

- 同一商品多个约定组数量守恒；
- 缝边剪袋交付约定必含捏毛装袋工序；
- 客户供料保留材料需求和标准价值；
- 主数据修改不刷新订单快照；
- 确认失败不留下 production 需求或部分快照；
- 确认不创建排班任务。

### 28.2 订单变更

- 影响预览不产生事实和占用；
- 正式提交重新评估，旧影响版本被拒绝；
- 任务取消确认绑定任务版本；
- 任一任务已核验、管理员未确认或任务版本变化时全部回滚；
- 变更后数量不得低于累计发货；
- 旧约定版本和已发货快照保持不变。

### 28.3 发货

- 草稿创建、修改和取消正确创建、替换和释放 production 占用；
- 两个并发草稿不能重复占用同一来源数量；
- 确认发货原子消费来源并追加 orders 发货事实；
- 来源链接合计等于发货数量；
- 阶段、质量、规格或归属不符时整体拒绝；
- 实物已发不允许恢复来源；
- 售后引用和完成事实正确阻止非法更正。

### 28.4 资金与完成

- orders 业务资金事实和 finance 现金事实原子提交；
- 多收款退回不再次减少订单应收；
- 净收、待收和待退款按事实重建一致；
- 单边履约完成不能确认订单完成；
- 管理员确认时间是订单完成时间；
- 完成失效后不会自动重新完成；
- 售后资金不改变原订单应收和完成状态。

### 28.5 已有订单接管

- 数量守恒公式真实执行；
- 当前状态数量与未发制品数量一致；
- 历史发货与当前制品不重复；
- 接管不创建历史任务、核验、逐笔发货或逐笔资金；
- 接管确认一次性创建 orders 与 production 当前事实；
- 接管更正不能破坏接管后下游消费；
- 纯历史已完成且无后续管理事项的订单被拒绝接管。

### 28.6 并发、幂等和重建

- 相同幂等键不产生第二套事实；
- 相同幂等键不同请求指纹被拒绝；
- expectedVersion 冲突不会部分提交；
- 锁序遵守 orders → scheduling → production → finance；
- 真实 MySQL 并发测试证明不超发、不重复收款、不重复完成；
- 单订单投影可校验和重建；
- 跨模块失败时整条命令回滚。

## 29. 明确不采用

- 一条订单商品只能有一个统一交付阶段；
- 用缝边数量作为唯一流程分桶；
- 固定 `制作 → 捏毛装袋 → 缝边剪袋 → SHIPPABLE` 路线；
- 订单确认后继续读取商品当前成本代替冻结快照；
- 订单变更覆盖旧约定版本或已发货快照；
- 客户供料时删除材料需求或把整道工序材料成本归零；
- 草稿数量隐式充当正式来源占用；
- orders 直接修改 production 来源余额；
- production 直接修改订单累计发货；
- 用物流更正修改发货数量；
- 实物已发时以账面更正恢复来源；
- 用资金净额单独判断订单完成；
- 双方履约满足后自动完成订单；
- 完成后重新打开原订单处理新事项；
- 已有订单补造历史任务、核验、逐笔发货或逐笔现金；
- 用公共库存或跨订单领用补足订单；
- Controller 编排多个模块或参与端口暴露为 HTTP。

## 30. 最终确认结论

```text
orders 拥有普通订单、交付约定、普通发货、订单资金义务和完成事实
交付约定按数量分组并以不可变版本演进
订单确认冻结客户、商品、工序、材料、价格和预计成本
订单变更统一协调任务取消、生产归组和金额调整
发货草稿通过 production 正式占用来源
普通发货事实与生产来源消费事实分属两个模块并同事务提交
订单交付履约和付款履约分别投影
订单完成必须由管理员确认
完成后的新事项进入独立售后
已有订单仅接管当前事实并保持数量、状态和资金守恒
```

# 订单模块施工文档（阶段三：订单草稿、确认、变更与共同数量）

日期：2026-09-24  
修改人：chen  
状态：**已评审，实施中**（2026-09-24）；按 `openspec/changes/build-yumi-v2-order-fulfillment/tasks.md` 的 3.1–3.15 逐条实施，逐项证据回填该文件。  
上游依据：`docs/architecture/domain-and-quantity-model.md`（数量分层与不变量）、`docs/architecture/database-design.md` §5–6/§12–15（表归属与约束）、`specs/order-lifecycle/spec.md`（Requirement/Scenario）、`design.md` §4/§5/§6/§7（事务、API 与前端矩阵）。

## 1. 范围

**本阶段做**：订单表结构（8 张）、订单编号、草稿创建/查询/编辑、明细 Q/E 与金额/成本/利润、确认校验与单事务快照、共同数量初始化与履约视图、订单变更草稿与确认（含减单不变量）、订单取消、订单多维只读状态、`/orders`、`/orders/new`、`/orders/:id`、`/orders/:id/changes/:changeId` 四个正式路由，以及 3.14/3.15 的测试与人工验收。

**本阶段不做**：库存领用、生产计划与核验、返工/重做、发货、收退款、售后、报表导出、关闭（阶段四–九）。表中为这些阶段预留的列（见 §3.7/§3.8）**本阶段只建列与约束、不写入**；不建空实现、不建占位服务。

## 2. 编号

| 空间 | 前缀 | 宽度 | 示例 | `number_sequences.sequence_key` |
| --- | --- | --- | --- | --- |
| 订单 | `YM` | 7 | `YM00001` | `orders` |
| 订单变更 | `CO` | 7 | `CO00001` | `order_changes` |

沿用既有 `SequenceAllocator`：事务内锁定对应序列行并递增，业务编号列建唯一索引。变更单编号在**创建变更草稿时**分配。

## 3. 表结构

金额一律 `DECIMAL(19,4)`、比例 `DECIMAL(9,6)`、数量为 `INT UNSIGNED`（非负）；所有表带 `version`、`created_at`、`updated_at`、`created_by`、`updated_by`、`request_id`、`idempotency_key`（不适用者写 NULL）。

### 3.1 `orders`（订单主表）

| 列 | 类型 | 说明 |
| --- | --- | --- |
| `id` / `order_no` | BIGINT UNSIGNED / CHAR(7) | 主键 / 业务编号，唯一 |
| `customer_id` / `customer_name` | BIGINT UNSIGNED / VARCHAR(200) | 客户引用（FK）+ 下单时名称快照，供列表与打印 |
| `status` | VARCHAR(16) | `DRAFT` / `CONFIRMED` / `CANCELLED` / `CLOSED` |
| `order_date` | DATE | 下单日期 |
| `expected_delivery_date` | DATE NULL | 期望交期，可空 |
| `recipient_name` / `recipient_phone` / `region` / `address` | VARCHAR(100/32/100/300) | 当前收货信息（草稿可改；确认时冻结进快照） |
| `note` | VARCHAR(1000) NULL | 整单备注 |
| `goods_amount` | DECIMAL(19,4) | Σ 明细商品金额 |
| `seam_amount` | DECIMAL(19,4) | Σ 明细缝边收费 |
| `discount_amount` | DECIMAL(19,4) | 整单优惠 |
| `receivable_amount` | DECIMAL(19,4) | 应收 = 商品金额 + 缝边收费 − 优惠 |
| `goods_cost_amount` | DECIMAL(19,4) | Σ 明细商品成本 |
| `seam_cost_amount` | DECIMAL(19,4) | Σ 明细缝边成本 |
| `cost_amount` | DECIMAL(19,4) | 总成本 = 商品成本 + 缝边成本 |
| `profit_amount` | DECIMAL(19,4) | 利润 = 应收 − 总成本 |
| `confirmed_at` / `confirmed_by` | DATETIME(6) / BIGINT UNSIGNED NULL | 确认信息 |
| `cancelled_at` / `cancelled_by` / `cancel_reason` | 同上 NULL | 取消信息 |
| `closed_at` / `closed_by` | 同上 NULL | 关闭信息（阶段七写入） |

索引：`uk_orders_order_no`、`idx_orders_status`、`idx_orders_customer`、`idx_orders_order_date`。  
CHECK：`discount_amount >= 0`、`discount_amount <= goods_amount + seam_amount`、各金额列 `>= 0`（`profit_amount` 允许为负）。

### 3.2 `order_items`（明细当前有效值）

| 列 | 说明 |
| --- | --- |
| `order_id` / `line_no` | FK + 明细序号；唯一键 `uk_order_items_order_line (order_id, line_no)` |
| `product_id` / `product_no` / `product_name` | 商品引用与识别快照（商品改名不影响订单显示） |
| `quantity` | 当前有效订购数量 **Q** |
| `seam_quantity` | 当前缝边数量 **E** |
| `unit_price` / `goods_amount` | 商品成交单价 / 商品金额 = `unit_price × quantity` |
| `seam_type_id` / `seam_type_name` | 缝边种类引用 + 名称快照，可空＝不缝边剪袋 |
| `seam_unit_cost` / `seam_fee` | 种类成本单价快照（成本与提示）/ 缝边收费单价 |
| `seam_amount` | 缝边收费 = `seam_fee × seam_quantity` |
| `unit_cost` / `goods_cost_amount` | 商品单件成本快照（商品 `total_cost`，**不缝边剪袋**口径）/ 商品成本 = `unit_cost × quantity` |
| `seam_cost_amount` | 缝边成本 = `seam_unit_cost × seam_quantity` |
| `note` | 明细备注 |

索引：`idx_order_items_order`、`idx_order_items_product`、`idx_order_items_seam_type`（缝边种类删除守卫用）。  
CHECK：`seam_quantity <= quantity`（Q/E 检查）、`quantity >= 0`、`seam_quantity >= 0`。

> 明细不通过删除重建改变历史身份：草稿阶段可增删行；已确认后只能由变更单改数量/价格/缝边或标记移除，行与历史保留。

### 3.3 `order_confirmation_snapshots`（订单级确认快照）

`order_id` 唯一。列：客户快照（`customer_id`/`customer_no`/`customer_name`/`contact`/`phone`）、收货快照（四项）、金额快照（§3.1 的八个金额列）、`note`、`confirmed_by`、`confirmed_at`。确认后不可修改。

### 3.4 `order_item_snapshots`（明细级确认快照）

`order_item_id` 唯一。列：`order_id`、`line_no`、商品识别与说明（`product_no`/`product_name`/`product_note`/`image_file_id`）、星级（`star_level_id`/`star_name`/`star_std_minutes`）、数量（`quantity`/`seam_quantity`）、价格与金额（`unit_price`/`goods_amount`/`seam_type_id`/`seam_type_name`/`seam_unit_cost`/`seam_fee`/`seam_amount`）、成本（`unit_cost`/`goods_cost_amount`/`seam_cost_amount`）、**商品成本组成快照**（`glue_grams`/`glue_cost`/`colorpaste_cost`/`material_cost`/`product_labor_fee`/`packaging_labor_fee`/`box_labor_fee`/`labor_cost`/`other_cost`/`total_cost`）、`flow`（冻结流程文本：`制作 → 捏毛装袋 → 缝边剪袋 → 可发货`，不缝边剪袋时为 `制作 → 捏毛装袋 → 可发货`）、`note`。

### 3.5 `order_change_orders`（变更单）

`change_no` 唯一、`order_id`、`status`（`DRAFT`/`CONFIRMED`）、`reason`、变更后表头值（`new_expected_delivery_date`、`new_recipient_*` 四项、`new_note`、`new_discount_amount`；未变更的列为 NULL＝沿用原值）、`created_by`/`created_at`/`confirmed_by`/`confirmed_at`。

### 3.6 `order_change_items`（变更明细，结构化前后值）

`change_order_id`、`order_item_id`（新增行为 NULL）、`change_type`（`ADD`/`UPDATE`/`REMOVE`）、`line_no`（新增行的序号）、`product_id`（新增行）；结构化前后值各两列：`before_quantity`/`after_quantity`、`before_seam_quantity`/`after_seam_quantity`、`before_unit_price`/`after_unit_price`、`before_seam_type_id`/`after_seam_type_id`、`before_seam_fee`/`after_seam_fee`、`before_note`/`after_note`；超出处理三列（`surplus_disposition`＝`FINISH_TO_SURPLUS`/`SCRAP`、`surplus_quantity`、`surplus_reason`）。

CHECK：`change_type = 'ADD'` 时 `order_item_id IS NULL`，`UPDATE`/`REMOVE` 时非空；`surplus_quantity >= 0`。

### 3.7 `fulfillment_entries`（履约事实，不可变）

`order_id`、`order_item_id`、`entry_type`（`ORDER_DEMAND`/`ORDER_CHANGE`/`INVENTORY_ALLOCATION`/`PRODUCTION_QUALIFIED`/`REWORK_IN`/`REMAKE_IN`/`FINISHED_SURPLUS`/`SHIPMENT_CONSUME`/`SHIPMENT_RESTORE`）、`node`（`MAKING`/`PACKING_BAG`/`SEAM_CUTTING`/`SHIPPABLE`）、`direction`（`IN`/`OUT`）、`quantity`、`source_type`、`source_id`、`source_line_id` NULL、`business_date`、`operator_id`、`reverses_entry_id` NULL（冲销关系）、`note`。

**来源唯一消费**：`uk_fulfillment_entries_source (source_type, source_id, source_line_id, node, direction)`。  
索引：`idx_fulfillment_entries_item (order_item_id, node, source_type)`、`idx_fulfillment_entries_order (order_id)`。  
事实不可改：不提供 UPDATE/DELETE 入口，更正一律写入反向或替代事实。

### 3.8 `order_item_fulfillment_balances`（明细当前投影）

`order_item_id` 唯一。列：`order_id`、`required_quantity`（当前有效需求 Q）、`making_inflow`/`packing_inflow`/`seam_inflow`（各工序有效流入）、`making_planned`/`packing_planned`/`seam_planned`（有效计划占用）、`verified_processed`（已核验处理）、`rework_pending`/`remake_pending`（待安排）、`shippable_quantity`（可发货）、`shipped_quantity`（累计有效发货）、`finished_surplus_quantity`（成品余量）。

只允许领域服务在写 `fulfillment_entries` 的同一事务内更新；阶段三只写 `required_quantity` 与订单需求/变更事实，其余列由阶段四/五/六写入。阶段九提供按事实重建并与本表比对的一致性检查。

### 3.9 `order_inventory_plan_lines`（草稿库存计划，阶段四追加）

`V9__order_inventory_plan.sql`。列：`order_id`、`order_item_id`、`batch_id`、`quantity`。

**计划不占用库存**（`specs/inventory-management/spec.md`「草稿库存计划不占用库存」）：本表不产生库存流水、不改 `inventory_batches.quantity`，批次当前数量仍只由 `InventoryMovement` 改动。`order_item_id` 有外键，因此整体替换草稿明细前必须先清计划行（`OrderRepository.deletePlanLines`）。

- 唯一键 `uk_order_inventory_plan_lines_target (order_item_id, batch_id)`：同一明细同一批次只保留一行，要更多数量就改这一行；同一批次仍可拆给不同明细。
- 写入契约用**明细序号 `lineNo`** 而不是明细 id：草稿明细整单替换，明细 id 每次保存都会变，序号才稳定。
- 表归属 `orders`（`database-design.md` §7）；因外键依赖 `inventory_batches`，迁移排在 `V8` 之后的 `V9`。
- 计划行**不存接入节点**：`orders` 不得依赖 `inventory` 模块（模块循环已由 `orders → ledger` 单向边断开），接入矩阵只在领用时由库存模块判定，计划因此只是「打算用哪些批次、给哪条明细、多少件」。

## 4. 金额、成本与利润口径

公式登记到 `calculation/order`，编号 `FP-ORDER-01..09`，同步写入 `docs/architecture/formula-catalog.md`。

| 标识 | 名称 | 表达式 | 舍入 |
| --- | --- | --- | --- |
| FP-ORDER-01 | 明细商品金额 | `unit_price × quantity` | scale4 HALF_UP |
| FP-ORDER-02 | 明细缝边收费 | `seam_fee × seam_quantity` | scale4 HALF_UP |
| FP-ORDER-03 | 明细商品成本 | `unit_cost × quantity`（`unit_cost` 取商品 `total_cost`，不缝边剪袋口径） | scale4 HALF_UP |
| FP-ORDER-04 | 明细缝边成本 | `seam_unit_cost × seam_quantity`（种类成本单价允许 0） | scale4 HALF_UP |
| FP-ORDER-05 | 订单商品金额 | Σ 明细商品金额 | scale4 HALF_UP |
| FP-ORDER-06 | 订单缝边收费 | Σ 明细缝边收费 | scale4 HALF_UP |
| FP-ORDER-07 | 订单应收 | `goods_amount + seam_amount − discount_amount` | scale4 HALF_UP |
| FP-ORDER-08 | 订单总成本 | Σ（明细商品成本 + 明细缝边成本） | scale4 HALF_UP |
| FP-ORDER-09 | 订单利润 | `receivable_amount − cost_amount` | scale4 HALF_UP |

口径说明：

- **优惠只作用于应收**：`discount_amount` 不改变成本与利润之外的任何量；`profit_amount` 允许为负。
- **缝边成本与商品成本分列**（`goods_cost_amount` / `seam_cost_amount`），利润按“应收 − 商品成本 − 缝边成本”计算，与规格一致。
- 商品成本取**下单/编辑当时**的商品 `total_cost` 快照；确认时该值再冻结进明细快照，之后商品改价不影响已确认订单。
- 金额一律服务端计算，客户端提交的派生金额被忽略（与商品侧一致）。

## 5. 数量口径与共同数量

沿用 `domain-and-quantity-model.md` §5：`不缝边需求 = Q − E`、`制作共同需求 = Q`、`捏毛装袋共同需求 = Q`、`缝边剪袋需求 = E`、`最终交付需求 = Q`，**不得按工序相加**。

阶段三落地的部分：

1. 确认时对每条明细写入 `required_quantity = Q` 与一条 `ORDER_DEMAND` 履约事实（节点 `SHIPPABLE`、方向 `IN`），作为需求基线；
2. 变更确认时按数量差写入 `ORDER_CHANGE` 事实（增为正、减为负）并同步 `required_quantity` 与 `order_items.quantity`；
3. `GET /api/orders/{id}/fulfillment` 返回 `Q`/`E`/不缝边需求/最终交付需求与四层进度，其中工序流入、可发货、累计发货在本阶段恒为 0（阶段四–六写入），**不用 0 伪造状态**：派生状态按事实判定为「未开始/未发货」。
4. 派生状态由事实计算，不提供手工状态列：主状态、排产状态、执行条件、生产进度、需求处理状态、发货进度（§7）。

## 6. 状态机与合法转换

`orders.status`：`DRAFT` →（确认）→ `CONFIRMED` →（关闭）→ `CLOSED`；`DRAFT` →（取消）→ `CANCELLED`；`CONFIRMED` →（无任何履约事实时取消）→ `CANCELLED`。`CANCELLED`/`CLOSED` 为终态，**不可重开**。

- 草稿可编辑（`PATCH /api/orders/{id}`）；非草稿返回 `STATE_NOT_EDITABLE`。
- 确认要求：至少一条明细、商品存在且启用、`E ≤ Q`、金额一致、交期合法（`expected_delivery_date` 不早于 `order_date`）、客户与收货齐全；不满足返回 `STATE_NOT_CONFIRMABLE` / `VALIDATION_INVALID`。
- 变更仅对 `CONFIRMED` 且未取消/关闭的订单开放；草稿直接改，不走变更单。
- 取消守卫：草稿直接取消；已确认仅在**无任何履约事实**（阶段七起追加“无款项事实”）时可取消，否则返回 `STATE_CANCEL_NOT_ALLOWED`。
- 并发：`orders`/`order_items` 用 `@Version` 乐观锁，版本失配返回 `CONFLICT_VERSION`；确认与变更确认在同一事务内完成并重读校验。

## 6.1 派生状态规则（任务 3.10）

一个状态不表达所有含义。主状态为 `orders.status`，仅由命令转换；其余五类全部由事实与数量派生，
**明细与订单级共用同一规则**（订单级＝明细数量求和后套用同一函数，列表页一次汇总查询）：

| 状态 | 取值 | 规则 |
| --- | --- | --- |
| 排产状态 | 未排产 / 已排产 | 有计划占用（`making_planned + packing_planned + seam_planned > 0`）则 `已排产` |
| 执行条件 | 等待上游 / 可执行 | `制作有效流入 − 已核验处理 > 0` 则 `可执行` |
| 生产进度 | 未开始 / 生产中 / 部分完成 / 生产处理完成 | `已核验处理 = 0` 时（有计划则 `生产中`，否则 `未开始`）；否则未达当前有效需求 `部分完成`、达到则 `生产处理完成` |
| 需求处理状态 | 仍有待履约 / 部分处理 / 全部处理 | 只看 `累计有效发货` 与当前有效需求 |
| 发货进度 | 未发货 / 部分发货 / 全部发货 | 只看 `累计有效发货` 与当前有效需求 |

**关键口径**：生产报废、计划创建与成品余量都**不**改变客户需求口径——需求处理状态与发货进度只看累计有效发货，
因此“生产处理完成”不等于“客户需求已处理完”。

## 7. API 契约（阶段三）

| 能力 | 方法与路径 | 幂等 | 成功结果 | 主要拒绝码 |
| --- | --- | --- | --- | --- |
| 订单列表 | `GET /api/orders` | 否 | 按状态/客户/日期筛选的摘要列表（含主状态与派生进度） | `AUTH_REQUIRED` |
| 新建草稿 | `POST /api/orders` | 写入幂等 | 分配 `YM` 编号的草稿 + 明细 + 金额 +（可选）草稿库存计划 | `VALIDATION_INVALID`, `STATE_DISABLED` |
| 订单详情 | `GET /api/orders/{id}` | 否 | 订单、明细、金额、快照（已确认时）、草稿库存计划与派生状态 | `NOT_FOUND` |
| 编辑草稿 | `PATCH /api/orders/{id}` | 写入幂等 | 整体重算金额后的草稿；`inventoryPlan` 非空整体替换、空列表清空、不传保持原值 | `STATE_NOT_EDITABLE`, `VALIDATION_INVALID`, `CONFLICT_VERSION` |
| 确认 | `POST /api/orders/{id}/confirm` | 必须幂等 | 单事务快照 + 已确认订单；入参 `transferShortageToProduction` 表示管理员明确将计划缺口转生产 | `STATE_NOT_CONFIRMABLE`, `SNAPSHOT_FAILED`, `STOCK_INSUFFICIENT`, `CONFLICT_VERSION` |
| 履约视图 | `GET /api/orders/{id}/fulfillment` | 否 | Q/E、共同数量、四层进度与派生状态 | `NOT_FOUND` |
| 变更草稿 | `POST /api/orders/{id}/change-orders`、`GET/PATCH /api/order-changes/{id}` | 写入幂等 | 变更草稿（前后值 + 超出处理） | `STATE_NOT_CHANGEABLE`, `QUANTITY_REQUIRES_DISPOSITION` |
| 变更确认 | `POST /api/order-changes/{id}/confirm` | 必须幂等 | 应用后订单 + 新投影 | `QUANTITY_BELOW_SHIPPED`, `CONFLICT_VERSION` |
| 取消 | `POST /api/orders/{id}/cancel` | 必须幂等 | 已取消订单 + 取消人/时间/原因 | `STATE_CANCEL_NOT_ALLOWED` |

幂等：写命令要求 `Idempotency-Key`，重复键返回首次成功结果；只读查询不要求。响应统一信封（§design 6）。

## 8. 前端页面要点（正式路由）

| 路由 | 页面 | 关键点 |
| --- | --- | --- |
| `/orders` | 订单列表 | 主状态/生产进度/发货进度分列；筛选紧凑排列；显式「新建订单」入口 |
| `/orders/new` | 步骤化全页工作区 | 步骤：客户与收货 → 商品明细与 Q/E → 金额与优惠 → 库存计划（可选）→ 确认复核；金额只读展示服务端值；缝边默认值取商品；计划步骤按明细行选批次（显示工序/缝边/现有数量）并提示「不占用库存」，确认遇缺口时给出缺口明细与「缺口转生产并确认」 |
| `/orders/:id` | 订单详情（只读多 Tab） | 总览 / 商品与履约 / 发货与售后 / 资金与利润 / 资料与变更；Tab 不新增路由；总览用可容纳 12+ 明细的简表；履约按阶段卡片；查看不预置表单，新建/编辑/处理走显式入口 |
| `/orders/:id/changes/:changeId` | 变更确认操作页 | 明确列出变更前后、已发货下限、在途超出处理、待退款影响、确认后不可覆盖 |

视觉基线：全页白底 + 浅色侧栏 + 顶部面包屑 + **单组订单页签**，使用 Ant Design 现成组件表达层级，不在同页并排 A/B 方案；参考草图 `.superpowers/brainstorm/39592-1790147910/content/order-detail-antd-admin-v1.html`。

## 9. 不变量与错误码

不变量（实现与测试必须保证，取自 `domain-and-quantity-model.md` §14 的本阶段部分）：

1. 工序数量不相加为订单数量；
2. 同一来源只能消费一次（`fulfillment_entries` 唯一键）；
3. 减单不得低于累计有效发货（本阶段累计发货恒为 0，仍按同一公式实现）；
4. 报废不自动减少客户需求；
5. 已确认事实不物理删除；
6. 所有汇总可由来源事实重建。

错误码沿用既有分层：`VALIDATION_INVALID`、`NOT_FOUND`、`CONFLICT_VERSION`、`CONFLICT_DUPLICATE`、`STATE_NOT_EDITABLE`、`STATE_NOT_CONFIRMABLE`、`STATE_NOT_CHANGEABLE`、`STATE_CANCEL_NOT_ALLOWED`、`QUANTITY_INVALID`、`QUANTITY_BELOW_SHIPPED`、`QUANTITY_REQUIRES_DISPOSITION`、`SNAPSHOT_FAILED`、`AUTH_REQUIRED`。

## 10. 不做项

- 不改写 V1–V6；本阶段新增 `V7__orders.sql`（新增表，不改既有表结构）。若评审要求改动 V7 内容，按未上线口径清库重建，不用 `flyway repair`。
- 不提供通用状态覆盖端点（不手工改状态）。
- 不实现订单级“打印/PDF”、报表导出与物流字段（阶段九）。
- 不做明细行级删除历史：已确认后的移除只标记，不物理删行。
- 不引入 Docker/Testcontainers；沿用本机 `yumi_v2_test` 集成测试。

## 11. 待确认项（2026-09-24 已确认）

1. **订单级优惠的作用面**：确认按 §4 口径 `应收 = 商品金额 + 缝边收费 − 优惠`，优惠不进入成本、利润允许为负（**用户 2026-09-24 确认“作用于整单应收”**）。
2. **变更单编号前缀** `CO`（示例 `CO00001`）可用（随评审通过）。
3. **明细允许同一商品多行**（不同缝边/价格），规格要求总览可容纳 12+ 明细（随评审通过）。
4. **`E = 0` 视为不缝边剪袋**：`seam_type_id`/`seam_fee`/`seam_unit_cost` 存 NULL 或 0，避免“选了种类但数量为 0”的歧义；要缝边则 `E ≥ 1`（**用户 2026-09-24 确认“E=0 视为不缝边剪袋”**）。

### 实施状态（2026-09-24）

3.1–3.13 已实施：后端 3.1–3.10 通过全量门禁（**194 测试 0 失败**），前端 3.11–3.13 四个正式路由（`/orders`、`/orders/new`、`/orders/:id`、`/orders/:id/changes/:changeId`）通过 typecheck / 37 用例 / build 并完成浏览器自测；3.14（阶段测试汇总）与 3.15（人工验收）待做，人工签字状态 `pending-user-signoff`。

### 阶段边界：减单超出处理方案的执行事实（任务 3.8）

阶段三把「继续完成转成品余量 / 立即报废」落库为 `order_change_items` 的**结构化决策**，并随变更确认同事务更新
需求（`required_quantity`）、金额（订单表头八个金额列）与 `ORDER_CHANGE` 履约事实。对在制/合格数量的
**执行事实**（从哪个节点流出、是否计入可发货、是否生成成品余量）不在阶段三伪造——在制/合格数量由阶段五
生产核验产生，其处置语义属阶段五，届时按本方案执行。理由：阶段三在制数量恒为 0，此处臆造节点与方向会在阶段五返工。

### 实施项与验收（对应 tasks.md 3.1–3.15）

| 任务 | 实施项 | 验收 |
| --- | --- | --- |
| 3.1 | `V7__orders.sql` 建 8 张表 + 索引 + CHECK + `number_sequences` 两键 | 迁移测试断言表/列/唯一键/CHECK/外键；空库重建通过 |
| 3.2 | `YM` 编号、`GET/POST /api/orders`、`GET/PATCH /api/orders/{id}` | HTTP 测试：编号单调、草稿可编辑、非草稿 `STATE_NOT_EDITABLE`、版本冲突 |
| 3.3 | FP-ORDER-01..09 与 `calculation/order` | 基线算例逐字段字符串比对；客户端派生金额被忽略 |
| 3.4 | 确认校验 + 单事务快照 | 确认后快照逐字段一致；不自动建计划 |
| 3.5 | 确认原子性测试 | 任一校验失败整笔回滚、无部分快照；重复幂等键返回同一订单 |
| 3.6 | 共同数量初始化 + `GET /api/orders/{id}/fulfillment` | Q/E 与共同数量不按工序相加；派生进度而非手工状态 |
| 3.7 | 变更草稿与确认 | 前后值结构化落库；新增/改/移除三类路径 |
| 3.8 | 减单不变量 | 低于累计发货拒绝；超出在制/合格逐项处理 |
| 3.9 | 取消 | 草稿可取消；无事实的已确认可取消；有事实拒绝且不删历史 |
| 3.10 | 多维只读状态 | 六类状态派生；报废/建计划/余量不自动完成客户需求 |
| 3.11 | `/orders`、`/orders/new` | 步骤化工作区、服务端金额权威 |
| 3.12 | `/orders/:id` 只读多 Tab | 单组订单页签、总览容纳 12+ 明细、查看与操作分离 |
| 3.13 | `/orders/:id/changes/:changeId` | 变更前后、已发货下限、超出处理、待退款影响 |
| 3.14 | 领域/HTTP/MySQL 测试 | 覆盖快照、Q/E、金额字符串、并发、减单下限、余量、取消、投影重建、状态派生 |
| 3.15 | 阶段人工验收 | `humanVisualConclusion.checklist`，状态 `pending-user-signoff` |


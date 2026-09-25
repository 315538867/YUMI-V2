# 生产模块施工文档（阶段五：生产计划、核验、返工、重做与超额提醒）

日期：2026-09-25  
修改人：chen  
状态：**待评审**；按 `openspec/changes/build-yumi-v2-order-fulfillment/tasks.md` 的 5.1–5.17 逐条实施，逐项证据回填该文件。  
上游依据：`docs/architecture/domain-and-quantity-model.md`（§5–§7 流程分流/有效流入/生产核验、§11 超额任务、§14 不变量）、`docs/architecture/database-design.md` §8（生产模块表）、§12–§15（编号、索引、约束、Flyway 门禁）、`specs/production-management/spec.md`（Requirement/Scenario）、`design.md` §4/§5/§6/§7（事务、事实不可变、API 与前端矩阵）。

## 1. 范围

**本阶段做**：10 张生产表（V10 迁移）、生产计划编号 `PN` 与其他排班编号 `OS`、正常/返工/重做/超额四类计划的创建与查询、待安排与当前可执行计算、一次性核验与逐工序合格流转、返工来源与重做来源、待执行计划取消与来源恢复、未完成待处理（重新安排/部分安排/暂不安排）、超额预占与超额提醒处理、其他排班与一次性工时核验及更正、`/production` 工作台与 `/production/plans/:id/verify` 核验页，以及 5.16/5.17 的测试与人工验收。

**本阶段不做**：发货（阶段六）、收退款与关闭（阶段七）、售后核验与售后补发（阶段八，本阶段只保留计划类型枚举与来源校验入口）、成品余量与减单超出处置（阶段七）、工资与工时结算（延期，本阶段只落工时事实）、报表导出（阶段九）。**不建空实现、不建占位服务。**

**不改动阶段三/四既有表结构**（初稿曾提议拆 `verified_processed`，已否决）：按工序的「已核验处理」由**生产事实**汇总得到（`SUM(production_verifications.completed_quantity) GROUP BY node`），而不是给投影表加三个分项列。理由：①「已核验处理」的事实来源就是核验表，投影列只是缓存，按事实汇总天然满足「所有汇总均可从来源事实重建」；②避免改阶段三/四的 `FulfillmentRepository`、`OrderStatuses`、`FulfillmentViews`、前端与既有测试；③`verified_processed` 继续作为**全部工序合计**由阶段五在核验的同一事务内累加，订单侧生产进度口径不变。阶段五只**写入**既有的投影列（`making_planned`/`packing_planned`/`seam_planned`、`verified_processed`、`rework_pending`/`remake_pending`），不增删列。

## 2. 编号

| 空间 | 前缀 | 宽度 | 示例 | `number_sequences.sequence_key` |
| --- | --- | --- | --- | --- |
| 生产计划 | `PN` | 6 | `PN000001` | `production_plans` |
| 其他排班 | `OS` | 6 | `OS000001` | `other_schedules` |

沿用既有 `SequenceAllocator`（事务内锁定序列行并递增），业务编号列建唯一索引。核验、来源、预占、提醒、工时更正不单独编号（用 id + 所属计划/排班追溯）。

## 3. 表结构

数量为 `INT UNSIGNED`（非负）；所有表带 `version`、`created_at`、`updated_at`、`created_by`、`updated_by`、`request_id`、`idempotency_key`（不适用者写 NULL）。本阶段无金额列。

### 3.1 `production_plans`（生产计划）

| 列 | 类型 | 说明 |
| --- | --- | --- |
| `id` / `plan_no` | BIGINT UNSIGNED / CHAR(6) | 主键 / 业务编号，唯一 |
| `plan_type` | VARCHAR(32) | `NORMAL` / `REWORK` / `REMAKE` / `OVERTIME` / `AFTER_SALES_REWORK` / `AFTER_SALES_REPLACEMENT` |
| `order_id` / `order_item_id` | BIGINT UNSIGNED | 订单明细引用（售后两类在阶段八接入时同样挂原订单明细） |
| `after_sales_case_id` | BIGINT UNSIGNED NULL | 售后单引用（阶段八写入；阶段五恒 NULL） |
| `node` | VARCHAR(32) | `MAKING` / `PACKING_BAG` / `SEAM_CUTTING`（其他排班不走本表） |
| `plan_date` | DATE | 计划日期 |
| `employee_id` / `employee_name` | BIGINT UNSIGNED / VARCHAR(100) | 执行员工引用 + 姓名快照（员工改名不影响历史计划） |
| `quantity` | INT UNSIGNED | 计划数量 |
| `status` | VARCHAR(16) | `PENDING` / `VERIFIED` / `CANCELLED` |
| `source_type` | VARCHAR(32) | `ORDER`（正常）/ `REWORK_SOURCE` / `REMAKE_SOURCE` / `NONE`（超额，来源是预占集合） |
| `source_id` / `source_line_id` | BIGINT UNSIGNED | 来源记录；无明细行用 0（不用 NULL，保证唯一键对「无明细来源」生效） |
| `note` | VARCHAR(500) NULL | 备注 |
| `cancelled_at` / `cancelled_by` / `cancel_reason` | DATETIME(6) / VARCHAR(100) / VARCHAR(500) NULL | 取消信息 |

索引：`uk_production_plans_plan_no`、`idx_production_plans_date_node`、`idx_production_plans_employee`、`idx_production_plans_item`、`idx_production_plans_status`、`idx_production_plans_source (source_type, source_id)`。  
CHECK：`quantity > 0`、`status IN (...)`、`plan_type IN (...)`。

**核心归属字段创建后不可编辑**：`plan_type`、`order_item_id`、`node`、`source_*`、`quantity` 不可改；只有**待执行的正常计划**可通过 `production_plan_adjustments` 调整日期、员工、数量与备注（§3.2）。返工/重做/超额计划的归属与数量不可调整，需要变更时取消后重建（来源余额随取消恢复）。

**注意**：本表**不设** `(source_type, source_id, node)` 唯一键。同一来源可拆成多条计划（部分安排），来源余额由来源表的 `total − arranged` 扣减保证不超支；这与 `fulfillment_entries` 的「每笔来源只接入一次」是两回事（后者约束的是**流入事实**）。

### 3.2 `production_plan_adjustments`（待执行正常计划调整历史）

`plan_id`、`adjustment_type`（`DATE` / `EMPLOYEE` / `QUANTITY` / `NOTE` / `COMBINED`）、`before_*` / `after_*`（日期、员工 id + 姓名快照、数量、备注，按类型取用）、`reason`、操作人与时间。只允许 `status = PENDING` 且 `plan_type = NORMAL` 的计划调整；调整不产生核验、库存或履约事实。

**唯一写入入口是 5.12 的超额提醒处理**（`POST /api/production-reminders/overtime/{id}/adjust-plan`）——`database-design.md` §8 把本表定义为「待执行正常计划的调整历史」，而 tasks 5.x 中没有独立的计划调整接口；不另开 `POST /api/production-plans/{id}/adjustments`（避免同一动作两个入口）。

数量调整的可安排上限：调整后数量不得超过该明细该工序的**待安排数量 + 本计划原数量**（调整只在本计划已占用的额度内增减，不抢其他计划的额度）。

### 3.3 `production_verifications`（一次性核验）

`plan_id` 唯一（`uk_production_verifications_plan`）、`order_id` / `order_item_id`（冗余便于按明细查询）、`node`、`completed_quantity`、`qualified_quantity`、`rework_quantity`、`scrap_quantity`、`incomplete_quantity`、`verify_note`、`verified_by` / `verified_at`、审计列。

CHECK：`completed_quantity = qualified_quantity + rework_quantity + scrap_quantity`。  
`incomplete_quantity = 计划数量 − completed_quantity` 跨表，由应用在同一事务内计算写入并断言（数据库层用触发器不可移植，按 `database-design.md` §14「或等价触发前应用校验」处理）。

### 3.4 `rework_sources`（返工来源）

`verification_id`（原核验）、`order_id` / `order_item_id`、`found_node`（发现问题的工序）、`target_node`（返工目标工序）、`total_quantity`、`arranged_quantity`（已安排，含待执行与已核验）、`round_no`（返工次数，从 1 起）、`previous_source_id`（上一返工来源，可空）、`reason`、`version`。

唯一键 `uk_rework_sources_target (verification_id, target_node)`：同一次核验的同一目标工序只有一条来源（数量在来源内累加，避免重复来源）。  
CHECK：`arranged_quantity <= total_quantity`、`total_quantity > 0`。  
可安排余额 = `total_quantity − arranged_quantity`。

**核验不自动生成来源**（2026-09-25 实施中订正）：核验只把返工数量记入「返工待安排」额度，来源由 5.6 按**目标工序显式创建**——否则自动生成的默认来源会吃掉全部额度，使「显式选择前序工序」无额度可用。同一核验下所有来源的 `total_quantity` 合计不得超过该核验的返工数量（应用层校验）。

### 3.5 `remake_sources`（报废重做来源）

`verification_id`（原报废核验）、`order_id` / `order_item_id`、`scrap_node`（报废工序）、`start_node`（重做起始工序）、`total_quantity`、`arranged_quantity`、`reason`（`start_node = MAKING` 时必填）、`version`。

唯一键 `uk_remake_sources_target (verification_id, start_node)`：同一次核验的同一重做起始工序只有一条来源（与返工对称）。  
可安排余额 = `total_quantity − arranged_quantity`。原报废事实永久保留，重做**不恢复**原报废数量、**不增加**订单需求。

**核验不自动生成来源**（同 §3.4）：来源由 5.7 按**起始工序显式创建**，默认取报废工序；选择从 `MAKING` 开始时必须填写原因。同一核验下所有来源的 `total_quantity` 合计不得超过该核验的报废数量。

### 3.6 `overtime_preemptions`（超额预占）

`overtime_plan_id`（超额任务计划）、`future_plan_id`（未来正常计划）、`order_id` / `order_item_id`（冗余）、`node`、`preempted_quantity`、`status`（`ACTIVE` / `RELEASED`）、`released_at` / `released_by` / `release_reason`、审计列。

唯一键 `uk_overtime_preemptions_pair (overtime_plan_id, future_plan_id)`：同一超额任务对同一未来计划只有一条预占。  
CHECK：`preempted_quantity > 0`。  
不变量（应用在同一事务内加锁校验）：`Σ 某未来计划的有效预占 ≤ 该未来计划当前可选数量`，其中

```text
未来计划当前可选数量 = 该计划计划数量 − 其他有效预占合计
```

预占**不修改**未来计划原始数量、不产生工序流入或完成。

### 3.7 `production_reminders`（工作台提醒）

`reminder_type`（`INCOMPLETE` 未完成待处理 / `OVERTIME_PENDING_VERIFY` 超额待核验 / `PLAN_ADJUSTMENT` 计划待调整）、`order_id` / `order_item_id`、`node`、`plan_id`（可空）、`verification_id`（可空）、`preemption_id`（可空）、`future_plan_id`（可空，计划待调整时指向受影响的未来计划）、`quantity`、`status`（`OPEN` / `HANDLED`）、`handling_type`（`RESCHEDULED` / `PARTIAL` / `DEFERRED` / `ADJUSTED` / `NO_ADJUSTMENT`，可空）、`handled_quantity`（部分安排时记录已重新安排数量）、`reason`、`handled_by` / `handled_at`、审计列。

索引：`idx_production_reminders_type_status`、`idx_production_reminders_item`、`idx_production_reminders_plan`。  
**提醒只辅助工作台，不是数量事实来源**：所有数量以计划、核验、来源与预占为准，提醒可重建。

### 3.8 `other_schedules`（其他排班）

`id` / `schedule_no`（`OS` 编号，唯一）、`schedule_date`、`employee_id` / `employee_name`、`hours`、`minutes`（0–59）、`total_minutes`、`status`（`PENDING` / `VERIFIED` / `CANCELLED`）、`note`、取消信息、审计列。

CHECK：`minutes BETWEEN 0 AND 59`、`hours >= 0`、`total_minutes = hours * 60 + minutes`、`total_minutes > 0`。  
其他排班**只保存总分钟与工时事实**，不产生商品、库存或订单履约事实，也不占用任何待安排来源。

### 3.9 `other_schedule_verifications`（其他排班一次性工时核验）

`schedule_id` 唯一、`total_minutes`、`note`、`verified_by` / `verified_at`、审计列。每排班最多一条有效核验。

### 3.10 `other_schedule_time_corrections`（工时更正）

`verification_id`、`schedule_id`、`before_total_minutes`、`after_total_minutes`、`reason`、操作人与时间。更正**不修改**原核验，只追加更正事实；有效工时取「原核验 + 最新更正」。

### 3.11 `after_sales_production_sources`（售后生产来源，任务 8.4/8.5）

`after_sales_item_id`（售后明细，唯一来源）、`order_id` / `order_item_id`（原订单与明细，用于员工资格与商品快照）、`purpose`（`REWORK` / `REPLACEMENT`）、`node`（目标工序）、`total_quantity`、`arranged_quantity`、`reason`、审计列。

唯一键 `uk_after_sales_production_sources_target (after_sales_item_id, purpose, node)`；CHECK：`total_quantity > 0`、`arranged_quantity <= total_quantity`、`purpose IN ('REWORK','REPLACEMENT')`。外键指向 `after_sales_items` / `orders` / `order_items`。

**额度与占用**（施工口径见 `after-sales-module-design.md` §4.3）：售后返工额度 = 退回核验的返工数量；售后补发生产额度 = 补发需求 − 已补发 − 可补发。售后计划不写 `order_item_fulfillment_balances` 的计划占用列，占用只记在本表的 `arranged_quantity`；核验合格写售后台账事实（`PRODUCTION_INFLOW`），未完成与取消退回本表余额。

### 3.12 `order_item_fulfillment_balances`：不改结构，只写既有投影列

**不增删列**。阶段五在同一事务内维护以下既有列，使订单侧派生状态与履约视图保持正确：

| 列 | 阶段五写入口径 |
| --- | --- |
| `making_planned` / `packing_planned` / `seam_planned` | 该明细该工序 `status = PENDING` 的计划数量合计（排产状态用） |
| `verified_processed` | 该明细**全部工序**累计已核验的本次完成数量（生产进度用，口径不变） |
| `rework_pending` / `remake_pending` | 该明细当前可安排返工/重做余额（来源表 `total − arranged` 的汇总，展示用） |
| `making_inflow` / `packing_inflow` / `seam_inflow` / `shippable_quantity` | 经 `FulfillmentLedger.applyInflow` 按核验节点增减（阶段四已具备） |

按工序的「已核验处理」**不落投影**：由 `production_verifications` 按 `(order_item_id, node)` 汇总，供 §4.2 的待安排与可执行计算（生产模块自身的权威口径）。

## 4. 数量口径

### 4.1 工序总需求

| 工序 | 总需求 |
| --- | --- |
| 制作 `MAKING` | `Q`（`required_quantity`，随订单变更更新） |
| 捏毛装袋 `PACKING_BAG` | `Q` |
| 缝边剪袋 `SEAM_CUTTING` | `E`（确认时冻结的 `order_items.seam_quantity`） |

**不得相加**：制作、捏毛装袋、缝边剪袋是同一批需求的阶段（`domain-and-quantity-model.md` §5）。

### 4.2 待安排与当前可执行

```text
待安排数量
= 当前工序总需求
− 该工序有效待执行计划占用（production_plans 中 status = PENDING 的 quantity 合计）
− 该工序已核验处理数量（production_verifications 按 node 汇总的 completed_quantity）
```

```text
当前可执行数量
= 该工序有效流入累计
− 该工序已核验处理数量（同上，按 node 汇总核验事实）
− 已分配给其他待执行计划且当前已具备执行条件的数量
```

**有效流入的口径**：`制作`（首道工序）没有上游工序，其有效流入**取订单实际生产缺口（即订单订购数量）**（`domain-and-quantity-model.md` §6「首道制作的正常流入来自订单实际生产缺口」）；`捏毛装袋` / `缝边剪袋` 取上游合格流入与库存接入的累计（`packing_inflow` / `seam_inflow`）。因此**制作计划不会「等待上游」**，而下游工序在没有上游流入时显示等待上游（实施中订正：初版对制作也读 `making_inflow`，而该列不会被任何命令写入，导致制作计划永远无法核验）。

「当前已具备执行条件」= 该待执行计划按其自身可执行量判定为可执行（即 `可执行数量 > 0`）。计算时按计划 `plan_date, id` 升序依次扣减，保证同一明细同一工序的多个待执行计划之间不重复占用同一份可执行量。

- 创建计划**允许**使用待安排数量（即使当前可执行数量不足）→ 计划显示「等待上游」；
- **核验时必须重新计算**当前可执行数量（事务内加锁后），实际完成数量不得超过它，否则 `QUANTITY_NOT_EXECUTABLE`；
- 返工/重做计划的待安排上限来自**来源余额**（§3.4/§3.5），不走上式。

### 4.3 核验与逐工序流转

```text
本次完成数量 = 合格数量 + 返工数量 + 报废数量
未完成数量 = 计划数量 − 本次完成数量
```

合格数量按冻结流程流入下一节点（`domain-and-quantity-model.md` §5/§6）：

| 核验工序 | 合格流入 |
| --- | --- |
| `MAKING` | 全部进 `packing_inflow` |
| `PACKING_BAG` | 按冻结的缝边数量分流：缝边部分进 `seam_inflow`，其余进 `shippable_quantity` |
| `SEAM_CUTTING` | 全部进 `shippable_quantity` |

捏毛装袋分流的缝边部分：

```text
缝边分流数量 = clamp(缝边数量 − 核验前 seam_inflow 累计, 0, 本次合格数量)
不缝边分流数量 = 本次合格数量 − 缝边分流数量
```

`clamp` 下限 0 的原因：库存领用可以直接接入缝边剪袋（阶段四），`seam_inflow` 可能已由库存接入填满甚至超过缝边数量，此时捏毛装袋合格全部进可发货。

其余结果：

- **返工**：按 `found_node = 本次核验工序` 生成/累加 `rework_sources`（目标工序由创建返工计划时选定，见 §4.4），不自动进入普通计划；
- **报废**：终止原件流转，生成/累加 `remake_sources`（起始工序默认等于报废工序），**不自动**创建重做计划；
- **未完成**：按计划类型恢复对应待安排来源——正常计划回到「待安排数量」（不写额外记录，待安排本身是派生量）+ 生成 `INCOMPLETE` 提醒；返工/重做计划把未完成数量回退来源的 `arranged_quantity`。

**核验结果不可修改**，错误一律用更正事实处理（本阶段更正只覆盖其他排班工时；生产核验的更正随阶段八售后/更正流程，不在本阶段实现）。

### 4.4 返工目标矩阵

| 发现问题工序 | 允许的返工目标工序 |
| --- | --- |
| `MAKING` | `MAKING` |
| `PACKING_BAG` | `PACKING_BAG`、`MAKING` |
| `SEAM_CUTTING` | `SEAM_CUTTING`、`PACKING_BAG`、`MAKING` |

即「只能选择发现问题工序或其前序」。计划数量不得超过来源可安排余额，否则 `SOURCE_INSUFFICIENT`；目标不合法返回 `REWORK_TARGET_INVALID`。返工计划核验后若再产生返工，`round_no` 递增并记 `previous_source_id`。

### 4.5 报废重做

起始工序默认等于报废工序；选择从 `MAKING` 开始时**必须**填写原因（前序材料不可用等），否则 `REMAKE_REASON_REQUIRED`。重做是替代品生产：原报废数量**不恢复**、订单需求**不增加**、原报废事实永久保留。

### 4.6 超额任务与预占

- 只在**执行当天**创建（`plan_date = 今天`），否则 `OVERTIME_DATE_INVALID`；来源只能是**未来日期**的正常计划（不能重复选择当天正常任务）；
- 从未来正常计划选择**尚未预占**数量，逐条明确来源计划（跨订单/跨商品时每条仍带来源计划），超出返回 `OVERTIME_RESERVATION_EXCEEDED`；
- 预占不修改未来计划原始数量、不产生工序流入或完成；
- 核验后：未完成部分释放预占（`RELEASED`）；**只有合格数量**按 §4.3 正常写入履约事实；合格数量 > 0 → 对每个受影响的未来计划生成 `PLAN_ADJUSTMENT` 提醒，**建议减少数量 = 合格数量按预占顺序分摊**（绝不超过该计划上的预占量）；合格数量 = 0 → 核验前生成的 `OVERTIME_PENDING_VERIFY` 提醒自动置为 `HANDLED`（`NO_ADJUSTMENT`，原因「零合格，无需调整」），不生成计划减少建议；合格数量 > 0 时待核验提醒置为 `HANDLED`/`SUPERSEDED`（已由计划待调整提醒接管）；
- 超额任务自身的返工/报废按独立来源处理（走 §4.4/§4.5），**不作为**未来计划减少的依据，也不进入普通「未完成待处理」区域。

**调整计划的边界**（5.12）：`newQuantity` 必须 **≥ 1**——`production_plans` 有 `CHECK (quantity > 0)`，且保留一条数量为 0 的待执行计划没有业务含义、会破坏「有效计划占用」口径；若某计划确实无需生产，应改用 `POST /api/production-plans/{id}/cancel`。`production_plan_adjustments` 的 `before_quantity`/`after_quantity`/`reason` 与操作人留痕，计划占用投影按差值同步。

### 4.7 其他排班工时

```text
总分钟 = 小时 × 60 + 分钟        （分钟 0–59，总分钟 > 0）
```

一次性核验；已核验后录错用 `other_schedule_time_corrections` 追加更正（原核验不动）。不产生任何商品、库存或履约数量。

## 5. 状态与派生

- **计划状态**（`production_plans.status`）：待执行 `PENDING` / 已核验 `VERIFIED` / 已取消 `CANCELLED`；只允许 `PENDING → VERIFIED`（核验）与 `PENDING → CANCELLED`（取消，须原因）。
- **执行条件**（派生）：该工序 `当前可执行数量 > 0` → 可执行，否则等待上游。
- **排产状态 / 生产进度 / 需求处理状态 / 发货进度**：沿用阶段三的 `OrderStatuses.derive`（生产进度改用三工序已核验合计，见 §3.11）。
- **其他排班状态**：待执行 / 已核验 / 已取消。
- 所有状态由事实派生或经明确命令转换，**不提供手工下拉框直接改状态**。

## 6. API 契约（阶段五）

| 能力 | 方法与路径 | 幂等 | 成功结果 | 主要拒绝码 |
| --- | --- | --- | --- | --- |
| 计划列表/详情 | `GET /api/production-plans`、`GET /api/production-plans/{id}` | 否 | 按日期/员工/订单/工序/状态筛选；含待安排、当前可执行、等待上游与派生状态 | `AUTH_REQUIRED`, `NOT_FOUND` |
| 创建计划 | `POST /api/production-plans` | 写入幂等 | `PN` 编号计划（正常/返工/重做/超额/售后两类） | `EMPLOYEE_NOT_ELIGIBLE`, `SOURCE_INSUFFICIENT`, `REWORK_TARGET_INVALID`, `OVERTIME_DATE_INVALID`, `VALIDATION_INVALID` |
| 取消计划 | `POST /api/production-plans/{id}/cancel` | 必须幂等 | 已取消计划 + 来源恢复 | `STATE_NOT_CANCELABLE` |
| 核验 | `POST /api/production-plans/{id}/verify` | 必须幂等 | 一次性核验 + 分流事实 | `VERIFICATION_EQUATION_INVALID`, `STATE_ALREADY_VERIFIED`, `QUANTITY_NOT_EXECUTABLE` |
| 返工来源 | `GET/POST /api/rework-sources`、`POST /api/rework-sources/{id}/plans` | 写入幂等 | 来源余额 / 从来源创建计划 | `REWORK_TARGET_INVALID`, `SOURCE_INSUFFICIENT`, `CONFLICT_DUPLICATE` |
| 重做来源 | `GET/POST /api/remake-sources`、`POST /api/remake-sources/{id}/plans` | 写入幂等 | 来源余额 / 从来源创建计划 | `REMAKE_REASON_REQUIRED`, `SOURCE_INSUFFICIENT`, `CONFLICT_DUPLICATE`, `VALIDATION_INVALID` |
| 未完成待处理 | `GET /api/production-reminders/incomplete`、`POST .../{id}/reschedule`、`POST .../{id}/defer` | 写入幂等 | 提醒状态 + 已安排/余量 | `QUANTITY_INVALID`, `STATE_NOT_CANCELABLE` |
| 超额任务 | `POST /api/overtime-tasks`、`POST /api/overtime-tasks/{id}/verify` | 必须幂等 | 预占或计划调整提醒 | `OVERTIME_DATE_INVALID`, `OVERTIME_RESERVATION_EXCEEDED`, `VERIFICATION_EQUATION_INVALID` |
| 超额提醒 | `GET /api/production-reminders/overtime`、`POST .../{id}/adjust-plan`、`POST .../{id}/no-adjustment` | 写入幂等 | 调整留痕 / 提醒已处理 | `STATE_NOT_CANCELABLE`, `VALIDATION_INVALID` |
| 其他排班 | `GET/POST /api/other-schedules`、`POST /api/other-schedules/{id}/verify`、`POST /api/other-schedules/{id}/cancel`、`POST /api/other-schedules/{id}/corrections` | 写入幂等 | `OS` 编号排班 / 一次性工时核验 / 更正事实 | `VERIFICATION_EQUATION_INVALID`, `STATE_ALREADY_VERIFIED`, `STATE_NOT_CANCELABLE`, `VALIDATION_INVALID` |
| 售后生产来源 | `GET /api/after-sales/{caseId}/production-sources`、`POST .../production-sources/plans` | 写入幂等 | 来源额度 / `PN` 售后计划（`AFTER_SALES_REWORK` / `AFTER_SALES_REPLACEMENT`） | `SOURCE_INSUFFICIENT`, `VALIDATION_INVALID`, `EMPLOYEE_NOT_ELIGIBLE` |

幂等：写命令要求 `Idempotency-Key`，重复键返回首次成功结果；只读查询不要求。响应统一信封（`design.md` §2）。  
`design.md` §6 尚未登记「其他排班」行（该能力来自 `production-management` 的「记录其他排班」Scenario 与任务 5.13）——**实施时同步补 `design.md` §6 一行**，不改其余契约。

## 7. 前端页面要点（正式路由）

| 路由 | 页面 | 关键点 |
| --- | --- | --- |
| `/production` | 生产工作台 | 顶部日期/员工/订单/工序/状态紧凑筛选；一组页签：**计划**（按日期与员工分组的计划行，行内显示计划/待安排/当前可执行、可执行或等待上游 Tag、行内「调整」「取消」「核验」入口）、**等待上游**（独立筛选视图）、**未完成待处理**（独立区域，行内「重新安排」「部分安排」「暂不安排」）、**返工/重做来源**（来源余额与「创建计划」入口）、**其他排班**（工时录入与核验/更正）；超额提醒**附着在受影响的未来排班行**上并带行内「调整计划」「无需调整」；新建计划从右上角显式按钮进入弹窗；页面无预置表单 |
| `/production/plans/:id/verify` | 核验操作页 | 清晰区分「计划数量 / 当前可执行 / 等待数量」与「本次完成 / 合格 / 返工 / 报废 / 未完成」；等式不成立与超可执行时错误码定位到对应字段；已核验计划只读展示核验事实 |

视觉基线：与订单/库存页一致——全页白底 + 浅色侧栏 + 顶部面包屑 + 单组页签，使用 Ant Design 现成组件表达层级，不在同页并排 A/B 方案；`/production/plans/:id/verify` 是**显式操作状态**的独立页面（与 `/orders/:id/changes/:changeId` 同性质），只读事实不预置表单。

## 8. 模块边界、事务与锁定

- **模块归属**：`production` 顶级模块（`com.yumi.production`，`package-info.java` 已声明 `@ApplicationModule`），子包按 `plan` / `verification` / `source` / `overtime` / `reminder` / `otherschedule` 划分，内部实现放各自 `internal`。
- **依赖方向**：`production → orders`（只经 `com.yumi.orders.ledger.FulfillmentLedger` 这一 `@NamedInterface` 登记事实）与 `production → catalog`（员工资格）。**不得**出现 `orders → production` 的 Java 依赖；订单侧如需生产数据，一律走只读 SQL 引用类（同阶段四 `InventoryPlanReference` 的做法）。
- **跨模块只读引用**：`production` 侧新增 `OrderProductionReference`（只读 `order_items` / `order_item_fulfillment_balances` / 冻结 `E`），不复制订单业务规则；`orders` 侧如需展示生产进度，只读 `production_plans` / `production_verifications`。
- **员工资格**：创建计划时校验员工在职且具备对应工种（`MAKING`/`PACKING_BAG`/`SEAM_CUTTING`；其他排班不校验工种但校验在职）。资格由 `catalog` 的员工应用服务判定，`production` 不复制资格规则；不合格返回 `EMPLOYEE_NOT_ELIGIBLE`。
- **事务拥有者**：计划创建、调整、取消、核验、来源创建/从来源创建计划、未完成提醒处理、超额创建/核验/提醒处理、其他排班核验/更正，均由 `production` 的应用服务在一个 `@Transactional` 内完成；跨模块写入（`FulfillmentLedger` 登记事实 + 投影）在同一事务内。
- **锁定顺序（防死锁）**：计划行 → `order_item_fulfillment_balances` → `rework_sources` / `remake_sources` → `overtime_preemptions`。计划创建没有计划行可锁，只锁履约余额。**售后计划**（8.4/8.5）的顺序为计划行 → `after_sales_items` → `after_sales_production_sources`，且不锁履约余额（售后数量不进订单侧）。所有竞争资源的读取都必须用**锁定读**（`FOR UPDATE`）：MySQL REPEATABLE READ 下普通 SELECT 走事务首次读建立的快照，并发事务会看不到对方刚提交的行而双双通过校验（5.16 并发测试实测复现并修复：超额预占、待安排、可执行上限三处）。
- **幂等**：写命令要求 `Idempotency-Key`；核验、取消、超额创建/核验为「必须幂等」。

## 9. 不变量与错误码

1. 各工序数量不相加为订单数量（§4.1）；
2. 同一来源数量不能被两个计划或两个订单重复消费（来源余额 `total − arranged` 加锁校验）；
3. 库存领用与发货不能重复扣库存（阶段四/六，本阶段只登记生产合格事实）；
4. 每计划最多一次有效核验（`uk_production_verifications_plan`）；
5. 核验等式成立且不超过事务内重算的当前可执行数量；
6. 报废不自动减少客户需求，重做不增加订单需求（§4.5）；
7. 返工/重做不增加订单数量；
8. 已确认事实不通过物理删除修正（计划取消只改状态 + 恢复来源，核验/来源/更正事实永久保留）；
9. 未完成数量必须按计划类型回到对应待安排来源（§4.3）；
10. 超额预占不修改未来计划原始数量、不产生流入或完成（§4.6）；
11. 其他排班不产生商品、库存或订单履约事实（§3.8）；
12. 所有汇总均可从来源事实重建（提醒、待安排、来源余额）。

错误码：沿用 `design.md` §3 分层，本阶段涉及 `VALIDATION_INVALID`、`NOT_FOUND`、`CONFLICT_VERSION`、`STATE_ALREADY_VERIFIED`、`STATE_NOT_CANCELABLE`、`STATE_NOT_EDITABLE`、`QUANTITY_INVALID`、`QUANTITY_NOT_EXECUTABLE`、`VERIFICATION_EQUATION_INVALID`、`REWORK_TARGET_INVALID`、`REMAKE_REASON_REQUIRED`、`SOURCE_INSUFFICIENT`、`EMPLOYEE_NOT_ELIGIBLE`、`OVERTIME_DATE_INVALID`、`OVERTIME_RESERVATION_EXCEEDED`；**不新增错误码**（全部已在 `ErrorCode` 中登记）。

## 10. 不做项

- 不做生产核验的更正/冲销（随阶段八售后与更正流程）；
- 不做返工/重做来源的更正或作废（只支持取消其待执行计划以恢复余额）；
- 不做其他排班的多员工/多工序排班与工资结算（工资延期，本阶段只落工时事实）；
- 不做超额任务的历史追溯性调整（预占只按创建当时校验，后续未来计划被改动按提醒人工处理）；
- 不做 `/production` 的甘特图、产能规划或自动排产（本阶段是人工排班 + 事实登记）；
- 不新增计算模块公式（本阶段无金额公式；工时是整数分钟，不涉及 `DecimalPolicy`）。

## 11. 待确认项（2026-09-25 待评审）

1. ~~`verified_processed` 拆分~~ **已定稿（2026-09-25，实施中发现更优解）**：**不改既有表结构**，按工序的已核验处理由 `production_verifications` 按 node 汇总（§3.11）。原提议的「拆成三个分项列」被否决——按事实汇总无需改阶段三/四代码，且天然可重建。
2. **返工来源的粒度**（§3.4）：按 `(verification_id, target_node)` 唯一。`database-design.md` §8 已写明来源含「目标工序」，故为**规格规定**，非开放项。
3. **重做来源的起始工序**（§3.5）：写在来源上（一次核验一条来源）。`database-design.md` §8 已写明来源含「重做起始工序」，故为**规格规定**，非开放项。
4. **超额任务形态**（§4.6）：一次任务一个工序 + 多条未来计划来源。`database-design.md` §8 的 `overtime_preemptions` 为「超额计划 + 未来正常计划 + 预占数量」，即一计划对多未来计划，故为**规格规定**，非开放项。
5. **售后两类计划类型**（§1）：阶段五只保留枚举与资格校验入口，创建请求在缺售后来源时返回 `VALIDATION_INVALID`，实际创建随阶段八接入。**待确认**（阶段边界）。
6. **其他排班是否校验工种**：推荐**在职即可**（其他排班不绑定工序，工种校验无意义）。**待确认**（影响 5.13 的一条校验）。

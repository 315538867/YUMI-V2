# 售后模块施工文档（阶段八：售后受理、退回核验、补发与售后退款）

日期：2026-09-25  
修改人：chen  
状态：**已评审（按阶段五/六/七同口径直接实施）**；按 `openspec/changes/build-yumi-v2-order-fulfillment/tasks.md` 的 8.1–8.12 逐条实施，逐项证据回填该文件。  
上游依据：`specs/order-lifecycle/spec.md`（售后必须独立于原订单履约）、`design.md` §6（售后与售后补发行）、§7.1（售后来源和结清口径）、`docs/architecture/database-design.md` §11、`domain-and-quantity-model.md` §12。

## 1. 范围

**本阶段做**：售后 5 张事实表 + 更正记录（V13）、售后编号 `AS`、售后受理（来源必须是**已确认且有效的原发货批次明细**）、退回核验（退回 = 返工 + 报废）、售后库存领用与售后生产合格进入**可补发**、补发发货确认（增加已补发）、售后退款（单列累计实际净收）、售后更正记录、订单详情「发货与售后」Tab 的售后区域，以及 8.11/8.12 的测试与人工验收。

**本阶段不做**：售后补差价、补应收或补收款（首期不支持，施工文档 §10）；把售后合格品自动转为通用库存（必须显式新建库存批次）；顶级售后工作区（售后一律在订单上下文的 Tab 内处理）。**不建空实现、不建占位服务。**

**不改动既有表结构**；售后**不回写**原订单的订购数量、累计发货、未交付需求、应收或主状态。

## 2. 编号

| 空间 | 前缀 | 宽度 | 示例 | `number_sequences.sequence_key` |
| --- | --- | --- | --- | --- |
| 售后单 | `AS` | 6 | `AS000001` | `after_sales_cases` |

## 3. 表结构

数量为 `INT UNSIGNED`；金额 `DECIMAL(19,4)`；表带 `version`、`created_at`、`updated_at`、`created_by`、`updated_by`、`request_id`、`idempotency_key`。表归 `orders` 模块。

### 3.1 `after_sales_cases`（售后单）

`id` / `case_no`（`AS`，唯一）、`order_id`、`case_type`（`REWORK` / `REPLACEMENT` / `REWORK_AND_REPLACEMENT`）、`status`（`OPEN` / `COMPLETED` / `CANCELLED`）、`problem`（问题描述，必填）、`solution`（处理方案）、`note`、`closed_at` / `closed_by`、审计列。

CHECK：`case_type IN (...)`、`status IN (...)`。索引：`uk_after_sales_cases_case_no`、`idx_after_sales_cases_order (order_id, status)`。

### 3.2 `after_sales_items`（售后明细）

`case_id`、`order_id`、`order_item_id`（原订单明细）、**`shipment_item_id`（必须引用已确认且有效的原发货批次明细）**、商品与缝边快照（`product_no` / `product_name` / `seam_quantity`）、`accepted_quantity`（受理数量）、`returned_quantity`（客户退回数量）、`replacement_required_quantity`（补发需求数量）、`return_verification_id`（退回核验，可空）、审计列。

唯一键 `uk_after_sales_items_shipment_item (shipment_item_id)`：**同一发货批次明细只允许一个有效售后占用**（来源唯一，防止并发超量）。  
CHECK：`accepted_quantity > 0`、`accepted_quantity <= returned_quantity + replacement_required_quantity` 由应用校验（受理量是来源上限，退回与补发需求是其分解）。

**剩余可受理量** = 该发货批次明细的 `quantity`（本次已发数量） − 其他售后占用。受理时事务内锁定该发货明细行并重算。

### 3.3 `after_sales_return_verifications`（退回核验）

`after_sales_item_id` 唯一、`returned_quantity`、`rework_quantity`、`scrap_quantity`、`reason`、`verified_by` / `verified_at`、审计列。

CHECK：`returned_quantity = rework_quantity + scrap_quantity`（退回等式，数据库级）。

### 3.4 `after_sales_fulfillment_entries`（售后可补发/已补发台账，不可变）

`after_sales_item_id`、`entry_type`（`INVENTORY_INFLOW` 库存接入 / `REWORK_QUALIFIED` 售后返工合格 / `PRODUCTION_QUALIFIED` 售后生产合格 / `REPLACEMENT_CONSUME` 补发发货消耗 / `REVERSAL` 冲销）、`direction`（`IN` / `OUT`）、`quantity`、`source_type` / `source_id` / `source_line_id`、`business_date`、操作人、备注、审计列。

唯一键 `uk_after_sales_entries_source (source_type, source_id, source_line_id, entry_type, direction)`：每笔来源只接入一次。  
```text
售后可补发数量 = 库存接入 + 售后返工合格 + 售后生产合格 − 已确认补发发货数量
售后待补发数量 = 补发需求数量 − 已补发数量
```

### 3.5 `after_sales_shipment_links`（补发发货关联）

`after_sales_item_id`、`shipment_id`、`shipment_item_id`、`quantity`、审计列。

唯一键 `uk_after_sales_shipment_links_item (after_sales_item_id, shipment_item_id)`。**补发批次确认后才增加已补发数量**。

### 3.6 `after_sales_corrections`（售后更正记录）

`target_type`（`RETURN_VERIFICATION` / `ITEM_QUANTITY`）、`target_id`、`before_value` / `after_value`（数量或文本）、`reason`（必填）、操作人与时间。**不覆盖原事实**，保留原值与来源链。

### 3.7 `after_sales_production_sources`（售后生产来源，任务 8.4/8.5）

`after_sales_item_id`、`order_id`、`order_item_id`、`purpose`（`REWORK` / `REPLACEMENT`）、`node`、`total_quantity`、`arranged_quantity`、`reason`、审计列。

唯一键 `uk_after_sales_production_sources_target (after_sales_item_id, purpose, node)`；CHECK：`total_quantity > 0`、`arranged_quantity <= total_quantity`、`purpose` 枚举。表归**生产模块**（生产计划的来源额度），只引用售后明细作为来源。

## 4. 口径

### 4.1 受理来源与上限（任务 8.2）

- 售后**必须**引用**已确认且有效的原发货批次明细**：发货草稿、已作废批次、未发商品一律 `AFTER_SALES_SOURCE_INVALID`；
- 受理数量不得超过**剩余可受理量**（该发货明细本次数量 − 其他售后占用）→ 否则 `AFTER_SALES_QUANTITY_EXCEEDED`；
- 受理时事务内锁定该发货明细行，并发受理不会超量（来源唯一键兜底）；
- 订单**部分发货但未关闭**即可创建售后，剩余订单需求可继续生产与发货。

### 4.2 退回核验（任务 8.3）

```text
客户退回数量 = 售后返工数量 + 售后报废数量
```

等式不成立 → `AFTER_SALES_EQUATION_INVALID`。**退回不自动入库、不恢复原发货库存**（原发货只消耗订单可发货投影，库存已在领用时扣减）。

### 4.3 补发来源（任务 8.4–8.6）

- **售后返工**：复用生产计划的返工来源与一次性核验（`plan_type = AFTER_SALES_REWORK`），最终合格按售后用途进入**可补发**；
- **售后生产**：库存不足部分创建 `plan_type = AFTER_SALES_REPLACEMENT` 的生产计划，合格进入**可补发**；
- **库存领用**：兼容批次扣库存后只增加**售后可补发**（扣一次库存，不二扣）；
- 以上三者**都不得直接增加已补发**；把售后合格品转为通用库存必须**显式新建库存批次**（不自动）。

来源额度（任务 8.4/8.5，`after_sales_production_sources`）：

```
售后返工额度 = 退回核验的返工数量 − 已安排
售后生产额度 = 补发需求 − 已补发 − 可补发 − 已安排
```

- 未核验退回（返工数量为 0）或补发需求已被已补发与可补发覆盖时**不允许排产** → `SOURCE_INSUFFICIENT`；
- 售后计划**不登记订单侧计划占用**（售后数量不属于订单工序需求），占用只记在来源行的 `arranged_quantity`；
- 核验合格写入售后台账事实 `PRODUCTION_INFLOW` / `IN`（`source_type = PRODUCTION`、`source_id = planId`），**不写订单履约事实、不推进订单工序流入**；
- 未完成数量退回来源余额（可重新排产），不产生订单侧待安排与未完成提醒；取消计划同样退回来源余额。

### 4.4 补发发货（任务 8.7）

`本次补发 ≤ 当前可补发` 且 `已补发 + 本次 ≤ 补发需求` → 否则 `AFTER_SALES_REPLACEMENT_INSUFFICIENT`。确认后写 `REPLACEMENT_CONSUME`/`OUT` 事实、增加售后已补发、写补发发货关联；**原订单订购数量、累计发货、未交付需求、应收与主状态全部不变**。

### 4.5 售后退款（任务 8.8）

退款 `source_type = AFTER_SALES` + 售后单 id：进入**累计实际净收**，**不冲减订单结清净额、不产生新的原订单待收/待退**；订单无论仍在履约还是已关闭都保持主状态（阶段七已实现该口径，本阶段补上「售后单存在性」校验）。首期不支持售后补差价、补应收或补收款。

### 4.6 更正（任务 8.9）

退回核验等不可覆盖事实出错时，用 `after_sales_corrections` 追加更正事实（保留原值、原因与来源链），**不修改原核验**。

## 5. 状态与派生

- **售后单状态**：`OPEN` → `COMPLETED`（补发需求全部补发或无需补发时关闭）/ `CANCELLED`（未产生任何退回核验或补发事实时取消）；状态由事实派生 + 明确命令转换。
- **售后占用**：由 `after_sales_items` 派生（每个发货批次明细一个有效占用）。
- 售后**不影响**原订单的派生状态（发货进度、需求处理状态等一律只看原订单事实）。

## 6. API 契约（阶段八）

| 能力 | 方法与路径 | 幂等 | 成功结果 | 主要拒绝码 |
| --- | --- | --- | --- | --- |
| 售后列表/详情 | `GET /api/orders/{id}/after-sales`、`GET /api/after-sales/{caseId}` | 否 | 售后单 + 明细（含占用与可补发/已补发） | `ORDER_NOT_FOUND`, `NOT_FOUND` |
| 创建售后 | `POST /api/orders/{id}/after-sales` | 写入幂等 | `AS` 编号售后单 + 明细与占用 | `AFTER_SALES_SOURCE_INVALID`, `AFTER_SALES_QUANTITY_EXCEEDED`, `VALIDATION_INVALID` |
| 退回核验 | `POST /api/after-sales/{caseId}/verify-return` | 必须幂等 | 一次性核验（退回 = 返工 + 报废） | `AFTER_SALES_EQUATION_INVALID`, `STATE_ALREADY_VERIFIED` |
| 售后生产来源 | `GET /api/after-sales/{caseId}/production-sources` | 否 | 来源额度（总额度/已安排/余额） | `NOT_FOUND` |
| 售后生产计划 | `POST /api/after-sales/{caseId}/production-sources/plans` | 写入幂等 | `PN` 售后计划（`AFTER_SALES_REWORK` / `AFTER_SALES_REPLACEMENT`） | `SOURCE_INSUFFICIENT`, `VALIDATION_INVALID`, `EMPLOYEE_NOT_ELIGIBLE` |
| 补发发货 | `POST /api/after-sales/{caseId}/replacement-shipments`、`POST .../confirm` | 必须幂等 | 补发批次 + 已补发增加 | `AFTER_SALES_REPLACEMENT_INSUFFICIENT` |
| 售后退款 | `POST /api/orders/{id}/refunds`（`sourceType=AFTER_SALES`） | 写入幂等 | 单列售后退款 + 累计实际净收 | `REFUND_EXCEEDS_RECEIPTS`, `REFUND_REFERENCE_REQUIRED` |
| 售后更正 | `POST /api/after-sales/{caseId}/corrections` | 写入幂等 | 更正事实（保留原值） | `VALIDATION_INVALID`, `NOT_FOUND` |

幂等：写命令要求 `Idempotency-Key`。**不新增错误码**（全部已在 `ErrorCode` 登记）。

## 7. 前端页面要点

| 路由 | 位置 | 关键点 |
| --- | --- | --- |
| `/orders/:id` | 「发货与售后」Tab 的售后区域 | 按售后单列出：来源批次明细、受理/退回/补发需求、退回核验（返工/报废）、库存/生产补发来源、**可补发/已补发**、补发发货、退款与更正历史；**查看只展示事实**，创建售后、退回核验、补发发货、退款、更正从显式按钮进入订单上下文操作；不提供顶级售后工作区；草稿批次不提供售后入口 |

## 8. 模块边界、事务与锁定

- **模块归属**：`orders`（`com.yumi.orders.aftersales`），与 `orders` 同模块。
- **事务拥有者**：创建售后、退回核验、补发发货确认、更正各自一个 `@Transactional`。
- **锁定顺序**：`shipment_items`（原发货明细，按 id 升序 `FOR UPDATE`）→ `after_sales_items` → `after_sales_fulfillment_entries`；竞争资源的读取一律用**锁定读**（REPEATABLE READ 快照陷阱同阶段五–七）。售后生产来源（8.4/8.5）的锁定顺序为 `after_sales_items` → `after_sales_production_sources`。
- **幂等**：退回核验与补发确认为「必须幂等」。

## 9. 不变量与错误码

1. 售后来源必须是已确认且有效的发货批次明细；同一发货明细只允许一个有效售后占用；
2. 受理数量不超过剩余可受理量；退回 = 返工 + 报废；
3. 退回不自动入库、不恢复原发货库存；报废不入库、不恢复库存、不计已补发；
4. 补发来源（库存/返工/生产）只增加可补发，**不得直接增加已补发**；
5. 补发确认后才增加已补发；补发不得超过可补发与补发需求；
6. **售后不改变原订单**的订购数量、累计发货、未交付需求、应收与主状态；
7. 售后退款只进累计实际净收，不冲减结清净额、不产生原订单新待收/待退；
8. 不可覆盖事实用更正事实处理，保留原值与来源链；
9. 所有汇总（可补发、已补发、待补发）均可从售后台账重建。

## 10. 不做项

- 不做售后补差价、补应收或补收款（首期不支持）；
- 不把售后合格品自动转为通用库存（必须显式新建库存批次）；
- 不做顶级售后工作区（一律在订单上下文处理）；
- 不做售后单的部分取消（整单取消；已产生事实的售后只能更正或继续完成）；
- 不做售后原因/方案的静态数据目录（自由文本 + 快照）。

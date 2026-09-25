# 发货模块施工文档（阶段六：发货草稿、确认、物流修改、作废与等量更正）

日期：2026-09-25  
修改人：chen  
状态：**已评审（按阶段五同口径直接实施）**；按 `openspec/changes/build-yumi-v2-order-fulfillment/tasks.md` 的 6.1–6.10 逐条实施，逐项证据回填该文件。  
上游依据：`specs/order-lifecycle/spec.md`（发货上限、草稿与物流分离、关闭与更正）、`docs/architecture/domain-and-quantity-model.md` §9（可发货与发货）、`docs/architecture/database-design.md` §9（发货表）、`design.md` §4/§5/§6/§7。

## 1. 范围

**本阶段做**：5 张发货表（V11）、发货编号 `SH`、发货草稿创建/编辑/查询、确认（含上限校验与快照）、物流修改（只改物流四字段并留痕）、未关闭订单作废、已关闭订单等量更正、订单详情「发货与售后」Tab 的发货区域，以及 6.9/6.10 的测试与人工验收。

**本阶段不做**：售后（阶段八，本阶段作废/更正的「无有效售后占用」校验在阶段八接入售后表后生效——当前无售后事实，条件恒真，见 §4.4）、收退款与关闭（阶段七）、报表与打印导出（阶段九，本阶段只在前端提供打印入口占位说明）。**不建空实现、不建占位服务。**

**不改动既有表结构**：只**写入**既有投影列 `shipped_quantity`（累计有效发货）与 `shippable_quantity`（可发货），不增删列。

## 2. 编号

| 空间 | 前缀 | 宽度 | 示例 | `number_sequences.sequence_key` |
| --- | --- | --- | --- | --- |
| 发货批次 | `SH` | 6 | `SH000001` | `shipments` |

## 3. 表结构

数量为 `INT UNSIGNED`；所有表带 `version`、`created_at`、`updated_at`、`created_by`、`updated_by`、`request_id`、`idempotency_key`。表归 `orders` 模块（`database-design.md` §9）。

### 3.1 `shipments`（发货批次）

`id` / `shipment_no`（`SH`，唯一）、`order_id`、`status`（`DRAFT` / `CONFIRMED` / `VOIDED`）、`shipment_date`、原始物流快照四列（`carrier` / `tracking_no` / `freight` `DECIMAL(19,4)` / `logistics_note`）、当前有效物流四列（`current_carrier` / `current_tracking_no` / `current_freight` / `current_logistics_note`）、`note`、`confirmed_at` / `confirmed_by`、`voided_at` / `voided_by` / `void_reason`、`replaces_shipment_id`（更正时指向被替代批次，可空）、审计列。

索引：`uk_shipments_shipment_no`、`idx_shipments_order`、`idx_shipments_status`、`idx_shipments_date`。  
CHECK：`status IN ('DRAFT','CONFIRMED','VOIDED')`、`freight >= 0`。

**草稿不占用任何数量**：`DRAFT` 不消耗可发货、不增加累计发货、不写履约事实；只有确认才产生事实。

### 3.2 `shipment_items`（发货明细）

`shipment_id`、`order_item_id`、`line_no`、`quantity`、商品与收货展示快照（`product_no` / `product_name` / `recipient_name` / `recipient_phone` / `region` / `address`）、确认时的 `cumulative_shipped_quantity`（本次之后的累计有效发货）与 `undelivered_quantity`（本次之后的未交付需求）快照、审计列。

唯一键 `uk_shipment_items_shipment_item (shipment_id, order_item_id)`。  
CHECK：`quantity > 0`。

### 3.3 `shipment_source_links`（发货来源追溯）

`shipment_item_id`、`source_type`（`INVENTORY_ALLOCATION` / `PRODUCTION_QUALIFIED` / `FINISHED_SURPLUS`）、`source_id` / `source_line_id`、`quantity`、审计列。

**只追溯来源，不再次改变库存**（`domain-and-quantity-model.md` §9）：库存已在领用时扣减，发货只消耗订单可发货投影。唯一键 `uk_shipment_source_links_source (shipment_item_id, source_type, source_id, source_line_id)`。

### 3.4 `shipment_logistics_changes`（物流修改历史）

`shipment_id`、`before_carrier` / `after_carrier`、`before_tracking_no` / `after_tracking_no`、`before_freight` / `after_freight`、`before_note` / `after_note`、`reason`（必填）、操作人与时间。

**只能改物流四字段**：不得修改订单、商品、数量、发货日期和来源关系；累计发货与履约数量不变。

### 3.5 `shipment_corrections`（等量更正关系）

`original_shipment_id`（唯一）、`replacement_shipment_id`、`reason`、操作人与时间。

唯一键 `uk_shipment_corrections_original (original_shipment_id)`：一个原批次最多一次等量更正；同一事务内使原批次失效并创建等量替代，**交付数量不得下降**。

## 4. 口径

### 4.1 可发货与发货上限

```text
当前可发货数量 = 可发货流入累计 − 有效发货消耗累计 − 已转成品余量数量
确认发货：本次发货 <= 当前可发货数量
          累计有效发货 + 本次发货 <= 当前有效订购数量
```

任一明细失败**整批不生效**（同一事务回滚）。可发货流入来自：不缝边捏毛装袋合格、缝边剪袋合格、兼容最终阶段库存接入；消耗来自发货确认（`SHIPMENT_CONSUME`/`OUT`）与作废恢复（`SHIPMENT_VOID`/`IN`）。

### 4.2 确认的原子写入

事务内以**订单明细 id 升序**锁定履约投影行，随后：①校验订单为 `CONFIRMED`；②逐明细校验可发货上限与当前有效需求上限；③写确认快照（`shipment_items` 的展示与累计/未交付快照）；④逐明细写 `SHIPMENT_CONSUME` 履约事实并同步投影（`shippable_quantity` 减少、`shipped_quantity` 增加）；⑤写来源追溯（`shipment_source_links`）；⑥批次置 `CONFIRMED`。**不生成任何库存流水**。

### 4.3 物流修改

已确认批次可改物流公司、单号、运费、备注；必须填写原因，保留修改前后值；`shipments` 的当前有效物流值随更新，原始物流快照不变。

### 4.4 作废

仅 `CONFIRMED` 且订单未关闭时可作废，必须填写原因：①校验原批次明细**无有效售后占用**（阶段八接入售后表后生效；当前无售后事实，条件恒真）；②写反向履约事实（`SHIPMENT_VOID`/`IN`）恢复可发货与累计发货；③批次置 `VOIDED`；④**原确认快照保留**，作废批次不可再次确认；⑤**不恢复原库存**（需要恢复库存须另行满足取消领用条件并生成反向库存流水）。

### 4.5 等量更正（已关闭订单）

订单已关闭时不得作废，只能更正：同一事务内使原批次失效并**创建等量替代批次**（数量与明细一一对应），写 `shipment_corrections` 关系；交付数量不得下降。若当前可发货不足以立即创建替代批次 → `CORRECTION_REPLACEMENT_REQUIRED` 并引导走售后（阶段八）。一个原批次最多一次等量更正（`uk_shipment_corrections_original`），重复更正返回 `CONFLICT_DUPLICATE`。

## 5. 状态与派生

- **批次状态**：草稿 `DRAFT` → 已确认 `CONFIRMED` → 已作废 `VOIDED`；`DRAFT → CONFIRMED`（确认）、`CONFIRMED → VOIDED`（作废）。作废批次不可再次确认；更正产生的新批次同样是 `CONFIRMED`，原批次为 `VOIDED` 并带 `replaces_shipment_id` 反向关系。
- **订单发货进度**（沿用阶段三 `OrderStatuses.derive`）：`累计有效发货 = 0` → 未发货；`0 < 累计 < 当前有效需求` → 部分发货；`累计 >= 当前有效需求` → 全部发货。作废恢复后进度随之回退。
- 状态由事实派生或经明确命令转换，**不提供手工下拉框直接改状态**。

## 6. API 契约（阶段六）

| 能力 | 方法与路径 | 幂等 | 成功结果 | 主要拒绝码 |
| --- | --- | --- | --- | --- |
| 发货列表/详情 | `GET /api/orders/{id}/shipments` | 否 | 批次（草稿/已确认/已作废）+ 明细 + 来源追溯 | `ORDER_NOT_FOUND` |
| 新建/编辑草稿 | `POST /api/orders/{id}/shipments`、`PATCH /api/orders/{id}/shipments/{shipmentId}` | 写入幂等 | 草稿（不影响可发货/累计发货） | `STATE_NOT_EDITABLE`, `QUANTITY_INVALID` |
| 确认 | `POST /api/orders/{id}/shipments/{shipmentId}/confirm` | 必须幂等 | 冻结快照 + 累计发货 | `SHIPMENT_EXCEEDS_AVAILABLE`, `SHIPMENT_EXCEEDS_DEMAND`, `STATE_NOT_CONFIRMABLE` |
| 物流修改 | `PATCH /api/orders/{id}/shipments/{shipmentId}/logistics` | 写入幂等 | 修改历史 + 当前物流值 | `STATE_NOT_EDITABLE`, `VALIDATION_INVALID`（缺原因） |
| 作废 | `POST /api/orders/{id}/shipments/{shipmentId}/void` | 必须幂等 | 反向事实 + 已作废批次 | `STATE_CLOSED_REQUIRES_CORRECTION`, `SHIPMENT_AFTER_SALES_LINKED`, `STATE_NOT_CANCELABLE` |
| 更正 | `POST /api/orders/{id}/shipments/{shipmentId}/corrections` | 必须幂等 | 等量替代批次 + 更正关系 | `CORRECTION_REPLACEMENT_REQUIRED`, `SHIPMENT_AFTER_SALES_LINKED`, `STATE_NOT_EDITABLE`（未关闭/非已确认）、`CONFLICT_DUPLICATE`（已更正过） |

幂等：写命令要求 `Idempotency-Key`。**不新增错误码**（全部已在 `ErrorCode` 登记）。

## 7. 前端页面要点

| 路由 | 位置 | 关键点 |
| --- | --- | --- |
| `/orders/:id` | 「发货与售后」Tab 的发货区域 | 按批次列出草稿/已确认/已作废，展开显示明细、来源追溯与物流修改历史；**查看只展示事实**，新建/编辑草稿、确认、物流修改、作废、更正从显式按钮进入订单上下文的独立操作界面；不建立 `/shipments` 顶级工作区；不允许在只读区域编辑已确认业务数量；提供打印/PDF 入口 |

## 8. 模块边界、事务与锁定

- **模块归属**：`orders`（`com.yumi.orders.shipment`），与 `orders` 同模块，因此可直接使用 `FulfillmentRepository` 与 `OrderRepository`，无需跨模块接口。
- **事务拥有者**：草稿写入、确认、物流修改、作废、更正各自一个 `@Transactional`。
- **锁定顺序**：`order_item_fulfillment_balances`（按 `order_item_id` **升序**）→ `shipments`（`FOR UPDATE`）。所有竞争资源的读取用**锁定读**（REPLACEABLE READ 快照陷阱同阶段五 §8）。
- **幂等**：确认、作废、更正为「必须幂等」。

## 9. 不变量与错误码

1. 发货不超过当前可发货，累计有效发货不超过当前有效订购（§4.1）；
2. 发货**不得再次扣减原库存**（§3.3）；
3. 草稿不产生任何事实（§3.1）；
4. 已确认发货的业务数量、日期与来源关系不可修改（物流四字段除外）；
5. 已作废批次不可再次确认，原确认快照保留（§4.4）；
6. 已关闭订单只能等量更正，交付数量不得下降（§4.5）；
7. 已确认事实不通过物理删除修正（一律用反向事实或更正关系）；
8. 所有汇总（累计发货、可发货、未交付）均可从来源事实重建。

## 10. 不做项

- 不做售后台账与售后补发（阶段八）；本阶段作废/更正的「无有效售后占用」校验在阶段八接入；
- 不做收退款与订单关闭（阶段七）；
- 不做打印/PDF 的服务端生成（阶段九；本阶段前端提供入口说明）；
- 不做发货批次的部分作废（整批作废；部分更正通过等量替代批次表达）；
- 不做物流公司目录（自由文本，随批次快照）。

# 收退款与关闭模块施工文档（阶段七：不可变资金事实、结清口径与订单关闭）

日期：2026-09-25  
修改人：chen  
状态：**已评审（按阶段五/六同口径直接实施）**；按 `openspec/changes/build-yumi-v2-order-fulfillment/tasks.md` 的 7.1–7.9 逐条实施，逐项证据回填该文件。  
上游依据：`specs/order-lifecycle/spec.md`（收款与退款不可变、订单关闭条件）、`design.md` §6（收退款与关闭行）、§7.1（售后来源与结清口径）、`docs/architecture/database-design.md` §10（收退款表）。

## 1. 范围

**本阶段做**：`payments` / `refunds` 两张不可变事实表 + 订单款项投影（V12）、收退款编号 `PA`/`RF`、收款与退款命令、服务端结清公式与收款状态派生、订单关闭（三重校验）、终态互斥与关闭后约束、订单详情「资金与利润」Tab，以及 7.8/7.9 的测试与人工验收。

**本阶段不做**：售后台账本身与售后补发（阶段八——**售后退款**在本阶段即可登记（关联售后单、不冲减结清净额），但「售后单是否存在」的校验随阶段八的售后表接入后生效）、报表与导出（阶段九）、发票与账期（不在首期）。**不建空实现、不建占位服务。**

**不改动既有表结构**：订单主表 `orders` 的金额列沿用；关闭只写既有的 `closed_at` / `closed_by` 与主状态。

## 2. 编号

| 空间 | 前缀 | 宽度 | 示例 | `number_sequences.sequence_key` |
| --- | --- | --- | --- | --- |
| 收款 | `PA` | 6 | `PA000001` | `payments` |
| 退款 | `RF` | 6 | `RF000001` | `refunds` |

## 3. 表结构

金额一律 `DECIMAL(19,4)`（非负）；表带 `version`、`created_at`、`updated_at`、`created_by`、`updated_by`、`request_id`、`idempotency_key`。表归 `orders` 模块。

### 3.1 `payments`（收款事实，不可变）

`id` / `payment_no`（`PA`，唯一）、`order_id`、`amount`、`business_date`、`method`（自由文本：现金/微信/银行转账等）、`note`、`operator_username`、审计列。

CHECK：`amount > 0`。索引：`uk_payments_payment_no`、`idx_payments_order (order_id, business_date)`。  
**只追加，不提供编辑/删除**；更正只能新增反向事实（阶段九的更正流程）或退款。

### 3.2 `refunds`（退款事实，不可变）

`id` / `refund_no`（`RF`，唯一）、`order_id`、`amount`、`business_date`、`method`、`reason`（必填）、`note`、`source_type`（`ORDER_CHANGE` / `AFTER_SALES`）、`source_id`（变更单 id 或售后单 id）、`operator_username`、审计列。

CHECK：`amount > 0`、`source_type IN ('ORDER_CHANGE','AFTER_SALES')`。索引：`uk_refunds_refund_no`、`idx_refunds_order (order_id, business_date)`、`idx_refunds_source (source_type, source_id)`。  
**退款必须关联来源**（`REFUND_REFERENCE_REQUIRED`）；累计退款不得超过累计收款（`REFUND_EXCEEDS_RECEIPTS`）。

### 3.3 `order_settlement_balances`（订单款项投影）

`order_id` 唯一、`paid_amount`（累计订单收款）、`change_refund_amount`（累计订单变更退款）、`after_sales_refund_amount`（累计售后退款）、`net_settled_amount`（订单结清净额）、`effective_receivable_amount`（当前有效应收）、`refund_pending_amount`（订单待退款）、审计列。

由领域服务在写收退款事实的同一事务内更新；**所有汇总均可从 `payments`/`refunds` 按来源类型重建**（阶段九提供重建校验）。

## 4. 结清口径（任务 7.4）

```text
订单结清净额   = 累计订单收款 − 累计订单变更退款
订单待退款     = max(累计订单收款 − 当前有效应收 − 累计订单变更退款, 0)
累计实际净收   = 订单结清净额 − 累计售后退款
收款状态       = 结清净额 = 0 → 未收款；0 < 结清净额 < 当前有效应收 → 部分收款；
                 结清净额 ≥ 当前有效应收 且 待退款 = 0 → 已结清；待退款 > 0 → 待退款
```

**售后退款单列**：只进入累计实际净收，**不冲减订单结清净额**、不产生新的原订单待收/待退，也不改变履约需求与主状态（`design.md` §7.1）。

## 5. 命令口径

### 5.1 收款（任务 7.2）

只允许 `CONFIRMED` 订单登记（草稿返回 `PAYMENT_DRAFT_FORBIDDEN`；已取消/已关闭返回 `STATE_NOT_EDITABLE`——关闭后禁止新增原订单收款，见 7.6）。金额必须大于 0；记录金额、业务日期、方式、备注与操作人。

### 5.2 退款（任务 7.3）

`amount > 0`、`reason` 必填、`sourceType` + `sourceId` 必填；**累计退款（含售后退款）不得超过累计收款**；`ORDER_CHANGE` 退款须关联该订单的变更单（校验归属）；`AFTER_SALES` 退款在订单仍 `CONFIRMED` 时也可登记，且**不改变订单结清净额、待收、待退与剩余履约需求**。已取消/已关闭订单只允许**有依据的实际退款补录**（同样要求来源）。

### 5.3 关闭（任务 7.5）

同一事务内锁定订单行与履约/款项投影，**重新读取并逐项校验**：

1. **履约**：每条明细 `累计有效发货 ≥ 当前有效需求`（需求已由订单变更维护，生产完成或余量不能替代交付）→ 否则 `CLOSE_FULFILLMENT_PENDING`；
2. **应收结清**：`订单结清净额 ≥ 当前有效应收` → 否则 `CLOSE_SETTLEMENT_PENDING`；
3. **无待退款**：`订单待退款 = 0` → 否则 `CLOSE_REFUND_PENDING`。

全部通过才置 `CLOSED` 并记录关闭人与时间。**已关闭不得重开**。

### 5.4 终态互斥与关闭后约束（任务 7.6）

- `CANCELLED` 与 `CLOSED` 都是终态，不可互转、不可重开；
- 关闭后**禁止**：新增原订单生产计划（计划创建要求订单 `CONFIRMED`）、确认发货（要求 `CONFIRMED`）、新增收款；
- 关闭后**允许**：独立物流修改（只要求批次 `CONFIRMED`）、有依据的实际退款补录、发货等量更正、售后（阶段八）。

## 6. API 契约（阶段七）

| 能力 | 方法与路径 | 幂等 | 成功结果 | 主要拒绝码 |
| --- | --- | --- | --- | --- |
| 收款 | `POST /api/orders/{id}/payments` | 写入幂等 | 不可变收款事实 + 结清投影 | `PAYMENT_DRAFT_FORBIDDEN`, `STATE_NOT_EDITABLE`, `VALIDATION_INVALID` |
| 退款 | `POST /api/orders/{id}/refunds` | 写入幂等 | 不可变退款事实（关联来源）+ 结清投影 | `REFUND_EXCEEDS_RECEIPTS`, `REFUND_REFERENCE_REQUIRED`, `VALIDATION_INVALID` |
| 结清视图 | `GET /api/orders/{id}/settlement` | 否 | 逐笔收退款 + 结清净额/待退款/累计实际净收 + 关闭条件逐项 | `ORDER_NOT_FOUND` |
| 关闭 | `POST /api/orders/{id}/close` | 必须幂等 | 已关闭订单 + 关闭人/时间 | `CLOSE_FULFILLMENT_PENDING`, `CLOSE_SETTLEMENT_PENDING`, `CLOSE_REFUND_PENDING`, `STATE_NOT_CANCELABLE` |

幂等：写命令要求 `Idempotency-Key`。**不新增错误码**（全部已在 `ErrorCode` 登记）。

## 7. 前端页面要点

| 路由 | 位置 | 关键点 |
| --- | --- | --- |
| `/orders/:id` | 「资金与利润」Tab | 金额快照与预计利润拆解 + 收款状态 Tag + **逐笔收退款表**（金额/日期/方式/来源/原因/操作人，只读）+ 结清净额/订单待退款/售后退款单列/累计实际净收 + **关闭条件逐项**（履约/应收/待退款三行，未满足时标红并给出原因）；显式按钮进入「登记收款」「登记退款」「关闭订单」操作，**不提供普通编辑/删除** |

## 8. 模块边界、事务与锁定

- **模块归属**：`orders`（`com.yumi.orders.settlement`），与 `orders` 同模块，可直接读履约投影与订单行。
- **事务拥有者**：收款、退款、关闭各自一个 `@Transactional`。
- **锁定顺序**：订单行 `FOR UPDATE` → `order_item_fulfillment_balances`（按 `order_item_id` 升序）→ `order_settlement_balances` `FOR UPDATE`；竞争资源的读取一律用**锁定读**（REPEATABLE READ 快照陷阱同阶段五/六）。
- **幂等**：关闭为「必须幂等」。

## 9. 不变量与错误码

1. 收款与退款不得修改/删除（只追加）；
2. 退款累计不得超过累计收款（含售后退款）；
3. 退款必须关联来源（变更单或售后单）；
4. 订单结清净额 = 累计收款 − 累计变更退款；售后退款不冲减结清净额；
5. 关闭必须同时满足履约、应收结清、无待退款；
6. 生产完成/成品余量不能替代交付；
7. 已取消与已关闭互斥且不可重开；关闭后禁止新增生产/发货/收款；
8. 所有汇总均可从收退款事实重建。

## 10. 不做项

- 不做售后台账与售后补发（阶段八）；本阶段可登记售后退款，但「售后单是否存在」的校验随阶段八接入（当前无售后表，条件恒真，如实记录）；
- 不做收款/退款的更正与冲销（阶段九的更正流程；本阶段只追加事实）；
- 不做报表、导出与打印（阶段九）；
- 不做分期/账期、发票与对账（不在首期）；
- 不做自动结清（收款状态一律由事实派生，不提供手工状态）。

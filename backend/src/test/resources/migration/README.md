# 迁移与恢复演练素材

本目录用于放置一期备份副本迁移/恢复演练的可复用核对脚本，不包含正式数据、原型示例或密钥。

当前演练记录见 `docs/operations/phase-one-runbook.md`（2026-10-21）：

- `mysqldump --single-transaction --routines --triggers yumi_it` 备份后恢复到独立库 `yumi_drill`；
- 恢复后 `orders_orders`、`production_demands`、`finance_cash_facts` 数量与有效现金合计一致；
- 使用正式 `POST /api/orders/opening-orders` 接管已有订单，订单与生产需求同事务增加，现金事实不增加。

执行核对时仅连接独立演练库，禁止对正式库执行恢复、删除或直接改表。

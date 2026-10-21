# 一期备份恢复与已有订单接管演练

- 演练日期：2026-10-21
- 范围：独立测试库 `yumi_drill`，不操作任何正式库；不导入原型示例数据。
- 结果：备份恢复数量一致；恢复库通过正式 `opening-orders` 命令接管一张已有订单，订单与生产需求同步增加，现金事实不变。

## 1. 备份

```bash
mysqldump -uroot --single-transaction --routines --triggers yumi_it > /tmp/yumi_it_backup.sql
```

`--single-transaction` 保证 InnoDB 一致性快照；备份文件保存在本机临时目录，不提交仓库。

## 2. 恢复副本

```bash
mysql -uroot -e "DROP DATABASE IF EXISTS yumi_drill; \
  CREATE DATABASE yumi_drill CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"
mysql -uroot yumi_drill < /tmp/yumi_it_backup.sql
```

## 3. 恢复核对（实际结果）

```bash
mysql -N -uroot yumi_it   -e "SELECT COUNT(*) FROM orders_orders"
mysql -N -uroot yumi_drill -e "SELECT COUNT(*) FROM orders_orders"
```

| 项目 | 源库 `yumi_it` | 恢复库 `yumi_drill` |
|---|---|---|
| `orders_orders` | 9 | 9 |
| `production_demands` | 9 | 9 |
| `finance_cash_facts` | 10 | 10 |
| 有效现金合计 | 540.00 | 540.00 |
| `finance_cost_entries` | 0 | 0 |

恢复后先核对数量与现金合计，再执行接管；未使用原型缓存或示例订单。

## 4. 已有订单接管

恢复库以独立端口启动应用（不覆盖日常开发实例）：

```bash
cd backend
YUMI_DB_URL='jdbc:mysql://127.0.0.1:3306/yumi_drill?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&allowPublicKeyRetrieval=true&useSSL=false' \
YUMI_DB_USERNAME=root YUMI_DB_PASSWORD='' YUMI_SERVER_PORT=8082 \
./mvnw -q spring-boot:run
```

通过正式命令接管（节选，完整请求体包含客户、商品/方案快照、数量、价格、收货与期初阶段）：

```bash
curl -X POST http://127.0.0.1:8082/api/orders/opening-orders \
  -H "Authorization: Bearer <管理员会话>" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: drill-intake-1' \
  --data-binary @/tmp/intake.json
```

接管结果：订单号 `SO000033`，状态 `CONFIRMED`，数量 1，金额 88.00。

接管后核对：

| 项目 | 接管前 | 接管后 |
|---|---|---|
| `orders_orders` | 9 | 10 |
| `production_demands` | 9 | 10 |
| `finance_cash_facts` | 10 | 10 |

订单与生产需求在同一事务增加；接管不产生历史任务、劳动、材料或现金事实。

## 5. 结束与清理

```bash
# 停止 8082 上的恢复库实例
# 确认无需保留演练数据后，可删除独立演练库（不影响正式库）：
mysql -uroot -e "DROP DATABASE yumi_drill;"
rm -f /tmp/yumi_it_backup.sql /tmp/intake.json
```

## 6. 禁止事项

- 不在正式库执行 `DROP DATABASE`、恢复或接管演练。
- 不把原型 HTML、localStorage 示例或静态种子订单导入正式数据。
- 恢复后的订单必须继续通过正式命令变更、发货、收付和完成，不直接改表。
- 生产部署、备份策略与保留周期由发布负责人另行确认；本演练只提供可复现步骤与结果。

set -euo pipefail
API=http://127.0.0.1:18090
JAR=/tmp/yumi-acc-cookies.txt
PW=$(security find-generic-password -s yumi-v2-local-test -w)
DB() { mysql --default-character-set=utf8mb4 -h 127.0.0.1 -u yumi_v2_test -p"$PW" yumi_v2_test -N -s -e "$1" 2>/dev/null; }
key() { uuidgen; }
j() { python3 -c '
import json, sys
node = json.load(sys.stdin)
for part in sys.argv[1].replace("]", "").replace("[", ".").split("."):
    if not part:
        continue
    node = node[int(part)] if part.isdigit() else node[part]
print(node)
' "$1"; }
TODAY=$(date +%F); TOMORROW=$(date -v+1d +%F)

echo "=== 清理上一轮验收数据 ==="
DB "SET FOREIGN_KEY_CHECKS=0;
DELETE FROM shipment_source_links WHERE shipment_item_id IN (SELECT si.id FROM shipment_items si JOIN shipments s ON s.id=si.shipment_id JOIN orders o ON o.id=s.order_id JOIN customers c ON c.id=o.customer_id WHERE c.name LIKE '验收-%');
DELETE FROM shipment_logistics_changes WHERE shipment_id IN (SELECT s.id FROM shipments s JOIN orders o ON o.id=s.order_id JOIN customers c ON c.id=o.customer_id WHERE c.name LIKE '验收-%');
DELETE FROM shipment_corrections WHERE original_shipment_id IN (SELECT s.id FROM shipments s JOIN orders o ON o.id=s.order_id JOIN customers c ON c.id=o.customer_id WHERE c.name LIKE '验收-%');
DELETE FROM shipment_items WHERE shipment_id IN (SELECT s.id FROM shipments s JOIN orders o ON o.id=s.order_id JOIN customers c ON c.id=o.customer_id WHERE c.name LIKE '验收-%');
DELETE FROM shipments WHERE order_id IN (SELECT o.id FROM orders o JOIN customers c ON c.id=o.customer_id WHERE c.name LIKE '验收-%');
DELETE FROM production_reminders WHERE order_id IN (SELECT o.id FROM orders o JOIN customers c ON c.id=o.customer_id WHERE c.name LIKE '验收-%');
DELETE FROM overtime_preemptions WHERE order_id IN (SELECT o.id FROM orders o JOIN customers c ON c.id=o.customer_id WHERE c.name LIKE '验收-%');
DELETE FROM remake_sources WHERE order_id IN (SELECT o.id FROM orders o JOIN customers c ON c.id=o.customer_id WHERE c.name LIKE '验收-%');
DELETE FROM rework_sources WHERE order_id IN (SELECT o.id FROM orders o JOIN customers c ON c.id=o.customer_id WHERE c.name LIKE '验收-%');
DELETE FROM production_verifications WHERE order_id IN (SELECT o.id FROM orders o JOIN customers c ON c.id=o.customer_id WHERE c.name LIKE '验收-%');
DELETE FROM production_plan_adjustments WHERE plan_id IN (SELECT p.id FROM production_plans p JOIN orders o ON o.id=p.order_id JOIN customers c ON c.id=o.customer_id WHERE c.name LIKE '验收-%');
DELETE FROM production_plans WHERE order_id IN (SELECT o.id FROM orders o JOIN customers c ON c.id=o.customer_id WHERE c.name LIKE '验收-%');
DELETE FROM inventory_allocation_lines WHERE batch_id IN (SELECT b.id FROM inventory_batches b JOIN products p ON p.id=b.product_id WHERE p.name LIKE '验收-%');
DELETE FROM inventory_movement_lines WHERE batch_id IN (SELECT b.id FROM inventory_batches b JOIN products p ON p.id=b.product_id WHERE p.name LIKE '验收-%');
DELETE FROM inventory_allocations WHERE order_id IN (SELECT o.id FROM orders o JOIN customers c ON c.id=o.customer_id WHERE c.name LIKE '验收-%');
DELETE FROM inventory_batches WHERE product_id IN (SELECT id FROM products WHERE name LIKE '验收-%');
DELETE FROM order_inventory_plan_lines WHERE order_id IN (SELECT o.id FROM orders o JOIN customers c ON c.id=o.customer_id WHERE c.name LIKE '验收-%');
DELETE FROM fulfillment_entries WHERE order_id IN (SELECT o.id FROM orders o JOIN customers c ON c.id=o.customer_id WHERE c.name LIKE '验收-%');
DELETE FROM order_item_fulfillment_balances WHERE order_id IN (SELECT o.id FROM orders o JOIN customers c ON c.id=o.customer_id WHERE c.name LIKE '验收-%');
DELETE FROM order_item_snapshots WHERE order_id IN (SELECT o.id FROM orders o JOIN customers c ON c.id=o.customer_id WHERE c.name LIKE '验收-%');
DELETE FROM order_confirmation_snapshots WHERE order_id IN (SELECT o.id FROM orders o JOIN customers c ON c.id=o.customer_id WHERE c.name LIKE '验收-%');
DELETE FROM order_items WHERE order_id IN (SELECT o.id FROM orders o JOIN customers c ON c.id=o.customer_id WHERE c.name LIKE '验收-%');
DELETE FROM orders WHERE customer_id IN (SELECT id FROM customers WHERE name LIKE '验收-%');
DELETE FROM other_schedule_time_corrections WHERE schedule_id IN (SELECT id FROM other_schedules WHERE employee_id IN (SELECT id FROM employees WHERE name LIKE '验收-%'));
DELETE FROM other_schedule_verifications WHERE schedule_id IN (SELECT id FROM other_schedules WHERE employee_id IN (SELECT id FROM employees WHERE name LIKE '验收-%'));
DELETE FROM other_schedules WHERE employee_id IN (SELECT id FROM employees WHERE name LIKE '验收-%');
DELETE FROM employee_work_types WHERE employee_id IN (SELECT id FROM employees WHERE name LIKE '验收-%');
DELETE FROM employees WHERE name LIKE '验收-%';
DELETE FROM products WHERE name LIKE '验收-%';
DELETE FROM customers WHERE name LIKE '验收-%';
DELETE FROM inventory_movements WHERE id NOT IN (SELECT DISTINCT movement_id FROM inventory_movement_lines);
SET FOREIGN_KEY_CHECKS=1;"

echo "=== 管理员与登录 ==="
HASH=$(htpasswd -bnBC 10 '' 'Yumi-Local-2026!' | tr -d ':\n')
DB "DELETE FROM admin_accounts WHERE username='admin';
INSERT INTO admin_accounts (username, password_hash, status, version, created_at, updated_at)
VALUES ('admin', '$HASH', 'ACTIVE', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6));"
curl -s -c "$JAR" -X POST "$API/api/session" -H 'Content-Type: application/json' -d '{"username":"admin","password":"Yumi-Local-2026!"}' | j code

STAR=$(curl -s -b "$JAR" "$API/api/settings/static-data/STAR_LEVEL" | j "data.0.id")
TIER=$(curl -s -b "$JAR" "$API/api/settings/static-data/PACKAGING_TIER" | j "data.0.id")
# 缝边种类名称在类别内唯一：先复用已存在条目，保证脚本可反复重铺
SEAM=$(DB "SELECT id FROM seam_types WHERE name='验收-标准缝边' LIMIT 1;")
if [ -z "$SEAM" ]; then
  SEAM=$(curl -s -b "$JAR" -X POST "$API/api/settings/static-data/SEAM_TYPE/items" -H 'Content-Type: application/json' -H "Idempotency-Key: $(key)" -d '{"name":"验收-标准缝边","costPrice":"1.2500"}' | j "data.id")
fi

echo "=== 客户/商品/员工 ==="
CUST=$(curl -s -b "$JAR" -X POST "$API/api/customers" -H 'Content-Type: application/json' -H "Idempotency-Key: $(key)" -d '{"name":"验收-生产客户","defaultRecipient":"张三","defaultRecipientPhone":"13800000000","defaultRegion":"华东","defaultAddress":"上海市浦东新区示例路 1 号"}' | j "data.id")
P1=$(curl -s -b "$JAR" -X POST "$API/api/products" -H 'Content-Type: application/json' -H "Idempotency-Key: $(key)" -d "{\"name\":\"验收-生产商品甲\",\"starLevelId\":$STAR,\"salePrice\":\"25.0000\",\"weightG\":270,\"packagingTierId\":$TIER}" | j "data.id")
P2=$(curl -s -b "$JAR" -X POST "$API/api/products" -H 'Content-Type: application/json' -H "Idempotency-Key: $(key)" -d "{\"name\":\"验收-生产商品乙\",\"starLevelId\":$STAR,\"salePrice\":\"18.0000\",\"weightG\":200,\"packagingTierId\":$TIER}" | j "data.id")
emp() { curl -s -b "$JAR" -X POST "$API/api/employees" -H 'Content-Type: application/json' -H "Idempotency-Key: $(key)" -d "{\"name\":\"$1\",\"firstHireDate\":\"$TODAY\",\"workTypes\":[\"$2\"]}" | j "data.id"; }
MAKER=$(emp "验收-制作员工" MAKING); PACKER=$(emp "验收-包装员工" PACKING_BAG); CUTTER=$(emp "验收-缝边员工" SEAM_CUTTING)

echo "=== 订单（Q=10/E=4 + Q=6）并确认 ==="
ORDER=$(curl -s -b "$JAR" -X POST "$API/api/orders" -H 'Content-Type: application/json' -H "Idempotency-Key: $(key)" -d "{\"customerId\":$CUST,\"orderDate\":\"$TODAY\",\"items\":[{\"productId\":$P1,\"quantity\":10,\"seamQuantity\":4,\"seamTypeId\":$SEAM,\"seamFee\":\"2.0000\",\"unitPrice\":\"25.0000\"},{\"productId\":$P2,\"quantity\":6,\"seamQuantity\":0,\"unitPrice\":\"18.0000\"}]}" | j "data.id")
curl -s -b "$JAR" -X POST "$API/api/orders/$ORDER/confirm" -H 'Content-Type: application/json' -H "Idempotency-Key: $(key)" -d '{}' | j "data.status"
ITEM1=$(DB "SELECT id FROM order_items WHERE order_id=$ORDER AND line_no=1;")
ITEM2=$(DB "SELECT id FROM order_items WHERE order_id=$ORDER AND line_no=2;")

echo "=== 库存领用（制作 4 → 捏毛装袋）==="
BATCH=$(curl -s -b "$JAR" -X POST "$API/api/inventory/batches" -H 'Content-Type: application/json' -H "Idempotency-Key: $(key)" -d "{\"productId\":$P1,\"node\":\"MAKING\",\"seamState\":\"NONE\",\"quantity\":4,\"inventoryDate\":\"$TODAY\"}" | j "data.id")
curl -s -b "$JAR" -X POST "$API/api/inventory-allocations" -H 'Content-Type: application/json' -H "Idempotency-Key: $(key)" -d "{\"orderId\":$ORDER,\"reason\":\"验收领用\",\"lines\":[{\"batchId\":$BATCH,\"orderItemId\":$ITEM1,\"quantity\":4,\"targetNode\":\"PACKING_BAG\"}]}" | j "data.status"

plan() { curl -s -b "$JAR" -X POST "$API/api/production-plans" -H 'Content-Type: application/json' -H "Idempotency-Key: $(key)" -d "{\"planType\":\"NORMAL\",\"orderItemId\":$1,\"node\":\"$2\",\"planDate\":\"$3\",\"employeeId\":$4,\"quantity\":$5}"; }
echo "=== 计划与核验 ==="
MP=$(plan "$ITEM1" MAKING "$TODAY" "$MAKER" 10 | j "data.id")
PP=$(plan "$ITEM1" PACKING_BAG "$TODAY" "$PACKER" 4 | j "data.id")
SP=$(plan "$ITEM1" SEAM_CUTTING "$TODAY" "$CUTTER" 4 | j "data.id")
curl -s -b "$JAR" -X POST "$API/api/production-plans/$MP/verify" -H 'Content-Type: application/json' -H "Idempotency-Key: $(key)" -d '{"completedQuantity":6,"qualifiedQuantity":6,"reworkQuantity":0,"scrapQuantity":0}' | j "data.incompleteReminderId"
VP=$(plan "$ITEM2" MAKING "$TOMORROW" "$MAKER" 6 | j "data.id")
VID=$(curl -s -b "$JAR" -X POST "$API/api/production-plans/$VP/verify" -H 'Content-Type: application/json' -H "Idempotency-Key: $(key)" -d '{"completedQuantity":6,"qualifiedQuantity":3,"reworkQuantity":2,"scrapQuantity":1}' | j "data.id")
curl -s -b "$JAR" -X POST "$API/api/rework-sources" -H 'Content-Type: application/json' -H "Idempotency-Key: $(key)" -d "{\"verificationId\":$VID,\"targetNode\":\"MAKING\",\"quantity\":2}" >/dev/null
# 明天 4 件未来计划（超额任务来源）
plan "$ITEM1" MAKING "$TOMORROW" "$MAKER" 4 >/dev/null

echo "=== 其他排班（已核验 120 分钟）==="
SCH=$(curl -s -b "$JAR" -X POST "$API/api/other-schedules" -H 'Content-Type: application/json' -H "Idempotency-Key: $(key)" -d "{\"scheduleDate\":\"$TODAY\",\"employeeId\":$PACKER,\"hours\":1,\"minutes\":30,\"note\":\"验收-打包杂活\"}" | j "data.id")
curl -s -b "$JAR" -X POST "$API/api/other-schedules/$SCH/verify" -H 'Content-Type: application/json' -H "Idempotency-Key: $(key)" -d '{"hours":2,"minutes":0}' | j "data.effectiveMinutes"

echo "=== 生产流转至可发货并部分发货（8.12 前提：部分发货且订单仍已确认）==="
curl -s -b "$JAR" -X POST "$API/api/production-plans/$PP/verify" -H 'Content-Type: application/json' -H "Idempotency-Key: $(key)" -d '{"completedQuantity":4,"qualifiedQuantity":4,"reworkQuantity":0,"scrapQuantity":0}' >/dev/null
curl -s -b "$JAR" -X POST "$API/api/production-plans/$SP/verify" -H 'Content-Type: application/json' -H "Idempotency-Key: $(key)" -d '{"completedQuantity":4,"qualifiedQuantity":4,"reworkQuantity":0,"scrapQuantity":0}' >/dev/null
SHIP=$(curl -s -b "$JAR" -X POST "$API/api/orders/$ORDER/shipments" -H 'Content-Type: application/json' -H "Idempotency-Key: $(key)" -d "{\"shipmentDate\":\"$TODAY\",\"freight\":\"8.0000\",\"carrier\":\"顺丰\",\"trackingNo\":\"SF-ACCEPT-0001\",\"items\":[{\"orderItemId\":$ITEM1,\"quantity\":4}]}" | j "data.id")
curl -s -b "$JAR" -X POST "$API/api/orders/$ORDER/shipments/$SHIP/confirm" -H "Idempotency-Key: $(key)" | j "data.status"

echo "=== 部分收款 ==="
curl -s -b "$JAR" -X POST "$API/api/orders/$ORDER/payments" -H 'Content-Type: application/json' -H "Idempotency-Key: $(key)" -d "{\"amount\":\"40.0000\",\"businessDate\":\"$TODAY\",\"method\":\"TRANSFER\",\"note\":\"验收-部分收款\"}" | j "data.actualNetReceived"

echo "=== 状态 ==="
DB "SELECT CONCAT('订单 ',order_no,' ',status) FROM orders WHERE id=$ORDER;
SELECT CONCAT('计划 ',plan_no,' ',plan_type,' ',node,' ',plan_date,' 数量 ',quantity,' ',status) FROM production_plans WHERE order_id=$ORDER ORDER BY id;
SELECT CONCAT('提醒 ',reminder_type,' 数量 ',quantity,' ',status) FROM production_reminders ORDER BY id;
SELECT CONCAT('履约 明细 ',order_item_id,' 需求 ',required_quantity,' 可发货 ',shippable_quantity,' 已发 ',shipped_quantity) FROM order_item_fulfillment_balances WHERE order_id=$ORDER;
SELECT CONCAT('发货 ',shipment_no,' ',status) FROM shipments WHERE order_id=$ORDER;
SELECT CONCAT('结清 已收 ',paid_amount,' 变更退款 ',change_refund_amount,' 售后退款 ',after_sales_refund_amount,' 净收 ',net_settled_amount,' 待退 ',refund_pending_amount) FROM order_settlement_balances WHERE order_id=$ORDER;"
echo "入口 http://127.0.0.1:5190/orders/$ORDER"

# 历史遗留检查（只提示不删除）：早期版本的铺数/演示数据名称不在本轮清理口径内，
# 删它们有误删风险，故只报告条数，由操作者决定是否手工清理。
LEFTOVER=$(DB "SELECT CONCAT('products=', (SELECT COUNT(*) FROM products WHERE name NOT LIKE '验收-%'),
                                   ' customers=', (SELECT COUNT(*) FROM customers WHERE name NOT LIKE '验收-%'),
                                   ' employees=', (SELECT COUNT(*) FROM employees WHERE name NOT LIKE '验收-%'));")
echo "=== 非验收命名的基础资料（含历史遗留与手工录入，仅供核对）==="
echo "$LEFTOVER"

-- 发货更正可撤回整笔明细：允许明细数量减至零，原更正事实保留
ALTER TABLE orders_shipment_items
    DROP CHECK ck_shipment_item_quantity,
    ADD CONSTRAINT ck_shipment_item_quantity CHECK (quantity >= 0);

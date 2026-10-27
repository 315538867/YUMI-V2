ALTER TABLE orders_orders
    ADD COLUMN order_note VARCHAR(500) NULL AFTER expected_delivery_date;

-- Meal cost, stock forecasting and procurement support.
-- Run once against the_full_order before using the new endpoints.

ALTER TABLE the_full_order.tb_account_table_meals
    ADD COLUMN meal_budget_per_person DECIMAL(15,2) NULL COMMENT '고객사 1인 식단가';

ALTER TABLE the_full_order.tb_account_table_meals_detail
    ADD COLUMN serving_qty DECIMAL(13,3) NOT NULL DEFAULT 1 COMMENT '예상 식수';

ALTER TABLE the_full_order.tb_account_ingredient_master
    ADD COLUMN welstory_item_code VARCHAR(18) NULL COMMENT '웰스토리 품목코드',
    ADD COLUMN purchase_price DECIMAL(15,2) NULL COMMENT '구매 포장 단가',
    ADD COLUMN package_base_qty DECIMAL(13,3) NULL COMMENT '포장당 기준용량',
    ADD COLUMN max_stock_qty DECIMAL(13,3) NULL COMMENT '최대 보유량';

CREATE TABLE IF NOT EXISTS the_full_order.tb_account_inventory_movement (
    movement_id VARCHAR(40) NOT NULL,
    account_id VARCHAR(40) NOT NULL,
    ingredient_id VARCHAR(40) NOT NULL,
    movement_type VARCHAR(10) NOT NULL COMMENT 'IN/OUT/ADJUST',
    quantity DECIMAL(13,3) NOT NULL,
    unit VARCHAR(20) NOT NULL,
    reference_type VARCHAR(20) NULL,
    reference_id VARCHAR(40) NULL,
    movement_at DATETIME NOT NULL,
    user_id VARCHAR(40) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (movement_id),
    KEY idx_inventory_movement_usage (account_id, ingredient_id, movement_at),
    UNIQUE KEY uk_inventory_movement_reference (account_id, reference_type, reference_id, ingredient_id)
);

CREATE TABLE IF NOT EXISTS the_full_order.tb_account_purchase_order (
    purchase_order_id VARCHAR(40) NOT NULL,
    account_id VARCHAR(40) NOT NULL,
    sold_to VARCHAR(10) NOT NULL,
    client_ord VARCHAR(20) NOT NULL,
    req_delivery_date CHAR(8) NOT NULL,
    status VARCHAR(20) NOT NULL COMMENT 'ORDERED/CONFIRMED/RECEIVED/NOT_RECEIVED/FAILED',
    welstory_res_cd VARCHAR(10) NULL,
    welstory_res_msg VARCHAR(500) NULL,
    client_note VARCHAR(1000) NULL,
    user_id VARCHAR(40) NULL,
    ordered_at DATETIME NOT NULL,
    reconciled_at DATETIME NULL,
    received_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    mod_at DATETIME NULL,
    PRIMARY KEY (purchase_order_id),
    UNIQUE KEY uk_purchase_order_client_ord (client_ord),
    KEY idx_purchase_order_account (account_id, ordered_at)
);

CREATE TABLE IF NOT EXISTS the_full_order.tb_account_purchase_order_item (
    purchase_order_id VARCHAR(40) NOT NULL,
    client_ord_item VARCHAR(6) NOT NULL,
    ingredient_id VARCHAR(40) NOT NULL,
    menu_id VARCHAR(40) NULL,
    welstory_item_code VARCHAR(18) NOT NULL,
    order_qty DECIMAL(15,3) NOT NULL,
    order_unit VARCHAR(20) NULL,
    base_unit VARCHAR(20) NOT NULL,
    base_qty_per_order_unit DECIMAL(13,3) NOT NULL DEFAULT 1,
    received_qty DECIMAL(15,3) NOT NULL DEFAULT 0,
    item_status CHAR(1) NOT NULL DEFAULT 'N',
    welstory_res_cd VARCHAR(10) NULL,
    welstory_error_msg VARCHAR(500) NULL,
    PRIMARY KEY (purchase_order_id, client_ord_item),
    KEY idx_purchase_order_item_ingredient (ingredient_id)
);

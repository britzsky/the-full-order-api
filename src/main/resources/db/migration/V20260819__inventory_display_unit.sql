ALTER TABLE tb_account_inventory_balance
    ADD COLUMN current_unit VARCHAR(30) NULL COMMENT '현재고 화면 입력/표시 단위' AFTER base_unit;

UPDATE tb_account_inventory_balance
   SET current_unit = base_unit
 WHERE current_unit IS NULL;

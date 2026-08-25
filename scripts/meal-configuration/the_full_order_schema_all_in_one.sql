/*
 * 식단·원가·공급처상품·재고·발주 전체 DROP + CREATE 단일 스크립트
 * DBMS: MySQL 8.x / MariaDB 10.5+
 *
 * 경고: 실행하면 아래 테이블의 기존 데이터가 모두 삭제된다.
 * 운영 DB에서 실행하기 전에 반드시 백업하고 데이터 이관 필요 여부를 확인한다.
 *
 * 선행 스크립트 없음. 이 파일 하나만 사용한다.
 * 공통 tb_ingredient_master는 유지하며 본 파일에서 삭제하지 않는다.
 * tb_menu_master는 meal_plan_type 반영을 위해 삭제 후 다시 생성한다.
 */

USE the_full_order;

-- tb_ingredient_master.ingredient_id가 utf8mb4_0900_ai_ci이므로
-- 본 스크립트에서 생성하는 테이블의 문자 Collation을 동일하게 통일한다.

SET FOREIGN_KEY_CHECKS = 0;

DROP TABLE IF EXISTS tb_account_meal_slot_setting;
DROP TABLE IF EXISTS tb_meal_slot_master;

/* 자식 테이블부터 삭제한다. */
DROP TABLE IF EXISTS tb_account_purchase_order_item;
DROP TABLE IF EXISTS tb_account_purchase_order;
DROP TABLE IF EXISTS tb_account_procurement_cart_item;
DROP TABLE IF EXISTS tb_account_procurement_cart;
DROP TABLE IF EXISTS tb_account_inventory_movement;
DROP TABLE IF EXISTS tb_account_inventory_balance;
DROP TABLE IF EXISTS tb_account_meal_ingredient_requirement;
DROP TABLE IF EXISTS tb_account_meal_menu_cost_snapshot;
DROP TABLE IF EXISTS tb_account_table_meals_detail;
DROP TABLE IF EXISTS tb_account_meal_service;
DROP TABLE IF EXISTS tb_account_table_meals;
DROP TABLE IF EXISTS tb_account_recipe_detail;
DROP TABLE IF EXISTS tb_account_menu_master;
DROP TABLE IF EXISTS tb_recipe_detail;
DROP TABLE IF EXISTS tb_menu_master;
DROP TABLE IF EXISTS tb_account_ingredient_product;
DROP TABLE IF EXISTS tb_account_ingredient_master;
DROP TABLE IF EXISTS tb_supplier_ingredient_price_history;
DROP TABLE IF EXISTS tb_supplier_ingredient_product;
DROP TABLE IF EXISTS tb_supplier;

/* ========================================================================== */
/* 1. 공급처와 공급처 판매상품                                                */
/* ========================================================================== */

CREATE TABLE tb_meal_slot_master (
    meal_slot_code         TINYINT      NOT NULL COMMENT '식사구분 코드. 0 조식, 1 오전간식, 2 중식, 3 오후간식, 4 DC, 5 석식, 6 야식, 7 기타간식, 8 킨더간식',
    meal_slot_name         VARCHAR(30)  NOT NULL COMMENT '식사구분명',
    display_order          TINYINT      NOT NULL COMMENT '식단표 기본 표시 순서',
    active_yn              CHAR(1)      NOT NULL DEFAULT 'Y' COMMENT '시스템 사용 여부. Y/N',
    created_at             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '등록일시',
    mod_at                 DATETIME     NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '수정일시',
    user_id                VARCHAR(45)  NULL COMMENT '최종 처리 사용자 ID',
    PRIMARY KEY (meal_slot_code),
    UNIQUE KEY uk_meal_slot_name (meal_slot_name),
    UNIQUE KEY uk_meal_slot_display_order (display_order),
    CONSTRAINT chk_meal_slot_code CHECK (meal_slot_code BETWEEN 0 AND 8),
    CONSTRAINT chk_meal_slot_active CHECK (active_yn IN ('Y', 'N'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='식단표 공통 식사구분 코드';

INSERT INTO tb_meal_slot_master (meal_slot_code, meal_slot_name, display_order) VALUES
    (0, '조식', 0),
    (1, '오전간식', 1),
    (2, '중식', 2),
    (3, '오후간식', 3),
    (4, 'DC', 4),
    (5, '석식', 5),
    (6, '저녁간식', 6),
    (7, '기타간식', 7),
    (8, '킨더간식', 8);

CREATE TABLE tb_account_meal_slot_setting (
    account_id             VARCHAR(45) NOT NULL COMMENT '고객사 식별자',
    meal_slot_code         TINYINT     NOT NULL COMMENT '고객사가 제공하는 식사구분 코드',
    display_order          TINYINT     NOT NULL COMMENT '고객사 식단표의 식사구분 표시 순서',
    created_at             DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '등록일시',
    mod_at                 DATETIME    NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '수정일시',
    user_id                VARCHAR(45) NULL COMMENT '최종 처리 사용자 ID',
    PRIMARY KEY (account_id, meal_slot_code),
    KEY idx_account_meal_slot (account_id, meal_slot_code),
    UNIQUE KEY uk_account_meal_slot_order (account_id, display_order),
    CONSTRAINT fk_account_meal_slot_master
        FOREIGN KEY (meal_slot_code) REFERENCES tb_meal_slot_master (meal_slot_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='고객사별 식단표 제공 식사구분 설정';

CREATE TABLE tb_supplier (
    supplier_id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '공급처 내부 식별자',
    supplier_code          VARCHAR(30)  NOT NULL COMMENT '공급처 코드. WELSTORY, OURHOME 등',
    supplier_name          VARCHAR(100) NOT NULL COMMENT '공급처명',
    supplier_type          VARCHAR(20)  NOT NULL DEFAULT 'FOOD' COMMENT '공급처 유형',
    active_yn              CHAR(1)      NOT NULL DEFAULT 'Y' COMMENT '사용 여부. Y/N',
    created_at             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '등록일시',
    mod_at                 DATETIME     NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '수정일시',
    user_id                VARCHAR(45)  NULL COMMENT '최종 처리 사용자 ID',
    PRIMARY KEY (supplier_id),
    UNIQUE KEY uk_supplier_code (supplier_code),
    KEY idx_supplier_active (active_yn)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='식자재 공급처 마스터';

CREATE TABLE tb_supplier_ingredient_product (
    supplier_product_id    BIGINT        NOT NULL AUTO_INCREMENT COMMENT '공급처 판매상품 식별자',
    supplier_id            BIGINT        NOT NULL COMMENT '공급처 식별자',
    ingredient_id          VARCHAR(20)   NOT NULL COMMENT '표준 식재료 식별자. 공급처가 달라도 같은 양파면 동일 값',
    supplier_item_code     VARCHAR(50)   NOT NULL COMMENT '공급처 상품코드',
    product_name           VARCHAR(255)  NOT NULL COMMENT '공급처 상품명',
    order_unit             VARCHAR(30)   NOT NULL COMMENT '발주 단위. BOX/EA/봉 등',
    package_qty            DECIMAL(13,3) NOT NULL DEFAULT 1 COMMENT '포장 표시 수량. 20kg 상품이면 20',
    package_unit           VARCHAR(30)   NOT NULL COMMENT '포장 표시 단위. kg/g/L/ml/EA 등',
    base_qty               DECIMAL(13,3) NOT NULL COMMENT '발주단위 1개의 기준단위 총수량. 20kg이면 20000g',
    base_unit              VARCHAR(30)   NOT NULL COMMENT '계산 기준단위. g/ml/EA 등',
    minimum_order_qty      DECIMAL(13,3) NOT NULL DEFAULT 1 COMMENT '최소 발주수량',
    order_multiple_qty     DECIMAL(13,3) NOT NULL DEFAULT 1 COMMENT '발주 배수',
    tax_type               VARCHAR(20)   NULL COMMENT '과세 유형',
    active_yn              CHAR(1)       NOT NULL DEFAULT 'Y' COMMENT '상품 사용 여부. Y/N',
    created_at             DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '등록일시',
    mod_at                 DATETIME      NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '수정일시',
    user_id                VARCHAR(45)   NULL COMMENT '최종 처리 사용자 ID',
    PRIMARY KEY (supplier_product_id),
    UNIQUE KEY uk_supplier_item (supplier_id, supplier_item_code),
    KEY idx_supplier_product_ingredient (ingredient_id),
    CONSTRAINT fk_supplier_product_supplier
        FOREIGN KEY (supplier_id) REFERENCES tb_supplier (supplier_id),
    CONSTRAINT fk_supplier_product_ingredient
        FOREIGN KEY (ingredient_id) REFERENCES tb_ingredient_master (ingredient_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='공급처별 식재료 판매상품. 공급처가 다르면 같은 식재료도 별도 행';

CREATE TABLE tb_supplier_ingredient_price_history (
    price_history_id       BIGINT        NOT NULL AUTO_INCREMENT COMMENT '공급가격 이력 식별자',
    supplier_product_id    BIGINT        NOT NULL COMMENT '공급처 판매상품 식별자',
    purchase_price         DECIMAL(15,2) NOT NULL COMMENT '발주단위 1개 구매가격',
    effective_from         DATETIME      NOT NULL COMMENT '가격 적용 시작일시',
    effective_to           DATETIME      NULL COMMENT '가격 적용 종료일시. NULL이면 현재 가격',
    created_at             DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '등록일시',
    user_id                VARCHAR(45)   NULL COMMENT '가격 등록 사용자 ID',
    PRIMARY KEY (price_history_id),
    UNIQUE KEY uk_product_price_start (supplier_product_id, effective_from),
    KEY idx_product_price_period (supplier_product_id, effective_from, effective_to),
    CONSTRAINT fk_price_product
        FOREIGN KEY (supplier_product_id)
        REFERENCES tb_supplier_ingredient_product (supplier_product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='공급처 상품 구매가격 이력';

/* ========================================================================== */
/* 2. 거래처 식재료 마스터                                                    */
/* ========================================================================== */

CREATE TABLE tb_account_ingredient_master (
    account_id             VARCHAR(45)   NOT NULL COMMENT '거래처 식별자',
    ingredient_id          VARCHAR(20)   NOT NULL COMMENT '표준 식재료 식별자',
    ingredient_name_raw    VARCHAR(255)  NULL COMMENT '거래처에서 사용하는 원본 식재료명',
    ingredient_name_std    VARCHAR(255)  NULL COMMENT '표준화된 식재료명',
    category_name          VARCHAR(100)  NULL COMMENT '식재료 카테고리',
    base_unit              VARCHAR(30)   NOT NULL COMMENT '레시피·원가·재고 계산 기준단위. g/ml/EA 등',
    storage_type           VARCHAR(20)   NULL COMMENT '보관 방식. ROOM/REFRIGERATED/FROZEN 등',
    needs_review           TINYINT       NOT NULL DEFAULT 0 COMMENT '식재료 정보 검토 필요 여부. 0/1',
    note                   VARCHAR(300)  NULL COMMENT '거래처 식재료 메모',
    active_yn              CHAR(1)       NOT NULL DEFAULT 'Y' COMMENT '사용 여부. Y/N',
    created_at             DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '등록일시',
    mod_at                 DATETIME      NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '수정일시',
    user_id                VARCHAR(45)   NULL COMMENT '최종 처리 사용자 ID',
    PRIMARY KEY (account_id, ingredient_id),
    KEY idx_account_ingredient_name (account_id, ingredient_name_std),
    CONSTRAINT fk_account_ingredient_standard
        FOREIGN KEY (ingredient_id) REFERENCES tb_ingredient_master (ingredient_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='거래처별 표준 식재료 정보. 공급처 상품·가격·재고는 별도 테이블에서 관리';

CREATE TABLE tb_account_ingredient_product (
    account_ingredient_product_id BIGINT        NOT NULL AUTO_INCREMENT COMMENT '거래처 사용상품 식별자',
    account_id                    VARCHAR(45)   NOT NULL COMMENT '거래처 식별자',
    supplier_product_id           BIGINT        NOT NULL COMMENT '공급처 판매상품 식별자',
    preferred_yn                  CHAR(1)       NOT NULL DEFAULT 'N' COMMENT '같은 식재료 중 우선 발주상품 여부',
    safe_stock_base_qty           DECIMAL(13,3) NOT NULL DEFAULT 0 COMMENT '기준단위 기준 안전재고',
    max_stock_base_qty            DECIMAL(13,3) NULL COMMENT '최대 또는 적정 보유량. 노란색 30% 판단 기준',
    lead_time_days                INT           NOT NULL DEFAULT 0 COMMENT '주문부터 입고까지 예상 일수',
    default_location_id           VARCHAR(40)   NULL COMMENT '기본 보관 위치 식별자',
    active_yn                     CHAR(1)       NOT NULL DEFAULT 'Y' COMMENT '거래처 사용 여부. Y/N',
    created_at                    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '등록일시',
    mod_at                        DATETIME      NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '수정일시',
    user_id                       VARCHAR(45)   NULL COMMENT '최종 처리 사용자 ID',
    PRIMARY KEY (account_ingredient_product_id),
    UNIQUE KEY uk_account_supplier_product (account_id, supplier_product_id),
    KEY idx_account_product_active (account_id, active_yn),
    CONSTRAINT fk_account_product_supplier_product
        FOREIGN KEY (supplier_product_id)
        REFERENCES tb_supplier_ingredient_product (supplier_product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='거래처가 사용하는 공급처별 식재료 상품';

/* ========================================================================== */
/* 3. 공통 메뉴 및 거래처 메뉴 마스터                                         */
/* ========================================================================== */

CREATE TABLE tb_menu_master (
    menu_id                VARCHAR(20)   NOT NULL COMMENT '공통 메뉴 식별자',
    source_row             INT           NULL COMMENT '원본 데이터 식재료 또는 메뉴 순번',
    menu_name_raw          VARCHAR(255)  NULL COMMENT '원본 메뉴명',
    menu_name              VARCHAR(255)  NOT NULL COMMENT '표준 메뉴명',
    food_type              TINYINT       NOT NULL COMMENT '음식 유형. 1 한식, 2 중식, 3 일식, 4 분식, 5 간식, 6 기타',
    food_type_reason       VARCHAR(300)  NULL COMMENT '음식 유형 분류 사유 또는 키워드',
    food_type_confidence   VARCHAR(20)   NULL COMMENT '음식 유형 분류 신뢰도',
    menu_img               TEXT          NULL COMMENT '메뉴 이미지 경로 또는 URL',
    menu_type              INT           NULL COMMENT '0 주메뉴, 1 부메뉴, 2 후식/간식, 3 음료, 4 기타',
    menu_gubun             INT           NULL COMMENT '밥·국·무침·튀김 등 메뉴 세부 분류 코드',
    meal_plan_type         TINYINT       NOT NULL DEFAULT 0 COMMENT '식단표 타입. 0 일반식, 1 가성비식단, 2 요양원맞춤식단, 3 테마식단, 4 다이어트식단, 5 프리미엄식단',
    calories_per_serving   DECIMAL(10,2) NULL COMMENT '메뉴 1인분 기준 열량(kcal)',
    del_yn                 CHAR(1)       NOT NULL DEFAULT 'N' COMMENT '논리 삭제 여부. Y/N',
    created_at             DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '등록일시',
    mod_at                 DATETIME      NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '수정일시',
    user_id                VARCHAR(45)   NULL COMMENT '최종 처리 사용자 ID',
    PRIMARY KEY (menu_id),
    KEY idx_menu_meal_plan_type (meal_plan_type, del_yn),
    KEY idx_menu_name (menu_name),
    CONSTRAINT chk_menu_meal_plan_type CHECK (meal_plan_type BETWEEN 0 AND 5),
    CONSTRAINT chk_menu_calories CHECK (calories_per_serving IS NULL OR calories_per_serving >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='공통 메뉴 마스터';

CREATE TABLE tb_recipe_detail (
    recipe_detail_id        BIGINT        NOT NULL AUTO_INCREMENT COMMENT '메뉴 마스터 레시피 식재료 행 식별자',
    recipe_id               BIGINT        NOT NULL COMMENT '표준 레시피 식별자. 같은 메뉴의 식재료 행은 동일 값 사용',
    menu_id                 VARCHAR(20)   NOT NULL COMMENT '공통 메뉴 식별자',
    ingredient_id           VARCHAR(20)   NOT NULL COMMENT '표준 식재료 식별자',
    ingredient_seq          INT           NOT NULL DEFAULT 1 COMMENT '레시피 내 식재료 표시 순서',
    ingredient_name_raw     VARCHAR(255)  NULL COMMENT '등록 당시 원본 식재료명',
    qty_raw                 VARCHAR(50)   NULL COMMENT '사용자가 입력한 원본 수량 문자열',
    qty_num                 DECIMAL(13,3) NULL COMMENT '원본 단위 기준 숫자 수량',
    qty_unit                VARCHAR(30)   NULL COMMENT '사용자가 입력한 원본 단위',
    recipe_yield_servings   DECIMAL(13,3) NOT NULL DEFAULT 1 COMMENT '레시피 전체 수량이 만드는 인분 수',
    qty_base                DECIMAL(13,3) NOT NULL COMMENT '기준단위로 환산한 레시피 전체 필요량',
    base_unit               VARCHAR(30)   NOT NULL COMMENT '계산 기준단위. g/ml/EA 등',
    qty_per_person          DECIMAL(13,3) NOT NULL COMMENT '1인 필요량. qty_base / recipe_yield_servings',
    review_flag             TINYINT       NOT NULL DEFAULT 0 COMMENT '수량 또는 단위 검토 필요 여부. 0/1',
    created_at              DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '등록일시',
    mod_at                  DATETIME      NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '수정일시',
    user_id                 VARCHAR(45)   NULL COMMENT '최종 처리 사용자 ID',
    PRIMARY KEY (recipe_detail_id),
    UNIQUE KEY uk_recipe_ingredient_seq (recipe_id, menu_id, ingredient_seq),
    KEY idx_recipe_menu (menu_id),
    KEY idx_recipe_ingredient (ingredient_id),
    CONSTRAINT fk_recipe_menu
        FOREIGN KEY (menu_id) REFERENCES tb_menu_master (menu_id),
    CONSTRAINT fk_recipe_ingredient
        FOREIGN KEY (ingredient_id) REFERENCES tb_ingredient_master (ingredient_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='메뉴 마스터의 표준 1인분 기준 레시피 식재료';

CREATE TABLE tb_account_menu_master (
    account_id             VARCHAR(45)   NOT NULL COMMENT '거래처 식별자',
    menu_id                VARCHAR(20)   NOT NULL COMMENT '메뉴 식별자',
    menu_name_raw          VARCHAR(255)  NULL COMMENT '공통 메뉴에서 복사된 원본 메뉴명',
    menu_name              VARCHAR(255)  NOT NULL COMMENT '거래처에서 사용하는 메뉴명',
    food_type              TINYINT       NULL COMMENT '음식 유형. 1 한식, 2 중식, 3 일식, 4 분식, 5 간식, 6 기타',
    food_type_reason       VARCHAR(300)  NULL COMMENT '음식 유형 분류 사유 또는 키워드',
    menu_type              INT           NULL COMMENT '0 주메뉴, 1 부메뉴, 2 후식/간식, 3 음료, 4 기타',
    menu_gubun             INT           NULL COMMENT '밥·국·무침·튀김 등 메뉴 세부 분류 코드',
    meal_plan_type         TINYINT       NOT NULL DEFAULT 0 COMMENT '식단표 타입. 0 일반식, 1 가성비식단, 2 요양원맞춤식단, 3 테마식단, 4 다이어트식단, 5 프리미엄식단',
    calories_per_serving   DECIMAL(10,2) NULL COMMENT '거래처 메뉴 1인분 기준 열량(kcal)',
    del_yn                 CHAR(1)       NOT NULL DEFAULT 'N' COMMENT '논리 삭제 여부. Y/N',
    created_at             DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '등록일시',
    mod_at                 DATETIME      NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '수정일시',
    user_id                VARCHAR(45)   NULL COMMENT '최종 처리 사용자 ID',
    PRIMARY KEY (account_id, menu_id),
    KEY idx_account_menu_active (account_id, del_yn),
    KEY idx_account_menu_plan_type (account_id, meal_plan_type, del_yn),
    CONSTRAINT chk_account_menu_meal_plan_type CHECK (meal_plan_type BETWEEN 0 AND 5),
    CONSTRAINT chk_account_menu_calories CHECK (calories_per_serving IS NULL OR calories_per_serving >= 0),
    CONSTRAINT fk_account_menu_standard
        FOREIGN KEY (menu_id) REFERENCES tb_menu_master (menu_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='거래처가 사용하도록 등록한 메뉴';

/* ========================================================================== */
/* 3. 거래처 메뉴 레시피 상세                                                 */
/* ========================================================================== */

CREATE TABLE tb_account_recipe_detail (
    account_recipe_detail_id BIGINT        NOT NULL AUTO_INCREMENT COMMENT '거래처 레시피 식재료 행 식별자',
    account_id               VARCHAR(45)   NOT NULL COMMENT '거래처 식별자',
    recipe_id                BIGINT        NOT NULL COMMENT '레시피 식별자',
    menu_id                  VARCHAR(20)   NOT NULL COMMENT '거래처 메뉴 식별자',
    ingredient_id            VARCHAR(20)   NOT NULL COMMENT '표준 식재료 식별자. 공급처 상품과 분리',
    ingredient_seq           INT           NOT NULL DEFAULT 1 COMMENT '레시피 내 식재료 표시 순서',
    ingredient_name_raw      VARCHAR(255)  NULL COMMENT '등록 당시 원본 식재료명',
    qty_raw                  VARCHAR(50)   NULL COMMENT '사용자가 입력한 원본 수량 문자열',
    qty_num                  DECIMAL(13,3) NULL COMMENT '원본 단위 기준 숫자 수량',
    qty_unit                 VARCHAR(30)   NULL COMMENT '사용자가 입력한 원본 단위',
    recipe_yield_servings    DECIMAL(13,3) NOT NULL DEFAULT 1 COMMENT '레시피 전체 수량이 만드는 인분 수',
    qty_base                 DECIMAL(13,3) NOT NULL COMMENT '기준단위로 환산한 레시피 전체 필요량',
    base_unit                VARCHAR(30)   NOT NULL COMMENT '계산 기준단위. g/ml/EA 등',
    qty_per_person           DECIMAL(13,3) NOT NULL COMMENT '1인 필요량. qty_base / recipe_yield_servings',
    review_flag              TINYINT       NOT NULL DEFAULT 0 COMMENT '수량 또는 단위 검토 필요 여부. 0/1',
    created_at               DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '등록일시',
    mod_at                   DATETIME      NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '수정일시',
    user_id                  VARCHAR(45)   NULL COMMENT '최종 처리 사용자 ID',
    PRIMARY KEY (account_recipe_detail_id),
    UNIQUE KEY uk_account_recipe_ingredient_seq
        (account_id, recipe_id, menu_id, ingredient_seq),
    KEY idx_account_recipe_menu (account_id, menu_id),
    KEY idx_account_recipe_ingredient (account_id, ingredient_id),
    CONSTRAINT fk_account_recipe_menu
        FOREIGN KEY (account_id, menu_id)
        REFERENCES tb_account_menu_master (account_id, menu_id),
    CONSTRAINT fk_account_recipe_ingredient
        FOREIGN KEY (account_id, ingredient_id)
        REFERENCES tb_account_ingredient_master (account_id, ingredient_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='거래처 메뉴의 1인분 기준 정규화 레시피 식재료';

/* ========================================================================== */
/* 4. 식단표와 끼니 운영정보                                                  */
/* ========================================================================== */

CREATE TABLE tb_account_table_meals (
    table_id               VARCHAR(100) NOT NULL COMMENT '식단표 식별자',
    account_id             VARCHAR(45)  NOT NULL COMMENT '거래처 식별자',
    account_name           VARCHAR(100) NULL COMMENT '생성 당시 거래처명 스냅샷',
    table_name             VARCHAR(255) NULL COMMENT '식단표명',
    table_year             INT          NOT NULL COMMENT '식단표 연도',
    table_month            INT          NOT NULL COMMENT '식단표 월',
    table_week             INT          NOT NULL COMMENT '식단표 주차',
    meal_plan_type         TINYINT      NOT NULL DEFAULT 0 COMMENT '식단표 타입. 0 일반식, 1 가성비식단, 2 요양원맞춤식단, 3 테마식단, 4 다이어트식단, 5 프리미엄식단',
    status                 VARCHAR(20)  NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/CONFIRMED/CANCELED',
    created_at             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '등록일시',
    mod_at                 DATETIME     NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '수정일시',
    user_id                VARCHAR(45)  NULL COMMENT '최종 처리 사용자 ID',
    PRIMARY KEY (table_id),
    KEY idx_table_meals_account_period (account_id, table_year, table_month, table_week),
    KEY idx_table_meals_plan_type (account_id, meal_plan_type, table_year, table_month),
    CONSTRAINT chk_table_meals_plan_type CHECK (meal_plan_type BETWEEN 0 AND 5)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='거래처 식단표 헤더';

CREATE TABLE tb_account_meal_service (
    meal_service_id        BIGINT        NOT NULL AUTO_INCREMENT COMMENT '날짜·끼니 운영 식별자',
    table_id               VARCHAR(100)  NOT NULL COMMENT '식단표 식별자',
    account_id             VARCHAR(45)   NOT NULL COMMENT '거래처 식별자',
    meal_date              DATE          NOT NULL COMMENT '식사 제공일자',
    weekday                VARCHAR(10)   NULL COMMENT '요일 표시값',
    meal_slot              VARCHAR(30)   NOT NULL COMMENT 'BREAKFAST/LUNCH/DINNER/SNACK 등 식사 구분',
    planned_servings       DECIMAL(13,3) NOT NULL DEFAULT 1 COMMENT '예상 식수 또는 제공 인분 수',
    actual_servings        DECIMAL(13,3) NULL COMMENT '실제 제공 인분 수',
    meal_budget_per_person DECIMAL(15,2) NOT NULL COMMENT '해당 끼니의 1인 식단가',
    status                 VARCHAR(20)   NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/CONFIRMED/SERVED/CANCELED',
    created_at             DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '등록일시',
    mod_at                 DATETIME      NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '수정일시',
    user_id                VARCHAR(45)   NULL COMMENT '최종 처리 사용자 ID',
    PRIMARY KEY (meal_service_id),
    UNIQUE KEY uk_meal_service (table_id, meal_date, meal_slot),
    KEY idx_meal_service_account_date (account_id, meal_date),
    CONSTRAINT fk_meal_service_table
        FOREIGN KEY (table_id) REFERENCES tb_account_table_meals (table_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='식단표 날짜·끼니별 식수와 1인 식단가';

CREATE TABLE tb_account_table_meals_detail (
    meal_detail_id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '식단 메뉴 구성 행 식별자',
    meal_service_id        BIGINT       NOT NULL COMMENT '날짜·끼니 운영 식별자',
    table_id               VARCHAR(100) NOT NULL COMMENT '식단표 식별자. 조회 편의를 위한 중복 참조',
    account_id             VARCHAR(45)  NOT NULL COMMENT '거래처 식별자',
    sort_order             INT          NOT NULL DEFAULT 0 COMMENT '끼니 내 메뉴 표시 순서',
    menu_id                VARCHAR(20)  NOT NULL COMMENT '거래처 메뉴 식별자',
    menu_name              VARCHAR(255) NOT NULL COMMENT '식단 구성 당시 메뉴명 스냅샷',
    food_type              TINYINT      NULL COMMENT '음식 유형 코드 스냅샷',
    menu_type              INT          NULL COMMENT '주메뉴·부메뉴 등 메뉴 타입 스냅샷',
    menu_gubun             INT          NULL COMMENT '밥·국·무침 등 메뉴 구분 스냅샷',
    created_at             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '등록일시',
    mod_at                 DATETIME     NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '수정일시',
    user_id                VARCHAR(45)  NULL COMMENT '최종 처리 사용자 ID',
    PRIMARY KEY (meal_detail_id),
    UNIQUE KEY uk_meal_service_menu (meal_service_id, menu_id),
    UNIQUE KEY uk_meal_service_sort (meal_service_id, sort_order),
    KEY idx_meal_detail_table (table_id),
    CONSTRAINT fk_meal_detail_service
        FOREIGN KEY (meal_service_id) REFERENCES tb_account_meal_service (meal_service_id),
    CONSTRAINT fk_meal_detail_account_menu
        FOREIGN KEY (account_id, menu_id)
        REFERENCES tb_account_menu_master (account_id, menu_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='끼니별 메뉴 구성';

CREATE TABLE tb_account_meal_menu_cost_snapshot (
    meal_menu_cost_id      BIGINT        NOT NULL AUTO_INCREMENT COMMENT '메뉴 원가 스냅샷 식별자',
    meal_service_id        BIGINT        NOT NULL COMMENT '날짜·끼니 운영 식별자',
    menu_id                VARCHAR(20)   NOT NULL COMMENT '거래처 메뉴 식별자',
    sort_order             INT           NOT NULL DEFAULT 0 COMMENT '끼니 내 메뉴 표시 순서',
    cost_per_person        DECIMAL(15,4) NOT NULL DEFAULT 0 COMMENT '계산 당시 메뉴 1인 원가',
    total_cost             DECIMAL(15,2) NOT NULL DEFAULT 0 COMMENT '메뉴 1인 원가 × 예상 식수',
    calculated_at          DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '원가 계산일시',
    PRIMARY KEY (meal_menu_cost_id),
    UNIQUE KEY uk_meal_menu_cost (meal_service_id, menu_id),
    CONSTRAINT fk_meal_menu_cost_service
        FOREIGN KEY (meal_service_id) REFERENCES tb_account_meal_service (meal_service_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='식단 생성·확정 당시 메뉴 원가 스냅샷';

CREATE TABLE tb_account_meal_ingredient_requirement (
    requirement_id             BIGINT        NOT NULL AUTO_INCREMENT COMMENT '식단 식재료 필요량 식별자',
    meal_service_id            BIGINT        NOT NULL COMMENT '날짜·끼니 운영 식별자',
    menu_id                    VARCHAR(20)   NOT NULL COMMENT '필요량을 발생시킨 메뉴 식별자',
    ingredient_id              VARCHAR(20)   NOT NULL COMMENT '표준 식재료 식별자',
    supplier_product_id        BIGINT        NULL COMMENT '원가 또는 발주에 선택한 공급처 상품',
    qty_per_person             DECIMAL(13,3) NOT NULL COMMENT '기준단위 기준 1인 필요량',
    planned_servings           DECIMAL(13,3) NOT NULL COMMENT '계산 당시 예상 식수',
    total_required_qty         DECIMAL(15,3) NOT NULL COMMENT '1인 필요량 × 예상 식수',
    base_unit                  VARCHAR(30)   NOT NULL COMMENT '필요량 기준단위',
    package_price_snapshot     DECIMAL(15,2) NULL COMMENT '계산 당시 발주단위 1개 가격',
    package_base_qty_snapshot  DECIMAL(13,3) NULL COMMENT '계산 당시 한 포장의 기준단위 총수량',
    calculated_cost            DECIMAL(15,4) NULL COMMENT '해당 식단에 필요한 식재료 계산 원가',
    calculated_at              DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '계산일시',
    PRIMARY KEY (requirement_id),
    UNIQUE KEY uk_meal_menu_ingredient (meal_service_id, menu_id, ingredient_id),
    KEY idx_requirement_ingredient (ingredient_id),
    CONSTRAINT fk_requirement_service
        FOREIGN KEY (meal_service_id) REFERENCES tb_account_meal_service (meal_service_id),
    CONSTRAINT fk_requirement_product
        FOREIGN KEY (supplier_product_id)
        REFERENCES tb_supplier_ingredient_product (supplier_product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='식단 확정 당시 메뉴별 식재료 필요량과 원가 근거';

/* ========================================================================== */
/* 6. 공급처 상품 단위 재고 잔액과 입출고 이력                               */
/* ========================================================================== */

CREATE TABLE tb_account_inventory_balance (
    inventory_balance_id          BIGINT        NOT NULL AUTO_INCREMENT COMMENT '재고 잔액 식별자',
    account_id                    VARCHAR(45)   NOT NULL COMMENT '거래처 식별자',
    account_ingredient_product_id BIGINT        NOT NULL COMMENT '거래처가 사용하는 공급처 상품 식별자',
    location_id                   VARCHAR(40)   NOT NULL DEFAULT 'L999' COMMENT '보관 위치 식별자',
    current_base_qty              DECIMAL(15,3) NOT NULL DEFAULT 0 COMMENT '기준단위로 환산한 현재고',
    base_unit                     VARCHAR(30)   NOT NULL COMMENT '재고 기준단위. g/ml/EA 등',
    current_unit                  VARCHAR(30)   NULL COMMENT '현재고 입력·표시 단위. NULL이면 base_unit 사용',
    current_qty                   DECIMAL(15,3) GENERATED ALWAYS AS (
                                      CASE
                                        WHEN LOWER(base_unit)='g' AND LOWER(current_unit)='kg' THEN current_base_qty/1000
                                        WHEN LOWER(base_unit)='ml' AND LOWER(current_unit)='l' THEN current_base_qty/1000
                                        ELSE current_base_qty
                                      END
                                  ) STORED COMMENT '현재고 표시단위 환산 수량',
    last_movement_at              DATETIME      NULL COMMENT '마지막 입출고 또는 조정일시',
    note                          VARCHAR(300)  NULL COMMENT '재고 메모',
    created_at                    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '등록일시',
    mod_at                        DATETIME      NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '수정일시',
    user_id                       VARCHAR(45)   NULL COMMENT '최종 처리 사용자 ID',
    PRIMARY KEY (inventory_balance_id),
    UNIQUE KEY uk_inventory_product_location
        (account_id, account_ingredient_product_id, location_id),
    KEY idx_inventory_account (account_id),
    CONSTRAINT fk_inventory_account_product
        FOREIGN KEY (account_ingredient_product_id)
        REFERENCES tb_account_ingredient_product (account_ingredient_product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='거래처·공급처상품·보관위치별 현재 재고';

CREATE TABLE tb_account_inventory_movement (
    movement_id                   VARCHAR(40)   NOT NULL COMMENT '재고 입출고 이력 식별자. UUID 사용 가능',
    account_id                    VARCHAR(45)   NOT NULL COMMENT '거래처 식별자',
    account_ingredient_product_id BIGINT        NOT NULL COMMENT '거래처가 사용하는 공급처 상품 식별자',
    location_id                   VARCHAR(40)   NOT NULL DEFAULT 'L999' COMMENT '입출고가 발생한 보관 위치',
    movement_type                 VARCHAR(20)   NOT NULL COMMENT 'IN/OUT/ADJUST/RETURN/DISCARD',
    quantity_delta                DECIMAL(15,3) NOT NULL COMMENT '증감 수량. 입고는 양수, 출고는 음수',
    quantity_before               DECIMAL(15,3) NOT NULL COMMENT '처리 직전 기준단위 재고',
    quantity_after                DECIMAL(15,3) NOT NULL COMMENT '처리 직후 기준단위 재고',
    base_unit                     VARCHAR(30)   NOT NULL COMMENT '수량 기준단위',
    reference_type                VARCHAR(30)   NULL COMMENT 'PURCHASE_ORDER/MEAL_SERVICE/MANUAL 등 근거 유형',
    reference_id                  VARCHAR(100)  NULL COMMENT '발주·끼니 등 원본 업무 식별자',
    movement_at                   DATETIME      NOT NULL COMMENT '실제 재고 증감일시',
    created_at                    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '이력 등록일시',
    user_id                       VARCHAR(45)   NULL COMMENT '처리 사용자 ID',
    PRIMARY KEY (movement_id),
    UNIQUE KEY uk_inventory_movement_reference
        (account_id, reference_type, reference_id, account_ingredient_product_id, movement_type),
    KEY idx_inventory_movement_usage
        (account_id, account_ingredient_product_id, movement_at),
    CONSTRAINT fk_movement_account_product
        FOREIGN KEY (account_ingredient_product_id)
        REFERENCES tb_account_ingredient_product (account_ingredient_product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='실제 재고 입고·사용·조정 이력. 평균 실제 사용량 계산 근거';

/* ========================================================================== */
/* 7. 외부발주 전 발주 대기 목록                                              */
/* ========================================================================== */

CREATE TABLE tb_account_procurement_cart (
    procurement_cart_id    BIGINT        NOT NULL AUTO_INCREMENT COMMENT '발주 대기 목록 식별자',
    account_id             VARCHAR(45)   NOT NULL COMMENT '거래처 식별자',
    status                 VARCHAR(20)   NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/ORDERED/CANCELED',
    requested_delivery_date DATE         NULL COMMENT '희망 납품일',
    note                   VARCHAR(1000) NULL COMMENT '발주 메모',
    created_at             DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '등록일시',
    mod_at                 DATETIME      NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '수정일시',
    user_id                VARCHAR(45)   NULL COMMENT '최종 처리 사용자 ID',
    PRIMARY KEY (procurement_cart_id),
    KEY idx_procurement_cart_account (account_id, status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='식단 또는 재고관리에서 생성한 외부발주 전 대기 목록';

CREATE TABLE tb_account_procurement_cart_item (
    procurement_cart_item_id       BIGINT        NOT NULL AUTO_INCREMENT COMMENT '발주 대기 품목 식별자',
    procurement_cart_id            BIGINT        NOT NULL COMMENT '발주 대기 목록 식별자',
    account_ingredient_product_id  BIGINT        NOT NULL COMMENT '거래처가 사용하는 공급처 상품 식별자',
    source_type                    VARCHAR(20)   NOT NULL COMMENT '생성 출처. MEAL_PLAN/INVENTORY/MANUAL',
    source_id                      VARCHAR(100)  NULL COMMENT '식단표 또는 기타 발주 근거 식별자',
    required_base_qty              DECIMAL(15,3) NOT NULL DEFAULT 0 COMMENT '발주 판단에 사용한 총 필요량',
    current_base_qty               DECIMAL(15,3) NOT NULL DEFAULT 0 COMMENT '발주 판단 당시 현재고',
    safe_stock_base_qty            DECIMAL(15,3) NOT NULL DEFAULT 0 COMMENT '발주 판단 당시 안전재고',
    shortage_base_qty              DECIMAL(15,3) NOT NULL DEFAULT 0 COMMENT '필요량+안전재고-현재고의 부족량',
    suggested_order_qty            DECIMAL(15,3) NOT NULL DEFAULT 0 COMMENT '포장 및 발주배수를 반영한 추천수량',
    confirmed_order_qty            DECIMAL(15,3) NULL COMMENT '사용자가 확정한 발주단위 주문수량',
    unit_price_snapshot            DECIMAL(15,2) NULL COMMENT '후보 생성 당시 발주단위 가격',
    stock_status                  VARCHAR(10)   NULL COMMENT '후보 생성 당시 RED/ORANGE/YELLOW/GREEN',
    created_at                    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '등록일시',
    mod_at                        DATETIME      NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '수정일시',
    PRIMARY KEY (procurement_cart_item_id),
    UNIQUE KEY uk_cart_product_source
        (procurement_cart_id, account_ingredient_product_id, source_type, source_id),
    CONSTRAINT fk_cart_item_cart
        FOREIGN KEY (procurement_cart_id)
        REFERENCES tb_account_procurement_cart (procurement_cart_id),
    CONSTRAINT fk_cart_item_account_product
        FOREIGN KEY (account_ingredient_product_id)
        REFERENCES tb_account_ingredient_product (account_ingredient_product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='발주 대기 목록의 공급처별 상품';

/* ========================================================================== */
/* 8. 외부 공급처 실제 발주                                                   */
/* ========================================================================== */

CREATE TABLE tb_account_purchase_order (
    purchase_order_id      VARCHAR(40)   NOT NULL COMMENT '발주 식별자. UUID 사용 가능',
    account_id             VARCHAR(45)   NOT NULL COMMENT '거래처 식별자',
    supplier_id            BIGINT        NOT NULL COMMENT '발주 대상 공급처 식별자',
    procurement_cart_id    BIGINT        NULL COMMENT '발주로 전환한 발주 대기 목록 식별자',
    sold_to                VARCHAR(20)   NULL COMMENT '공급처 거래처 코드. 웰스토리 soldTo 등',
    client_ord             VARCHAR(50)   NOT NULL COMMENT '클라이언트 발주번호. 중복 주문 방지 키',
    requested_delivery_date DATE         NOT NULL COMMENT '희망 납품일',
    status                 VARCHAR(20)   NOT NULL COMMENT 'DRAFT/ORDERED/CONFIRMED/PARTIAL_RECEIVED/RECEIVED/NOT_RECEIVED/FAILED/CANCELED',
    total_amount           DECIMAL(15,2) NOT NULL DEFAULT 0 COMMENT '발주 당시 총 발주금액',
    supplier_result_code   VARCHAR(20)   NULL COMMENT '공급처 API 결과 코드',
    supplier_result_message VARCHAR(500) NULL COMMENT '공급처 API 결과 메시지',
    client_note            VARCHAR(1000) NULL COMMENT '발주 메모',
    ordered_at             DATETIME      NULL COMMENT '외부 발주 요청일시',
    confirmed_at           DATETIME      NULL COMMENT '공급처 발주 확인일시',
    received_at            DATETIME      NULL COMMENT '전체 입고 완료일시',
    created_at             DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '등록일시',
    mod_at                 DATETIME      NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '수정일시',
    user_id                VARCHAR(45)   NULL COMMENT '최종 처리 사용자 ID',
    PRIMARY KEY (purchase_order_id),
    UNIQUE KEY uk_purchase_order_client (supplier_id, client_ord),
    KEY idx_purchase_order_account (account_id, created_at),
    KEY idx_purchase_order_status (account_id, status),
    CONSTRAINT fk_purchase_order_supplier
        FOREIGN KEY (supplier_id) REFERENCES tb_supplier (supplier_id),
    CONSTRAINT fk_purchase_order_cart
        FOREIGN KEY (procurement_cart_id) REFERENCES tb_account_procurement_cart (procurement_cart_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='거래처의 공급처별 실제 발주 헤더';

CREATE TABLE tb_account_purchase_order_item (
    purchase_order_item_id         BIGINT        NOT NULL AUTO_INCREMENT COMMENT '발주 품목 내부 식별자',
    purchase_order_id              VARCHAR(40)   NOT NULL COMMENT '발주 식별자',
    client_ord_item                VARCHAR(10)   NOT NULL COMMENT '공급처 API에 전달한 발주 품목 순번',
    account_ingredient_product_id  BIGINT        NOT NULL COMMENT '거래처가 사용하는 공급처 상품 식별자',
    procurement_cart_item_id       BIGINT        NULL COMMENT '원본 발주 대기 품목 식별자',
    menu_id                        VARCHAR(20)   NULL COMMENT '발주 필요량을 발생시킨 대표 메뉴. 여러 메뉴 합산이면 NULL 가능',
    supplier_item_code             VARCHAR(50)   NOT NULL COMMENT '발주 당시 공급처 상품코드 스냅샷',
    order_qty                      DECIMAL(15,3) NOT NULL COMMENT '발주단위 기준 주문수량',
    order_unit                     VARCHAR(30)   NOT NULL COMMENT '발주단위',
    base_qty_per_order_unit        DECIMAL(13,3) NOT NULL COMMENT '발주단위 1개에 포함된 기준단위 수량',
    base_unit                      VARCHAR(30)   NOT NULL COMMENT '재고 반영 기준단위',
    unit_price_snapshot            DECIMAL(15,2) NOT NULL COMMENT '발주 당시 발주단위 1개 가격',
    line_amount                    DECIMAL(15,2) NOT NULL COMMENT '발주 품목 금액. order_qty × unit_price_snapshot',
    received_order_qty             DECIMAL(15,3) NOT NULL DEFAULT 0 COMMENT '발주단위 기준 누적 입고수량',
    item_status                    VARCHAR(20)   NOT NULL DEFAULT 'ORDERED' COMMENT 'ORDERED/CONFIRMED/PARTIAL_RECEIVED/RECEIVED/FAILED/CANCELED',
    supplier_result_code           VARCHAR(20)   NULL COMMENT '공급처 품목 처리 결과 코드',
    supplier_error_message         VARCHAR(500)  NULL COMMENT '공급처 품목 오류 메시지',
    created_at                     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '등록일시',
    mod_at                         DATETIME      NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '수정일시',
    PRIMARY KEY (purchase_order_item_id),
    UNIQUE KEY uk_purchase_order_item (purchase_order_id, client_ord_item),
    KEY idx_purchase_item_product (account_ingredient_product_id),
    CONSTRAINT fk_purchase_item_order
        FOREIGN KEY (purchase_order_id) REFERENCES tb_account_purchase_order (purchase_order_id),
    CONSTRAINT fk_purchase_item_account_product
        FOREIGN KEY (account_ingredient_product_id)
        REFERENCES tb_account_ingredient_product (account_ingredient_product_id),
    CONSTRAINT fk_purchase_item_cart_item
        FOREIGN KEY (procurement_cart_item_id)
        REFERENCES tb_account_procurement_cart_item (procurement_cart_item_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='실제 발주의 공급처 상품별 품목';

SET FOREIGN_KEY_CHECKS = 1;

/*
 * 이 스크립트 적용 후 현재 Mapper도 함께 변경해야 한다.
 * 주요 변경:
 * - ingredient_id 재고 조회 -> account_ingredient_product_id 조회
 * - serving_qty -> tb_account_meal_service.planned_servings
 * - welstory_item_code -> supplier_item_code
 * - received_qty -> received_order_qty
 * - req_delivery_date CHAR(8) -> requested_delivery_date DATE
 * - reconciled_at -> confirmed_at
 */



/* ========================================================================== */
/* 고객사별 자동 식단 구성 및 단가 계산 확장                                 */
/* ========================================================================== */

/*
 * 고객사별 식사 구성 및 날짜별 식단 편성 확장
 * 대상: the_full_order (MySQL 8.x / MariaDB 10.5+)
 *
 * 주의
 * - 이 파일은 기존 데이터를 삭제하지 않는 증분 마이그레이션이다.
 * - 고객사 유형/금액 원본은 the_full.tb_account, the_full.tb_account_info이다.
 * - 단가 계산은 API 트랜잭션에서 수행하고 실제 식단에는 계산 근거를 스냅샷으로 저장한다.
 */

USE the_full_order;

CREATE TABLE tb_meal_composition_template (
    template_id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '식사 구성 템플릿 식별자',
    account_id             VARCHAR(45)  NOT NULL COMMENT '고객사 식별자',
    template_name          VARCHAR(100) NOT NULL COMMENT '예: 한식 기본 4찬, 프리미엄 중식',
    food_type              TINYINT      NOT NULL DEFAULT 1 COMMENT '1 한식, 2 중식, 3 일식, 4 양식, 5 분식, 6 간식, 7 기타',
    meal_plan_type         TINYINT      NOT NULL DEFAULT 0 COMMENT '0 일반식, 1 가성비, 2 요양원맞춤, 3 테마, 4 다이어트, 5 프리미엄',
    active_yn              CHAR(1)      NOT NULL DEFAULT 'Y',
    created_at             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    mod_at                 DATETIME     NULL ON UPDATE CURRENT_TIMESTAMP,
    user_id                VARCHAR(45)  NULL,
    PRIMARY KEY (template_id),
    UNIQUE KEY uk_meal_template_name (account_id, template_name),
    KEY idx_meal_template_account (account_id, active_yn),
    CONSTRAINT chk_meal_template_food_type CHECK (food_type BETWEEN 1 AND 7),
    CONSTRAINT chk_meal_template_plan_type CHECK (meal_plan_type BETWEEN 0 AND 5),
    CONSTRAINT chk_meal_template_active CHECK (active_yn IN ('Y', 'N'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='고객사별 재사용 가능한 식사 구성 템플릿';

CREATE TABLE tb_meal_composition_template_item (
    template_item_id       BIGINT       NOT NULL AUTO_INCREMENT,
    template_id            BIGINT       NOT NULL,
    component_name         VARCHAR(50)  NOT NULL COMMENT '밥, 국, 주찬, 부찬, 후식 등 화면 표시명',
    menu_type              TINYINT      NOT NULL COMMENT '0 주메뉴, 1 부메뉴, 2 후식/간식, 3 음료, 4 기타',
    required_count         TINYINT      NOT NULL DEFAULT 1 COMMENT '자동 생성 수량',
    required_yn            CHAR(1)      NOT NULL DEFAULT 'Y' COMMENT '후보 부족 시 오류 여부',
    sort_order             TINYINT      NOT NULL,
    created_at             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    mod_at                 DATETIME     NULL ON UPDATE CURRENT_TIMESTAMP,
    user_id                VARCHAR(45)  NULL,
    PRIMARY KEY (template_item_id),
    UNIQUE KEY uk_template_item_order (template_id, sort_order),
    CONSTRAINT fk_template_item_template FOREIGN KEY (template_id)
        REFERENCES tb_meal_composition_template (template_id) ON DELETE CASCADE,
    CONSTRAINT chk_template_item_menu_type CHECK (menu_type BETWEEN 0 AND 4),
    CONSTRAINT chk_template_item_count CHECK (required_count BETWEEN 0 AND 10),
    CONSTRAINT chk_template_item_required CHECK (required_yn IN ('Y', 'N'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='식사 템플릿의 메뉴 구성 항목';

CREATE TABLE tb_meal_composition_item_gubun (
    template_item_id       BIGINT      NOT NULL,
    menu_gubun             TINYINT     NOT NULL COMMENT '메뉴구분 0~18',
    priority               TINYINT     NOT NULL DEFAULT 1 COMMENT '작을수록 우선 선택',
    PRIMARY KEY (template_item_id, menu_gubun),
    CONSTRAINT fk_template_item_gubun FOREIGN KEY (template_item_id)
        REFERENCES tb_meal_composition_template_item (template_item_id) ON DELETE CASCADE,
    CONSTRAINT chk_template_item_gubun CHECK (menu_gubun BETWEEN 0 AND 18)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='구성 항목에서 허용하는 메뉴구분';

CREATE TABLE tb_account_meal_slot_profile (
    account_id             VARCHAR(45) NOT NULL,
    meal_slot_code         TINYINT     NOT NULL,
    default_food_type      TINYINT     NOT NULL DEFAULT 1,
    default_meal_plan_type TINYINT     NOT NULL DEFAULT 0,
    template_id            BIGINT      NULL,
    budget_override        DECIMAL(15,2) NULL COMMENT '예외 단가. NULL이면 고객사 유형별 자동 계산',
    created_at             DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    mod_at                 DATETIME    NULL ON UPDATE CURRENT_TIMESTAMP,
    user_id                VARCHAR(45) NULL,
    PRIMARY KEY (account_id, meal_slot_code),
    KEY idx_account_slot_template (template_id),
    CONSTRAINT fk_account_slot_profile_setting FOREIGN KEY (account_id, meal_slot_code)
        REFERENCES tb_account_meal_slot_setting (account_id, meal_slot_code) ON DELETE CASCADE,
    CONSTRAINT fk_account_slot_profile_template FOREIGN KEY (template_id)
        REFERENCES tb_meal_composition_template (template_id),
    CONSTRAINT chk_account_slot_food_type CHECK (default_food_type BETWEEN 1 AND 7),
    CONSTRAINT chk_account_slot_plan_type CHECK (default_meal_plan_type BETWEEN 0 AND 5),
    CONSTRAINT chk_account_slot_budget CHECK (budget_override IS NULL OR budget_override >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='고객사 식사구분별 기본 식사분류·식단유형·구성';

CREATE TABLE tb_account_meal_schedule_override (
    override_id            BIGINT      NOT NULL AUTO_INCREMENT,
    account_id             VARCHAR(45) NOT NULL,
    meal_date              DATE        NOT NULL,
    meal_slot_code         TINYINT     NOT NULL,
    food_type              TINYINT     NOT NULL,
    meal_plan_type         TINYINT     NOT NULL DEFAULT 0,
    template_id            BIGINT      NULL,
    budget_override        DECIMAL(15,2) NULL,
    note                    VARCHAR(255) NULL,
    created_at              DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    mod_at                  DATETIME    NULL ON UPDATE CURRENT_TIMESTAMP,
    user_id                 VARCHAR(45) NULL,
    PRIMARY KEY (override_id),
    UNIQUE KEY uk_account_meal_date_slot (account_id, meal_date, meal_slot_code),
    CONSTRAINT fk_schedule_override_slot FOREIGN KEY (meal_slot_code)
        REFERENCES tb_meal_slot_master (meal_slot_code),
    CONSTRAINT fk_schedule_override_template FOREIGN KEY (template_id)
        REFERENCES tb_meal_composition_template (template_id),
    CONSTRAINT chk_schedule_override_food_type CHECK (food_type BETWEEN 1 AND 7),
    CONSTRAINT chk_schedule_override_plan_type CHECK (meal_plan_type BETWEEN 0 AND 5),
    CONSTRAINT chk_schedule_override_budget CHECK (budget_override IS NULL OR budget_override >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='특정 날짜·식사구분의 고객사 기본 구성 예외';

ALTER TABLE tb_account_meal_service
    ADD COLUMN meal_slot_code TINYINT NULL AFTER meal_slot,
    ADD COLUMN food_type TINYINT NULL AFTER meal_slot_code,
    ADD COLUMN meal_plan_type TINYINT NOT NULL DEFAULT 0 AFTER food_type,
    ADD COLUMN template_id BIGINT NULL AFTER meal_plan_type,
    ADD COLUMN budget_source VARCHAR(20) NOT NULL DEFAULT 'AUTO' AFTER meal_budget_per_person,
    ADD COLUMN budget_source_amount DECIMAL(15,2) NULL AFTER budget_source,
    ADD COLUMN budget_divisor TINYINT NULL AFTER budget_source_amount,
    ADD KEY idx_meal_service_slot_profile (account_id, meal_date, meal_slot_code),
    ADD CONSTRAINT fk_meal_service_slot FOREIGN KEY (meal_slot_code)
        REFERENCES tb_meal_slot_master (meal_slot_code),
    ADD CONSTRAINT fk_meal_service_template FOREIGN KEY (template_id)
        REFERENCES tb_meal_composition_template (template_id),
    ADD CONSTRAINT chk_meal_service_food_type CHECK (food_type IS NULL OR food_type BETWEEN 1 AND 7),
    ADD CONSTRAINT chk_meal_service_plan_type CHECK (meal_plan_type BETWEEN 0 AND 5),
    ADD CONSTRAINT chk_meal_service_budget_divisor CHECK (budget_divisor IS NULL OR budget_divisor BETWEEN 1 AND 3);

/*
 * 서버 단가 계산 규칙(클라이언트 입력값을 신뢰하지 말 것)
 *
 * SELECT a.account_type, ai.elderly, ai.snack, ai.diet_price
 *   FROM the_full.tb_account a
 *   JOIN the_full.tb_account_info ai ON ai.account_id = a.account_id
 *  WHERE a.account_id = :account_id;
 *
 * main_meal_count = 활성 meal_slot_code 중 (0 조식, 2 중식, 5 석식)의 개수
 * 1 요양원: 본식 = elderly / main_meal_count, 간식(1,3,4,6,7,8) = snack
 * 4 산업체: 본식 = diet_price (나누지 않음), 간식은 별도 정책/예외단가가 없으면 저장 차단
 * 5 학교  : 본식 = diet_price / main_meal_count, 간식·후식은 diet_price 총액에 포함되므로 별도 가산 없음
 * 그 외   : budget_override 또는 별도 정책이 없으면 자동 생성 차단
 *
 * ROUND(..., 2)를 사용하고 main_meal_count=0, NULL, 음수 금액은 검증 오류로 처리한다.
 */


/* ========================================================================== */
/* 고객사별 자동 식단 구성 및 단가 계산 확장                                 */
/* ========================================================================== */

/* 고객사 유형별 식사 단가 조회 뷰 */
USE the_full_order;

CREATE OR REPLACE VIEW vw_account_meal_slot_budget AS
SELECT
    s.account_id,
    a.account_type,
    s.meal_slot_code,
    m.meal_slot_name,
    counts.main_meal_count,
    CASE
        WHEN p.budget_override IS NOT NULL THEN p.budget_override
        WHEN a.account_type = 1 AND s.meal_slot_code IN (0, 2, 5)
            THEN ROUND(ai.elderly / NULLIF(counts.main_meal_count, 0), 2)
        WHEN a.account_type = 1 AND s.meal_slot_code IN (1, 3, 4, 6, 7, 8)
            THEN ai.snack
        WHEN a.account_type = 4 AND s.meal_slot_code IN (0, 2, 5)
            THEN ai.diet_price
        WHEN a.account_type = 5 AND s.meal_slot_code IN (0, 2, 5)
            THEN ROUND(ai.diet_price / NULLIF(counts.main_meal_count, 0), 2)
        ELSE NULL
    END AS budget_per_person,
    CASE
        WHEN p.budget_override IS NOT NULL THEN 'OVERRIDE'
        WHEN a.account_type = 1 AND s.meal_slot_code IN (0, 2, 5) THEN 'ELDERLY_DIVIDED'
        WHEN a.account_type = 1 THEN 'SNACK'
        WHEN a.account_type = 4 AND s.meal_slot_code IN (0, 2, 5) THEN 'DIET_PRICE_EACH'
        WHEN a.account_type = 5 AND s.meal_slot_code IN (0, 2, 5) THEN 'DIET_PRICE_DIVIDED'
        WHEN a.account_type = 5 THEN 'INCLUDED_IN_DIET_PRICE'
        ELSE 'POLICY_REQUIRED'
    END AS budget_source,
    CASE
        WHEN p.budget_override IS NOT NULL THEN p.budget_override
        WHEN a.account_type = 1 AND s.meal_slot_code IN (0, 2, 5) THEN ai.elderly
        WHEN a.account_type = 1 THEN ai.snack
        WHEN a.account_type IN (4, 5) THEN ai.diet_price
        ELSE NULL
    END AS budget_source_amount,
    CASE
        WHEN a.account_type IN (1, 5) AND s.meal_slot_code IN (0, 2, 5)
            THEN counts.main_meal_count
        ELSE NULL
    END AS budget_divisor,
    p.default_food_type,
    p.default_meal_plan_type,
    p.template_id
FROM tb_account_meal_slot_setting s
JOIN tb_meal_slot_master m
  ON m.meal_slot_code = s.meal_slot_code
JOIN the_full.tb_account a
  ON a.account_id = s.account_id
JOIN the_full.tb_account_info ai
  ON ai.account_id = s.account_id
LEFT JOIN tb_account_meal_slot_profile p
  ON p.account_id = s.account_id
 AND p.meal_slot_code = s.meal_slot_code
JOIN (
    SELECT
        account_id,
        SUM(CASE WHEN meal_slot_code IN (0, 2, 5) THEN 1 ELSE 0 END) AS main_meal_count
    FROM tb_account_meal_slot_setting
    GROUP BY account_id
) counts
  ON counts.account_id = s.account_id;

/* API 조회 예시
SELECT *
  FROM vw_account_meal_slot_budget
 WHERE account_id = :account_id
 ORDER BY meal_slot_code;
*/

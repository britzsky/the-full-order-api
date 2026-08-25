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

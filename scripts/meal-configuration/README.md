# 고객사 식사 구성 DB 적용 파일

## 신규 DB에 전체 생성 — 권장

- 로컬: `order_ddl_local_all_in_one.sql`
- 실환경 collation 기준: `the_full_order_schema_all_in_one.sql`

두 파일에는 기존 전체 DROP/CREATE, 고객사 식사 구성 테이블, 식단 서비스 확장,
단가 계산 뷰가 모두 포함되어 있습니다. `SOURCE`나 다른 보조 파일 없이 하나만 실행합니다.

전체 DDL은 기존 관련 테이블을 삭제하고 다시 생성하므로 신규/초기화 환경에만 사용합니다.

## 기존 운영 DB에 증분 적용

기존 데이터를 유지해야 하면 전체 DDL을 실행하지 말고 다음 두 파일만 순서대로 실행합니다.

1. `account-meal-configuration-migration.sql`
2. `account-meal-budget-view.sql`

## 테스트 데이터

통합 DDL 실행 후 환경에 맞는 seed 파일 하나만 실행합니다.

- 로컬: `order_insert_local_200_account_20250819193455.sql`
- 실환경: `the_full_order_seed_testdata_200_account_20250819193455.sql`

두 파일은 고객사 `20250819193455`, 메뉴 200개, 식사 구성 템플릿과 기본 프로필을 생성합니다.
고객사가 `the_full.tb_account`와 `the_full.tb_account_info`에 먼저 존재해야 단가 뷰를 통해 식단 서비스가 생성됩니다.

## 단가 정책

- `account_type=1`: 본식은 `elderly / 활성 조식·중식·석식 수`, 간식류는 `snack`
- `account_type=4`: 본식 각각 `diet_price`
- `account_type=5`: 본식은 `diet_price / 활성 조식·중식·석식 수`; 간식·후식은 별도 예산을 더하지 않고 포함 상태로 반환
- 분모 0, NULL/음수 금액, 지원하지 않는 고객사 유형은 API에서 저장·자동 생성을 차단

학교 간식은 별도 단가가 아니므로 뷰의 `budget_per_person`이 NULL이고 `budget_source`가 `INCLUDED_IN_DIET_PRICE`입니다. 메뉴 원가를 어느 본식에 합산할지는 업무 규칙에 따라 API에서 연결해야 합니다.

## 백엔드 필수 작업

- `GET /Account/MealConfiguration`: `vw_account_meal_slot_budget`와 프로필을 조회
- `POST /Account/MealConfigurationSave`: 식사구분과 프로필을 한 트랜잭션으로 교체/UPSERT
- `POST /v2/meal-plans/services`: 단가를 서버에서 다시 계산한 후 계산 근거까지 스냅샷 저장
- 클라이언트가 보낸 `meal_budget_per_person`은 신뢰하지 않음

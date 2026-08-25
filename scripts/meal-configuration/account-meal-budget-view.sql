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

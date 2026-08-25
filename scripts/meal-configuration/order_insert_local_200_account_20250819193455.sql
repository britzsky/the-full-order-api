/*
 * 식단·원가·재고·발주 통합 테스트 데이터
 * 대상: scripts/rebuild_modified_tables_drop_create.sql 적용 직후의 MySQL/MariaDB
 * 규모: 메뉴 200개, 메뉴당 식재료 3개, 공급처 2개, 표준 식재료 30개
 * 테스트 거래처: 20250819193455
 *
 * 주의: 동일한 TEST_* 데이터가 있으면 먼저 삭제하고 다시 생성한다.
 */

USE the_full_order;
SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

DROP TEMPORARY TABLE IF EXISTS tmp_seed_menu;
DROP TEMPORARY TABLE IF EXISTS tmp_seed_ingredient;

CREATE TEMPORARY TABLE tmp_seed_menu (
    menu_no INT NOT NULL PRIMARY KEY,
    menu_name VARCHAR(255) NOT NULL,
    meal_plan_type TINYINT NOT NULL,
    calories_per_serving DECIMAL(10,2) NOT NULL,
    menu_type INT NOT NULL,
    menu_gubun INT NOT NULL,
    food_type TINYINT NOT NULL DEFAULT 1
);

INSERT INTO tmp_seed_menu
(menu_no, menu_name, meal_plan_type, calories_per_serving, menu_type, menu_gubun)
VALUES
(1,'쌀밥',0,310,0,0),(2,'잡곡밥',0,295,0,0),(3,'소고기미역국',0,145,1,1),(4,'돼지고기김치찌개',0,240,0,1),
(5,'된장찌개',0,180,1,1),(6,'닭볶음탕',0,360,0,8),(7,'고등어구이',0,285,0,4),(8,'제육볶음',0,390,0,9),
(9,'소불고기',0,410,0,9),(10,'계란찜',0,155,1,5),(11,'두부조림',0,190,1,8),(12,'시금치나물',0,75,1,10),
(13,'콩나물무침',0,65,1,2),(14,'배추김치',0,35,1,14),(15,'멸치볶음',0,125,1,9),(16,'감자조림',0,160,1,8),(17,'잔치국수',0,420,0,7),
(18,'가성비콩나물밥',1,340,0,0),(19,'가성비카레라이스',1,510,0,0),(20,'가성비짜장밥',1,535,0,0),(21,'가성비김치볶음밥',1,490,0,0),
(22,'가성비어묵국',1,120,1,1),(23,'가성비계란국',1,105,1,1),(24,'가성비두부된장국',1,135,1,1),(25,'가성비닭갈비',1,335,0,9),
(26,'가성비돈육장조림',1,280,0,8),(27,'가성비생선까스',1,320,0,3),(28,'가성비두부구이',1,170,1,4),(29,'가성비감자채볶음',1,145,1,9),
(30,'가성비무생채',1,55,1,2),(31,'가성비오이무침',1,45,1,2),(32,'가성비양배추샐러드',1,80,1,11),(33,'가성비요구르트',1,95,2,13),(34,'가성비떡볶이',1,355,2,12),
(35,'요양원흰죽',2,220,0,0),(36,'요양원소고기죽',2,285,0,0),(37,'요양원전복죽',2,260,0,0),(38,'요양원단호박죽',2,230,0,0),
(39,'요양원연두부국',2,115,1,1),(40,'요양원들깨미역국',2,135,1,1),(41,'요양원순살닭찜',2,245,0,5),(42,'요양원순살생선조림',2,210,0,8),
(43,'요양원두부완자',2,185,0,5),(44,'요양원부드러운계란찜',2,140,1,5),(45,'요양원감자으깸',2,120,1,10),(46,'요양원애호박볶음',2,85,1,9),
(47,'요양원무나물',2,65,1,10),(48,'요양원시금치된장무침',2,70,1,2),(49,'요양원바나나퓨레',2,105,2,13),(50,'요양원고구마샐러드',2,165,1,11),(51,'요양원두유',2,130,3,16),
(52,'봄나물비빔밥',3,485,0,0),(53,'여름열무국수',3,430,0,7),(54,'가을버섯영양밥',3,455,0,0),(55,'겨울굴국밥',3,520,0,0),
(56,'정월대보름오곡밥',3,465,0,0),(57,'초복삼계탕',3,690,0,1),(58,'추석소갈비찜',3,720,0,5),(59,'설날떡국',3,530,0,1),
(60,'어린이날함박스테이크',3,610,0,4),(61,'크리스마스로스트치킨',3,650,0,4),(62,'벚꽃유부초밥',3,410,0,0),(63,'여름수박화채',3,180,2,13),
(64,'가을단호박전',3,220,1,6),(65,'겨울호빵',3,240,2,12),(66,'복날오리백숙',3,675,0,1),(67,'김장수육',3,590,0,5),(68,'생일미역국정식',3,560,0,1),
(69,'다이어트현미밥',4,260,0,0),(70,'다이어트곤약밥',4,190,0,0),(71,'다이어트닭가슴살샐러드',4,280,0,11),(72,'다이어트연어샐러드',4,310,0,11),
(73,'다이어트두부샐러드',4,220,0,11),(74,'다이어트소고기채소볶음',4,330,0,9),(75,'다이어트닭가슴살스테이크',4,295,0,4),(76,'다이어트흰살생선구이',4,235,0,4),
(77,'다이어트토마토수프',4,125,1,1),(78,'다이어트양배추수프',4,110,1,1),(79,'다이어트버섯수프',4,135,1,1),(80,'다이어트계란흰자찜',4,95,1,5),
(81,'다이어트브로콜리무침',4,70,1,2),(82,'다이어트구운채소',4,115,1,4),(83,'다이어트그릭요거트',4,145,2,13),(84,'다이어트고구마',4,180,1,13),
(85,'프리미엄한우불고기',5,540,0,9),(86,'프리미엄한우갈비찜',5,760,0,5),(87,'프리미엄전복갈비탕',5,680,0,1),(88,'프리미엄장어구이',5,620,0,4),
(89,'프리미엄연어스테이크',5,510,0,4),(90,'프리미엄대하구이',5,395,0,4),(91,'프리미엄전복버터구이',5,430,0,4),(92,'프리미엄낙지볶음',5,455,0,9),
(93,'프리미엄해물누룽지탕',5,480,0,1),(94,'프리미엄버섯솥밥',5,445,0,0),(95,'프리미엄영양돌솥밥',5,490,0,0),(96,'프리미엄모둠전',5,520,1,6),
(97,'프리미엄잡채',5,390,1,9),(98,'프리미엄훈제오리샐러드',5,410,0,11),(99,'프리미엄과일플래터',5,210,2,13),(100,'프리미엄수제티라미수',5,380,2,13);

/* 기존 100개를 기반으로 식사분류가 다양한 메뉴 101~200을 추가한다. */
DROP TEMPORARY TABLE IF EXISTS tmp_seed_menu_base;
CREATE TEMPORARY TABLE tmp_seed_menu_base AS
SELECT * FROM tmp_seed_menu WHERE menu_no BETWEEN 1 AND 100;

INSERT INTO tmp_seed_menu
(menu_no,menu_name,meal_plan_type,calories_per_serving,menu_type,menu_gubun,food_type)
SELECT menu_no+100,CONCAT(menu_name,' 2'),meal_plan_type,calories_per_serving,menu_type,menu_gubun,MOD(menu_no-1,6)+1
FROM tmp_seed_menu_base;

DROP TEMPORARY TABLE tmp_seed_menu_base;

CREATE TEMPORARY TABLE tmp_seed_ingredient (
    ingredient_no INT NOT NULL PRIMARY KEY,
    ingredient_id VARCHAR(20) NOT NULL,
    ingredient_name VARCHAR(255) NOT NULL,
    category_name VARCHAR(100) NOT NULL,
    base_unit VARCHAR(30) NOT NULL,
    package_base_qty DECIMAL(13,3) NOT NULL,
    welstory_price DECIMAL(15,2) NOT NULL
);

INSERT INTO tmp_seed_ingredient
(ingredient_no,ingredient_id,ingredient_name,category_name,base_unit,package_base_qty,welstory_price)
VALUES
(1,'TIG001','쌀','곡류','g',20000,80000),(2,'TIG002','현미','곡류','g',10000,52000),(3,'TIG003','소고기','육류','g',5000,115000),
(4,'TIG004','돼지고기','육류','g',5000,62000),(5,'TIG005','닭고기','육류','g',5000,48000),(6,'TIG006','고등어','수산물','g',5000,55000),
(7,'TIG007','연어','수산물','g',5000,98000),(8,'TIG008','두부','두류','g',3000,12000),(9,'TIG009','감자','채소류','g',10000,28000),
(10,'TIG010','양파','채소류','g',10000,24000),(11,'TIG011','대파','채소류','g',5000,18000),(12,'TIG012','당근','채소류','g',10000,26000),
(13,'TIG013','애호박','채소류','g',5000,22000),(14,'TIG014','양배추','채소류','g',10000,21000),(15,'TIG015','시금치','채소류','g',4000,24000),
(16,'TIG016','콩나물','채소류','g',5000,13500),(17,'TIG017','무','채소류','g',10000,19000),(18,'TIG018','오이','채소류','g',5000,20000),
(19,'TIG019','버섯','버섯류','g',3000,27000),(20,'TIG020','브로콜리','채소류','g',5000,31000),(21,'TIG021','김치','김치류','g',10000,39000),
(22,'TIG022','된장','장류','g',5000,26000),(23,'TIG023','고추장','장류','g',5000,29000),(24,'TIG024','간장','장류','ml',10000,23000),
(25,'TIG025','참기름','유지류','ml',1800,28000),(26,'TIG026','식용유','유지류','ml',18000,42000),(27,'TIG027','밀가루','곡류','g',10000,21000),
(28,'TIG028','국수면','면류','g',10000,33000),(29,'TIG029','고구마','서류','g',10000,34000),(30,'TIG030','단호박','채소류','g',5000,25000);

/* 기존 테스트 데이터 정리 */
DELETE FROM tb_account_meal_schedule_override WHERE account_id='20250819193455';
DELETE FROM tb_account_meal_slot_profile WHERE account_id='20250819193455';
DELETE FROM tb_account_meal_slot_setting WHERE account_id='20250819193455';
DELETE FROM tb_meal_composition_template WHERE account_id='20250819193455';
DELETE FROM tb_account_purchase_order_item WHERE purchase_order_id LIKE 'TPO-%';
DELETE FROM tb_account_purchase_order WHERE purchase_order_id LIKE 'TPO-%';
DELETE FROM tb_account_procurement_cart_item WHERE source_id LIKE 'TBL-TEST-%';
DELETE FROM tb_account_procurement_cart WHERE account_id='20250819193455';
DELETE FROM tb_account_inventory_movement WHERE account_id='20250819193455';
DELETE FROM tb_account_inventory_balance WHERE account_id='20250819193455';
DELETE FROM tb_account_meal_ingredient_requirement WHERE meal_service_id IN (SELECT meal_service_id FROM tb_account_meal_service WHERE account_id='20250819193455');
DELETE FROM tb_account_meal_menu_cost_snapshot WHERE meal_service_id IN (SELECT meal_service_id FROM tb_account_meal_service WHERE account_id='20250819193455');
DELETE FROM tb_account_table_meals_detail WHERE account_id='20250819193455';
DELETE FROM tb_account_meal_service WHERE account_id='20250819193455';
DELETE FROM tb_account_table_meals WHERE account_id='20250819193455';
DELETE FROM tb_account_recipe_detail WHERE account_id='20250819193455';
DELETE FROM tb_account_menu_master WHERE account_id='20250819193455';
DELETE FROM tb_account_inventory_balance WHERE account_id='20250819193455';
DELETE FROM tb_account_ingredient_product WHERE account_id='20250819193455';
DELETE FROM tb_account_ingredient_master WHERE account_id='20250819193455';
DELETE FROM tb_supplier_ingredient_price_history WHERE supplier_product_id IN (SELECT supplier_product_id FROM tb_supplier_ingredient_product WHERE supplier_item_code LIKE 'TEST-%');
DELETE FROM tb_supplier_ingredient_product WHERE supplier_item_code LIKE 'TEST-%';
DELETE FROM tb_supplier WHERE supplier_code IN ('TEST_WELSTORY','TEST_OURHOME');
DELETE FROM tb_recipe_detail WHERE menu_id LIKE 'TMENU%';
DELETE FROM tb_menu_master WHERE menu_id LIKE 'TMENU%';
DELETE FROM tb_ingredient_master WHERE ingredient_id LIKE 'TIG%';

/* 고객사 자동 식단 기본 구성: 조식·중식·오후간식·석식을 사용한다. */
INSERT INTO tb_account_meal_slot_setting(account_id,meal_slot_code,display_order,user_id) VALUES
('20250819193455',0,1,'SEED'),('20250819193455',2,2,'SEED'),('20250819193455',3,3,'SEED'),('20250819193455',5,4,'SEED');

INSERT INTO tb_meal_composition_template(account_id,template_name,food_type,meal_plan_type,user_id)
VALUES ('20250819193455','한식 기본 4찬',1,0,'SEED');
SET @seed_template_id=LAST_INSERT_ID();

INSERT INTO tb_meal_composition_template_item(template_id,component_name,menu_type,required_count,required_yn,sort_order,user_id) VALUES
(@seed_template_id,'밥',0,1,'Y',1,'SEED'),(@seed_template_id,'국',1,1,'Y',2,'SEED'),
(@seed_template_id,'주찬',0,1,'Y',3,'SEED'),(@seed_template_id,'부찬',1,2,'Y',4,'SEED');
SET @seed_rice_item=(SELECT template_item_id FROM tb_meal_composition_template_item WHERE template_id=@seed_template_id AND sort_order=1);
SET @seed_soup_item=(SELECT template_item_id FROM tb_meal_composition_template_item WHERE template_id=@seed_template_id AND sort_order=2);
SET @seed_main_item=(SELECT template_item_id FROM tb_meal_composition_template_item WHERE template_id=@seed_template_id AND sort_order=3);
SET @seed_side_item=(SELECT template_item_id FROM tb_meal_composition_template_item WHERE template_id=@seed_template_id AND sort_order=4);
INSERT INTO tb_meal_composition_item_gubun(template_item_id,menu_gubun,priority) VALUES
(@seed_rice_item,0,1),(@seed_soup_item,1,1),
(@seed_main_item,3,1),(@seed_main_item,4,2),(@seed_main_item,5,3),(@seed_main_item,8,4),(@seed_main_item,9,5),
(@seed_side_item,2,1),(@seed_side_item,6,2),(@seed_side_item,10,3),(@seed_side_item,11,4),(@seed_side_item,14,5),(@seed_side_item,17,6);

INSERT INTO tb_account_meal_slot_profile(account_id,meal_slot_code,default_food_type,default_meal_plan_type,template_id,user_id) VALUES
('20250819193455',0,1,0,@seed_template_id,'SEED'),('20250819193455',2,1,0,@seed_template_id,'SEED'),
('20250819193455',3,6,0,NULL,'SEED'),('20250819193455',5,1,0,@seed_template_id,'SEED');

/* 표준 식재료 */
INSERT INTO tb_ingredient_master
(ingredient_id,ingredient_name_raw,ingredient_name_std,category_name,base_unit,needs_review,note,created_at,safe_stock_qty)
SELECT ingredient_id,ingredient_name,ingredient_name,category_name,base_unit,0,'200개 메뉴 테스트 데이터',NOW(),0
FROM tmp_seed_ingredient;

/* 공급처, 공급처 상품, 가격 */
INSERT INTO tb_supplier (supplier_code,supplier_name,supplier_type,active_yn,user_id)
VALUES ('TEST_WELSTORY','테스트 웰스토리','FOOD','Y','SEED'),('TEST_OURHOME','테스트 아워홈','FOOD','Y','SEED');

INSERT INTO tb_supplier_ingredient_product
(supplier_id,ingredient_id,supplier_item_code,product_name,order_unit,package_qty,package_unit,base_qty,base_unit,minimum_order_qty,order_multiple_qty,active_yn,user_id)
SELECT s.supplier_id,i.ingredient_id,
       CONCAT('TEST-',CASE WHEN s.supplier_code='TEST_WELSTORY' THEN 'W-' ELSE 'A-' END,LPAD(i.ingredient_no,3,'0')),
       CONCAT(s.supplier_name,' ',i.ingredient_name),
       'BOX',1,i.base_unit,i.package_base_qty,i.base_unit,1,1,'Y','SEED'
FROM tmp_seed_ingredient i CROSS JOIN tb_supplier s
WHERE s.supplier_code IN ('TEST_WELSTORY','TEST_OURHOME');

INSERT INTO tb_supplier_ingredient_price_history
(supplier_product_id,purchase_price,effective_from,effective_to,user_id)
SELECT p.supplier_product_id,
       ROUND(i.welstory_price * CASE WHEN s.supplier_code='TEST_WELSTORY' THEN 1 ELSE 1.08 END,2),
       '2026-01-01 00:00:00',NULL,'SEED'
FROM tb_supplier_ingredient_product p
JOIN tb_supplier s ON s.supplier_id=p.supplier_id
JOIN tmp_seed_ingredient i ON i.ingredient_id=p.ingredient_id
WHERE p.supplier_item_code LIKE 'TEST-%';

/* 거래처 식재료와 사용 상품. 웰스토리를 기본 상품으로 지정 */
INSERT INTO tb_account_ingredient_master
(account_id,ingredient_id,ingredient_name_raw,ingredient_name_std,category_name,base_unit,storage_type,needs_review,note,active_yn,user_id)
SELECT '20250819193455',ingredient_id,ingredient_name,ingredient_name,category_name,base_unit,'REFRIGERATED',0,'통합 테스트 데이터','Y','SEED'
FROM tmp_seed_ingredient;

INSERT INTO tb_account_ingredient_product
(account_id,supplier_product_id,preferred_yn,safe_stock_base_qty,max_stock_base_qty,lead_time_days,default_location_id,active_yn,user_id)
SELECT '20250819193455',p.supplier_product_id,
       CASE WHEN s.supplier_code='TEST_WELSTORY' THEN 'Y' ELSE 'N' END,
       p.base_qty*0.10,p.base_qty*2,CASE WHEN s.supplier_code='TEST_WELSTORY' THEN 1 ELSE 2 END,'L999','Y','SEED'
FROM tb_supplier_ingredient_product p JOIN tb_supplier s ON s.supplier_id=p.supplier_id
WHERE p.supplier_item_code LIKE 'TEST-%';

/* 공통 메뉴 200개 */
INSERT INTO tb_menu_master
(menu_id,source_row,menu_name_raw,menu_name,food_type,food_type_reason,food_type_confidence,menu_img,menu_type,menu_gubun,meal_plan_type,calories_per_serving,del_yn,user_id)
SELECT CONCAT('TMENU',LPAD(menu_no,3,'0')),menu_no,menu_name,menu_name,food_type,'테스트 데이터','HIGH',NULL,menu_type,menu_gubun,meal_plan_type,calories_per_serving,'N','SEED'
FROM tmp_seed_menu;

/* 메뉴 마스터 표준 레시피: 메뉴마다 서로 다른 식재료 3개 */
INSERT INTO tb_recipe_detail
(recipe_id,menu_id,ingredient_id,ingredient_seq,ingredient_name_raw,qty_raw,qty_num,qty_unit,recipe_yield_servings,qty_base,base_unit,qty_per_person,review_flag,user_id)
SELECT 900000+m.menu_no,CONCAT('TMENU',LPAD(m.menu_no,3,'0')),
       i.ingredient_id,o.seq,i.ingredient_name,
       CONCAT(CASE o.seq WHEN 1 THEN 180 WHEN 2 THEN 60 ELSE 12 END,i.base_unit),
       CASE o.seq WHEN 1 THEN 180 WHEN 2 THEN 60 ELSE 12 END,i.base_unit,
       1,CASE o.seq WHEN 1 THEN 180 WHEN 2 THEN 60 ELSE 12 END,i.base_unit,
       CASE o.seq WHEN 1 THEN 180 WHEN 2 THEN 60 ELSE 12 END,0,'SEED'
FROM tmp_seed_menu m
CROSS JOIN (SELECT 1 seq,0 offset_value UNION ALL SELECT 2,7 UNION ALL SELECT 3,14) o
JOIN tmp_seed_ingredient i ON i.ingredient_no=MOD(m.menu_no+o.offset_value-1,30)+1;

/* 거래처 메뉴 등록 */
INSERT INTO tb_account_menu_master
(account_id,menu_id,menu_name_raw,menu_name,food_type,food_type_reason,menu_type,menu_gubun,meal_plan_type,calories_per_serving,del_yn,user_id)
SELECT '20250819193455',CONCAT('TMENU',LPAD(menu_no,3,'0')),menu_name,menu_name,food_type,'테스트 데이터',menu_type,menu_gubun,meal_plan_type,calories_per_serving,'N','SEED'
FROM tmp_seed_menu;

/* 거래처 메뉴 등록 시 메뉴 마스터 표준 레시피를 업장별 레시피로 복사 */
INSERT INTO tb_account_recipe_detail
(account_id,recipe_id,menu_id,ingredient_id,ingredient_seq,ingredient_name_raw,qty_raw,qty_num,qty_unit,recipe_yield_servings,qty_base,base_unit,qty_per_person,review_flag,user_id)
SELECT '20250819193455',r.recipe_id,r.menu_id,r.ingredient_id,r.ingredient_seq,
       r.ingredient_name_raw,r.qty_raw,r.qty_num,r.qty_unit,r.recipe_yield_servings,
       r.qty_base,r.base_unit,r.qty_per_person,r.review_flag,'SEED'
FROM tb_recipe_detail r
JOIN tb_account_menu_master am ON am.account_id='20250819193455' AND am.menu_id=r.menu_id
WHERE r.menu_id LIKE 'TMENU%';

/* 공급처 상품별 초기 재고와 최초 입고 이력 */
INSERT INTO tb_account_inventory_balance
(account_id,account_ingredient_product_id,location_id,current_unit,current_base_qty,base_unit,last_movement_at,note,user_id)
SELECT x.account_id,x.account_ingredient_product_id,'L999',
       CASE WHEN LOWER(x.base_unit)='g' THEN 'kg' WHEN LOWER(x.base_unit)='ml' THEN 'L' ELSE x.base_unit END,
       x.current_base_qty,x.base_unit,NOW(),'단위환산·재고상태 테스트 초기재고','SEED'
FROM (
    SELECT '20250819193455' account_id,ap.account_ingredient_product_id,p.base_unit,
           CASE
             WHEN ap.preferred_yn='N' THEN p.base_qty*0.15
             WHEN CAST(RIGHT(p.supplier_item_code,3) AS UNSIGNED) BETWEEN 1 AND 5 THEN 0
             WHEN CAST(RIGHT(p.supplier_item_code,3) AS UNSIGNED) BETWEEN 6 AND 15 THEN p.base_qty*0.10
             ELSE p.base_qty*10.00
           END current_base_qty
    FROM tb_account_ingredient_product ap
    JOIN tb_supplier_ingredient_product p ON p.supplier_product_id=ap.supplier_product_id
    WHERE ap.account_id='20250819193455'
) x;

INSERT INTO tb_account_inventory_movement
(movement_id,account_id,account_ingredient_product_id,location_id,movement_type,quantity_delta,quantity_before,quantity_after,base_unit,reference_type,reference_id,movement_at,user_id)
SELECT CONCAT('MOV-SEED-',inventory_balance_id),'20250819193455',account_ingredient_product_id,location_id,'IN',current_base_qty,0,current_base_qty,base_unit,'MANUAL','INITIAL-SEED',NOW(),'SEED'
FROM tb_account_inventory_balance WHERE account_id='20250819193455';

/* 타입별 식단표와 끼니 */
INSERT INTO tb_account_table_meals
(table_id,account_id,account_name,table_name,table_year,table_month,table_week,meal_plan_type,status,user_id)
SELECT CONCAT('TBL-TEST-',meal_plan_type),'20250819193455','테스트 거래처',
       CONCAT(CASE meal_plan_type WHEN 0 THEN '일반식' WHEN 1 THEN '가성비식단' WHEN 2 THEN '요양원맞춤식단' WHEN 3 THEN '테마식단' WHEN 4 THEN '다이어트식단' ELSE '프리미엄식단' END,' 테스트 식단표'),
       YEAR(CURRENT_DATE),MONTH(CURRENT_DATE),1,meal_plan_type,'CONFIRMED','SEED'
FROM (SELECT DISTINCT meal_plan_type FROM tmp_seed_menu) t;

INSERT INTO tb_account_meal_service
(table_id,account_id,meal_date,weekday,meal_slot,meal_slot_code,food_type,meal_plan_type,template_id,planned_servings,actual_servings,meal_budget_per_person,budget_source,budget_source_amount,budget_divisor,status,user_id)
SELECT CONCAT('TBL-TEST-',t.meal_plan_type),'20250819193455',DATE_SUB(CURRENT_DATE,INTERVAL t.meal_plan_type DAY),'화','2',2,1,t.meal_plan_type,p.template_id,
       100,NULL,b.budget_per_person,b.budget_source,b.budget_source_amount,b.budget_divisor,'CONFIRMED','SEED'
FROM (SELECT DISTINCT meal_plan_type FROM tmp_seed_menu) t
JOIN vw_account_meal_slot_budget b ON b.account_id='20250819193455' AND b.meal_slot_code=2
LEFT JOIN tb_account_meal_slot_profile p ON p.account_id=b.account_id AND p.meal_slot_code=b.meal_slot_code;

INSERT INTO tb_account_table_meals_detail
(meal_service_id,table_id,account_id,sort_order,menu_id,menu_name,food_type,menu_type,menu_gubun,user_id)
SELECT s.meal_service_id,s.table_id,'20250819193455',m.menu_no,CONCAT('TMENU',LPAD(m.menu_no,3,'0')),m.menu_name,m.food_type,m.menu_type,m.menu_gubun,'SEED'
FROM tmp_seed_menu m JOIN tb_account_meal_service s
  ON s.account_id='20250819193455' AND s.table_id=CONCAT('TBL-TEST-',m.meal_plan_type);

/* 필요량과 원가 스냅샷 */
INSERT INTO tb_account_meal_ingredient_requirement
(meal_service_id,menu_id,ingredient_id,supplier_product_id,qty_per_person,planned_servings,total_required_qty,base_unit,package_price_snapshot,package_base_qty_snapshot,calculated_cost)
SELECT d.meal_service_id,d.menu_id,r.ingredient_id,p.supplier_product_id,r.qty_per_person,s.planned_servings,
       r.qty_per_person*s.planned_servings,r.base_unit,ph.purchase_price,p.base_qty,
       ROUND(ph.purchase_price*(r.qty_per_person*s.planned_servings)/p.base_qty,4)
FROM tb_account_table_meals_detail d
JOIN tb_account_meal_service s ON s.meal_service_id=d.meal_service_id
JOIN tb_account_recipe_detail r ON r.account_id=d.account_id AND r.menu_id=d.menu_id
JOIN tb_supplier_ingredient_product p ON p.ingredient_id=r.ingredient_id
JOIN tb_supplier sp ON sp.supplier_id=p.supplier_id AND sp.supplier_code='TEST_WELSTORY'
JOIN tb_supplier_ingredient_price_history ph ON ph.supplier_product_id=p.supplier_product_id AND ph.effective_to IS NULL
WHERE d.account_id='20250819193455';

/* 최근 식단 평균사용량의 110%를 안전재고로 저장한다. 화면 예상필요수량은 동일 이력에서 조회 계산된다. */
UPDATE tb_account_ingredient_product ap
JOIN (
    SELECT s.account_id,r.supplier_product_id,ROUND(AVG(r.total_required_qty)*1.10,3) safe_qty
    FROM tb_account_meal_ingredient_requirement r
    JOIN tb_account_meal_service s ON s.meal_service_id=r.meal_service_id
    WHERE s.account_id='20250819193455'
      AND s.meal_date BETWEEN DATE_SUB(CURRENT_DATE,INTERVAL 30 DAY) AND CURRENT_DATE
    GROUP BY s.account_id,r.supplier_product_id
) usage_avg ON usage_avg.account_id=ap.account_id AND usage_avg.supplier_product_id=ap.supplier_product_id
SET ap.safe_stock_base_qty=usage_avg.safe_qty;

INSERT INTO tb_account_meal_menu_cost_snapshot
(meal_service_id,menu_id,sort_order,cost_per_person,total_cost)
SELECT r.meal_service_id,r.menu_id,d.sort_order,
       ROUND(SUM(r.calculated_cost)/MAX(r.planned_servings),4),ROUND(SUM(r.calculated_cost),2)
FROM tb_account_meal_ingredient_requirement r
JOIN tb_account_table_meals_detail d ON d.meal_service_id=r.meal_service_id AND d.menu_id=r.menu_id
JOIN tb_account_meal_service s ON s.meal_service_id=r.meal_service_id AND s.account_id='20250819193455'
GROUP BY r.meal_service_id,r.menu_id,d.sort_order;

/* 타입별 발주 대기 목록과 부족 품목 */
INSERT INTO tb_account_procurement_cart
(account_id,status,requested_delivery_date,note,user_id)
SELECT '20250819193455','DRAFT',DATE_ADD(CURRENT_DATE,INTERVAL meal_plan_type+1 DAY),CONCAT('SEED_CART_TYPE_',meal_plan_type),'SEED'
FROM (SELECT DISTINCT meal_plan_type FROM tmp_seed_menu) t;

INSERT INTO tb_account_procurement_cart_item
(procurement_cart_id,account_ingredient_product_id,source_type,source_id,required_base_qty,current_base_qty,safe_stock_base_qty,shortage_base_qty,suggested_order_qty,confirmed_order_qty,unit_price_snapshot,stock_status)
SELECT c.procurement_cart_id,ap.account_ingredient_product_id,'MEAL_PLAN',s.table_id,
       SUM(r.total_required_qty) AS required_base_qty,
       COALESCE(MAX(b.current_base_qty),0) AS current_base_qty,
       ap.safe_stock_base_qty,
       GREATEST(SUM(r.total_required_qty)+ap.safe_stock_base_qty-COALESCE(MAX(b.current_base_qty),0),0) AS shortage_base_qty,
       CEIL(GREATEST(SUM(r.total_required_qty)+ap.safe_stock_base_qty-COALESCE(MAX(b.current_base_qty),0),0)/p.base_qty) AS suggested_order_qty,
       CEIL(GREATEST(SUM(r.total_required_qty)+ap.safe_stock_base_qty-COALESCE(MAX(b.current_base_qty),0),0)/p.base_qty) AS confirmed_order_qty,
       ph.purchase_price AS unit_price_snapshot,
       CASE WHEN COALESCE(MAX(b.current_base_qty),0) < SUM(r.total_required_qty) THEN 'RED' ELSE 'ORANGE' END AS stock_status
FROM tb_account_meal_ingredient_requirement r
JOIN tb_account_meal_service s ON s.meal_service_id=r.meal_service_id AND s.account_id='20250819193455'
JOIN tb_account_procurement_cart c ON c.account_id=s.account_id AND c.note=CONCAT('SEED_CART_TYPE_',RIGHT(s.table_id,1))
JOIN tb_account_ingredient_product ap ON ap.account_id=s.account_id AND ap.supplier_product_id=r.supplier_product_id
JOIN tb_supplier_ingredient_product p ON p.supplier_product_id=ap.supplier_product_id
JOIN tb_supplier_ingredient_price_history ph ON ph.supplier_product_id=p.supplier_product_id AND ph.effective_to IS NULL
LEFT JOIN tb_account_inventory_balance b ON b.account_id=s.account_id AND b.account_ingredient_product_id=ap.account_ingredient_product_id
GROUP BY c.procurement_cart_id,ap.account_ingredient_product_id,s.table_id,ap.safe_stock_base_qty,p.base_qty,ph.purchase_price
HAVING shortage_base_qty > 0;

/* 샘플 실제 발주 1건과 품목 5건 */
INSERT INTO tb_account_purchase_order
(purchase_order_id,account_id,supplier_id,procurement_cart_id,sold_to,client_ord,requested_delivery_date,status,total_amount,client_note,ordered_at,user_id)
SELECT 'TPO-TEST-001','20250819193455',s.supplier_id,c.procurement_cart_id,'TEST001','TEST-ORDER-001',DATE_ADD(CURRENT_DATE,INTERVAL 1 DAY),'ORDERED',0,'통합 테스트 발주',NOW(),'SEED'
FROM tb_supplier s JOIN tb_account_procurement_cart c ON c.account_id='20250819193455' AND c.note='SEED_CART_TYPE_0'
WHERE s.supplier_code='TEST_WELSTORY';

INSERT INTO tb_account_purchase_order_item
(purchase_order_id,client_ord_item,account_ingredient_product_id,procurement_cart_item_id,menu_id,supplier_item_code,order_qty,order_unit,base_qty_per_order_unit,base_unit,unit_price_snapshot,line_amount,received_order_qty,item_status)
SELECT 'TPO-TEST-001',LPAD(ROW_NUMBER() OVER (ORDER BY ci.procurement_cart_item_id),3,'0'),ci.account_ingredient_product_id,ci.procurement_cart_item_id,NULL,
       p.supplier_item_code,ci.confirmed_order_qty,p.order_unit,p.base_qty,p.base_unit,ci.unit_price_snapshot,
       ci.confirmed_order_qty*ci.unit_price_snapshot,0,'ORDERED'
FROM tb_account_procurement_cart_item ci
JOIN tb_account_procurement_cart c ON c.procurement_cart_id=ci.procurement_cart_id AND c.note='SEED_CART_TYPE_0'
JOIN tb_account_ingredient_product ap ON ap.account_ingredient_product_id=ci.account_ingredient_product_id
JOIN tb_supplier_ingredient_product p ON p.supplier_product_id=ap.supplier_product_id
WHERE ci.confirmed_order_qty > 0
ORDER BY ci.procurement_cart_item_id
LIMIT 5;

UPDATE tb_account_purchase_order o
SET total_amount=(SELECT COALESCE(SUM(line_amount),0) FROM tb_account_purchase_order_item i WHERE i.purchase_order_id=o.purchase_order_id)
WHERE o.purchase_order_id='TPO-TEST-001';

SET FOREIGN_KEY_CHECKS = 1;

/* 생성 건수 확인 */
SELECT 'menu_master' table_name,COUNT(*) row_count FROM tb_menu_master WHERE menu_id LIKE 'TMENU%'
UNION ALL SELECT 'recipe_detail',COUNT(*) FROM tb_recipe_detail WHERE menu_id LIKE 'TMENU%'
UNION ALL SELECT 'account_menu',COUNT(*) FROM tb_account_menu_master WHERE account_id='20250819193455'
UNION ALL SELECT 'account_recipe_detail',COUNT(*) FROM tb_account_recipe_detail WHERE account_id='20250819193455'
UNION ALL SELECT 'inventory',COUNT(*) FROM tb_account_inventory_balance WHERE account_id='20250819193455'
UNION ALL SELECT 'meal_plan',COUNT(*) FROM tb_account_table_meals WHERE account_id='20250819193455'
UNION ALL SELECT 'meal_requirement',COUNT(*) FROM tb_account_meal_ingredient_requirement r JOIN tb_account_meal_service s ON s.meal_service_id=r.meal_service_id WHERE s.account_id='20250819193455'
UNION ALL SELECT 'procurement_cart',COUNT(*) FROM tb_account_procurement_cart WHERE account_id='20250819193455'
UNION ALL SELECT 'purchase_order',COUNT(*) FROM tb_account_purchase_order WHERE account_id='20250819193455';

/* 거래처 재고관리 프로세스 확인: 예상필요·현재고 표시/기준단위·안전재고·부족량·상태 */
WITH recent_usage AS (
    SELECT s.account_id,r.supplier_product_id,AVG(r.total_required_qty) expected_required_qty,MAX(s.meal_date) last_used_at
    FROM tb_account_meal_ingredient_requirement r
    JOIN tb_account_meal_service s ON s.meal_service_id=r.meal_service_id
    WHERE s.account_id='20250819193455'
      AND s.meal_date BETWEEN DATE_SUB(CURRENT_DATE,INTERVAL 30 DAY) AND CURRENT_DATE
    GROUP BY s.account_id,r.supplier_product_id
)
SELECT p.ingredient_id,COALESCE(ai.ingredient_name_std,p.product_name) ingredient_name,
       ROUND(COALESCE(u.expected_required_qty,0),3) expected_required_qty,p.base_unit expected_unit,
       b.current_qty,b.current_unit,b.current_base_qty,b.base_unit,
       ROUND(COALESCE(u.expected_required_qty,0)*1.10,3) safe_stock_qty,p.base_unit safe_stock_unit,
       ROUND(GREATEST(COALESCE(u.expected_required_qty,0)-b.current_base_qty,0),3) shortage_qty,p.base_unit shortage_unit,
       CASE WHEN b.current_base_qty<=0 AND COALESCE(u.expected_required_qty,0)>0 THEN 'RED'
            WHEN b.current_base_qty<COALESCE(u.expected_required_qty,0) THEN 'ORANGE'
            WHEN u.last_used_at IS NULL AND ap.max_stock_base_qty>0 AND b.current_base_qty/ap.max_stock_base_qty<=0.30 THEN 'YELLOW'
            ELSE 'GREEN' END stock_status
FROM tb_account_inventory_balance b
JOIN tb_account_ingredient_product ap ON ap.account_ingredient_product_id=b.account_ingredient_product_id
JOIN tb_supplier_ingredient_product p ON p.supplier_product_id=ap.supplier_product_id
LEFT JOIN tb_account_ingredient_master ai ON ai.account_id=b.account_id AND ai.ingredient_id=p.ingredient_id
LEFT JOIN recent_usage u ON u.account_id=b.account_id AND u.supplier_product_id=p.supplier_product_id
WHERE b.account_id='20250819193455'
ORDER BY FIELD(stock_status,'RED','ORANGE','YELLOW','GREEN'),p.ingredient_id;

DROP TEMPORARY TABLE IF EXISTS tmp_seed_menu;
DROP TEMPORARY TABLE IF EXISTS tmp_seed_ingredient;


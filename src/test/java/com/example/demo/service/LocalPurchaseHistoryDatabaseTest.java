package com.example.demo.service;

import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * 로컬 MySQL에 임시 스키마(tfo_ph_verify)를 운영 DDL로 만들고 구매 이력 SQL을 실제로 돌린 뒤 지운다.
 * 로컬 the_full_order·운영 RDS는 건드리지 않는다. 실행: RUN_PURCHASE_HISTORY_DB=true
 */
@EnabledIfEnvironmentVariable(named = "RUN_PURCHASE_HISTORY_DB", matches = "true")
class LocalPurchaseHistoryDatabaseTest {
	static final String SCHEMA = "tfo_ph_verify";
	static final String NS = "com.example.demo.mapper.PurchaseHistoryMapper.";

	@Test
	void historyImportLinkPricesAndPlanningCostOnRealSchema() throws Exception {
		var root = Path.of("src/main/resources");
		var props = new Properties();
		try (var r = Files.newBufferedReader(root.resolve("application.properties_local"))) { props.load(r); }
		try (var r = Files.newBufferedReader(root.resolve("application-secret.properties"))) { props.load(r); }
		String url = props.getProperty("spring.datasource.url");
		assertThat(url).startsWith("jdbc:mysql://localhost:3306/the_full_order?");
		String server = url.replace("/the_full_order?", "/?allowMultiQueries=true&");
		String user = props.getProperty("spring.datasource.username"), pw = props.getProperty("spring.datasource.password");
		String ddl = Files.readString(Path.of("ddl-source/the_full_order_real_ddl.sql")).replace("the_full_order_real", SCHEMA);
		try (var admin = DriverManager.getConnection(server, user, pw); var st = admin.createStatement()) {
			st.execute("DROP DATABASE IF EXISTS " + SCHEMA);
			st.execute("CREATE DATABASE " + SCHEMA + " DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
			try {
				st.execute(ddl);
				while (st.getMoreResults() || st.getUpdateCount() != -1) { /* 다중 문장 결과 소비 */ }
				try (var c = DriverManager.getConnection(url.replace("/the_full_order?", "/" + SCHEMA + "?"), user, pw)) {
					run(c, root);
				}
			} finally {
				st.execute("DROP DATABASE IF EXISTS " + SCHEMA);
			}
		}
	}

	void run(Connection c, Path root) throws Exception {
		var jdbc = new JdbcTemplate(new SingleConnectionDataSource(c, true));
		var cfg = new org.apache.ibatis.session.Configuration(new org.apache.ibatis.mapping.Environment("ph",
				new org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory(), new SingleConnectionDataSource(c, true)));
		for (String file : List.of("PurchaseHistoryMapper.xml", "MenuMapper.xml", "SupplierIntegrationMapper.xml"))
			try (var in = Files.newInputStream(root.resolve("mybatis-mapper/" + file))) {
				new org.apache.ibatis.builder.xml.XMLMapperBuilder(in, cfg, "mybatis-mapper/" + file, cfg.getSqlFragments()).parse();
			}
		SqlSession s = new org.apache.ibatis.session.SqlSessionFactoryBuilder().build(cfg).openSession(c);

		// 준비: 공급사·사업장·거래처 연결·거래처 식재료(양파, g)
		jdbc.update("INSERT INTO tb_supplier(supplier_code,supplier_name) VALUES('WELSTORY','삼성웰스토리')");
		long supplier = jdbc.queryForObject("SELECT supplier_id FROM tb_supplier WHERE supplier_code='WELSTORY'", Long.class);
		jdbc.update("INSERT INTO tb_supplier_site(supplier_id,external_site_code) VALUES(?,'1000123')", supplier);
		long siteMaster = jdbc.queryForObject("SELECT supplier_site_id FROM tb_supplier_site", Long.class);
		jdbc.update("INSERT INTO tb_supplier_account_site(account_id,supplier_id,supplier_site_id,external_site_code,active_yn) VALUES('A1',?,?,'1000123','Y')", supplier, siteMaster);
		long site = jdbc.queryForObject("SELECT supplier_account_site_id FROM tb_supplier_account_site", Long.class);
		jdbc.update("INSERT INTO tb_account_ingredient_master(account_id,ingredient_id,ingredient_name_std,base_unit) VALUES('A1','ONION','양파','g')");

		assertThat(s.<Map<String, Object>>selectList(NS + "importSites", new HashMap<>(Map.of("account_id", "A1")))).hasSize(1);
		assertThat((String) s.selectOne(NS + "inventoryStartDate", Map.of("account_id", "A1"))).isNull();

		// 단종 상품 등록(판매 중 아님)
		var product = new HashMap<String, Object>(Map.of("supplier_id", supplier, "supplier_item_code", "111", "product_name", "양파 1KG/망",
				"order_unit", "망", "base_qty", new BigDecimal("1000"), "base_unit", "g", "user_id", "T"));
		product.put("alt_base_qty", null); product.put("alt_base_unit", null); product.put("tax_type", null);
		s.insert(NS + "insertHistoryProduct", product);
		s.insert(NS + "insertHistoryProduct", product); // 두 번째는 무시
		Map<String, Object> p = s.selectOne(NS + "productForHistory", Map.of("supplier_id", supplier, "supplier_item_code", "111"));
		long productId = ((Number) p.get("supplier_product_id")).longValue();
		assertThat(jdbc.queryForObject("SELECT active_yn FROM tb_supplier_ingredient_product WHERE supplier_product_id=?", String.class, productId)).isEqualTo("N");

		// 입고 2건(다른 날·다른 단가). 같은 줄을 다시 넣으면 덮어쓴다
		receipt(s, supplier, site, productId, "R1", "2026-10-01", "3", "4500");
		receipt(s, supplier, site, productId, "R2", "2026-10-15", "2", "4000");
		receipt(s, supplier, site, productId, "R2", "2026-10-15", "2", "4400");
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_goods_receipt_item", Integer.class)).isEqualTo(2);
		assertThat(jdbc.queryForObject("SELECT total_supply_amount FROM tb_goods_receipt WHERE provider_receipt_key='R2'", BigDecimal.class)).isEqualByComparingTo("4400");

		List<Map<String, Object>> products = s.selectList(NS + "purchasedProducts", new HashMap<>(Map.of("account_id", "A1")));
		assertThat(products).singleElement().satisfies(r -> {
			assertThat(((Number) r.get("unlinked_line_count")).intValue()).isEqualTo(2);
			assertThat((BigDecimal) r.get("total_supply_amount")).isEqualByComparingTo("8900");
			assertThat(r.get("ingredient_id")).isNull();
		});

		// 식재료 연결 → 원가 대표 상품
		var link = new HashMap<String, Object>(Map.of("account_id", "A1", "supplier_product_id", productId, "ingredient_id", "ONION",
				"base_unit", "g", "per_unit", new BigDecimal("1000"), "only_unlinked", false));
		assertThat(s.update(NS + "linkProductLines", link)).isEqualTo(2);
		// 연결 뒤 공급사 정정(3 → 4망): 기준수량도 같은 비율로
		receipt(s, supplier, site, productId, "R1", "2026-10-01", "4", "6000");
		assertThat(jdbc.queryForObject("SELECT base_received_qty FROM tb_goods_receipt_item WHERE provider_line_key='R1|L'", BigDecimal.class)).isEqualByComparingTo("4000");
		assertThat(s.<Map<String, Object>>selectList(NS + "knownLinks", Map.of("account_id", "A1"))).singleElement()
				.satisfies(k -> assertThat((BigDecimal) k.get("per_unit")).isEqualByComparingTo("1000"));

		List<Map<String, Object>> prices = s.selectList(NS + "ingredientPrices", new HashMap<>(Map.of("account_id", "A1")));
		assertThat(prices).singleElement().satisfies(r -> {
			assertThat((BigDecimal) r.get("weighted_unit_cost")).isEqualByComparingTo("1.733333"); // 10400원 / 6000g
			assertThat((BigDecimal) r.get("min_unit_cost")).isEqualByComparingTo("1.5");
			assertThat((BigDecimal) r.get("max_unit_cost")).isEqualByComparingTo("2.2");
		});
		assertThat(s.<Map<String, Object>>selectList(NS + "ingredientPriceLines", new HashMap<>(Map.of("account_id", "A1", "ingredient_id", "ONION")))).hasSize(2);

		// 사업장 판가가 있으면 원가는 구매 이력으로 연결한 상품의 판가(SITE_PRICE)
		jdbc.update("INSERT INTO tb_supplier_site_product_offer(supplier_site_id,supplier_product_id,active_yn) VALUES(?,?,'Y')", siteMaster, productId);
		long offer = jdbc.queryForObject("SELECT supplier_offer_id FROM tb_supplier_site_product_offer", Long.class);
		jdbc.update("INSERT INTO tb_supplier_site_product_price(supplier_offer_id,purchase_price,effective_from) VALUES(?,1800,NOW() - INTERVAL 1 DAY)", offer);
		Map<String, Object> cost = s.selectOne("com.example.demo.mapper.MenuMapper.ingredientPlanningPrice", Map.of("account_id", "A1", "ingredient_id", "ONION"));
		assertThat(cost.get("cost_source")).isEqualTo("SITE_PRICE");
		assertThat((BigDecimal) cost.get("unit_cost")).isEqualByComparingTo("1.8");

		// 재고 시작일 2026-10-10: 10-01은 HISTORY, 10-15는 UNMATCHED. 입고 목록은 HISTORY를 뺀다
		var setting = new HashMap<String, Object>(Map.of("account_id", "A1", "inventory_start_date", "2026-10-10", "user_id", "T"));
		s.insert(NS + "saveInventorySetting", setting);
		assertThat(s.update(NS + "reclassifyHistory", setting)).isBetween(1, 2); // 드라이버 설정에 따라 바뀐 줄 또는 찾은 줄 수
		assertThat((String) s.selectOne(NS + "inventoryStartDate", Map.of("account_id", "A1"))).isEqualTo("2026-10-10");
		assertThat(jdbc.queryForObject("SELECT status FROM tb_goods_receipt WHERE provider_receipt_key='R2'", String.class)).isEqualTo("UNMATCHED");
		List<Map<String, Object>> list = s.selectList("com.example.demo.mapper.SupplierIntegrationMapper.receipts", new HashMap<>(Map.of("account_id", "A1")));
		assertThat(list).extracting(r -> r.get("provider_receipt_key")).containsExactly("R2");
		assertThat(s.<Map<String, Object>>selectList("com.example.demo.mapper.SupplierIntegrationMapper.receipts",
				new HashMap<>(Map.of("account_id", "A1", "include_history", "Y")))).hasSize(2);

		// 연결 해제(only_unlinked=false, ingredient NULL)
		var unlink = new HashMap<String, Object>(link);
		unlink.put("ingredient_id", null);
		assertThat(s.update(NS + "linkProductLines", unlink)).isEqualTo(2);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_goods_receipt_item WHERE ingredient_id IS NULL", Integer.class)).isEqualTo(2);
	}

	void receipt(SqlSession s, long supplier, long site, long productId, String key, String date, String qty, String supply) {
		String id = UUID.nameUUIDFromBytes(key.getBytes()).toString();
		var h = new HashMap<String, Object>(Map.of("goods_receipt_id", id, "account_id", "A1", "supplier_id", supplier,
				"supplier_account_site_id", site, "provider_receipt_key", key, "receipt_date", date, "status", "HISTORY",
				"receipt_attributes", "{\"source\":\"PURCHASE_HISTORY\"}"));
		s.insert(NS + "upsertHistoryReceipt", h);
		var i = new HashMap<String, Object>(h);
		i.put("supplier_product_id", productId);
		i.put("provider_line_key", key + "|L");
		i.put("ordered_qty", new BigDecimal(qty));
		i.put("received_qty", new BigDecimal(qty));
		i.put("order_unit", "망");
		i.put("base_received_qty", new BigDecimal(qty).multiply(new BigDecimal("1000")));
		i.put("base_unit", "g");
		i.put("unit_price", new BigDecimal(supply).divide(new BigDecimal(qty)));
		i.put("supply_amount", new BigDecimal(supply));
		i.put("vat_amount", BigDecimal.ZERO);
		i.put("total_amount", new BigDecimal(supply));
		i.put("traceability_no", null);
		i.put("traceability_type", null);
		i.put("expiration_date", null);
		i.put("provider_attributes", "{}");
		s.insert(NS + "upsertHistoryReceiptItem", i);
		s.update(NS + "refreshHistoryReceiptTotals", Map.of("goods_receipt_id", id));
	}
}

package com.example.demo.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.sql.*;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 로컬 MySQL에 임시 스키마(tfo_shortage_verify)를 운영 DDL로 만들고 결품 처리·배송 구분·기존 입고 SQL을 실제로 돌린 뒤 지운다.
 * 로컬 the_full_order·운영 RDS는 건드리지 않는다. 웰스토리는 모의 응답. 실행: RUN_PURCHASE_HISTORY_DB=true
 */
@EnabledIfEnvironmentVariable(named = "RUN_PURCHASE_HISTORY_DB", matches = "true")
class LocalShortageDatabaseTest {
	static final String SCHEMA = "tfo_shortage_verify";
	static final String SI = "com.example.demo.mapper.SupplierIntegrationMapper.";
	final ObjectMapper json = new ObjectMapper();

	@Test
	void shortageLifecycleOnRealSchema() throws Exception {
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
				try (var c = DriverManager.getConnection(url.replace("/the_full_order?", "/" + SCHEMA + "?allowMultiQueries=true&"), user, pw)) {
					run(c, root);
				}
			} finally {
				st.execute("DROP DATABASE IF EXISTS " + SCHEMA);
			}
		}
	}

	void run(Connection c, Path root) throws Exception {
		var jdbc = new JdbcTemplate(new SingleConnectionDataSource(c, true));
		var cfg = new org.apache.ibatis.session.Configuration(new org.apache.ibatis.mapping.Environment("sh",
				new org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory(), new SingleConnectionDataSource(c, true)));
		for (String file : List.of("PurchaseHistoryMapper.xml", "SupplierIntegrationMapper.xml", "OrderWorkflowMapper.xml",
				"ShortageProcurementMapper.xml", "MealUsageMapper.xml"))
			try (var in = Files.newInputStream(root.resolve("mybatis-mapper/" + file))) {
				new org.apache.ibatis.builder.xml.XMLMapperBuilder(in, cfg, "mybatis-mapper/" + file, cfg.getSqlFragments()).parse();
			}
		SqlSession s = new org.apache.ibatis.session.SqlSessionFactoryBuilder().build(cfg).openSession(c);
		LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul")), yesterday = today.minusDays(1);

		// 준비: 웰스토리 사업장, 상품 3개(센터·택배·직송), 식재료
		jdbc.update("INSERT INTO tb_supplier(supplier_code,supplier_name) VALUES('WELSTORY','삼성웰스토리')");
		long supplier = jdbc.queryForObject("SELECT supplier_id FROM tb_supplier", Long.class);
		jdbc.update("INSERT INTO tb_supplier_site(supplier_id,external_site_code) VALUES(?,'1000123')", supplier);
		long siteMaster = jdbc.queryForObject("SELECT supplier_site_id FROM tb_supplier_site", Long.class);
		jdbc.update("INSERT INTO tb_supplier_account_site(account_id,supplier_id,supplier_site_id,external_site_code,active_yn) VALUES('A1',?,?,'1000123','Y')", supplier, siteMaster);
		jdbc.update("INSERT INTO tb_account_ingredient_master(account_id,ingredient_id,ingredient_name_std,base_unit) VALUES('A1','ONION','양파','g'),('A1','EGG','계란','EA'),('A1','OIL','기름','ml')");
		long[] products = new long[3];
		String[] codes = { "111", "222", "333" }, deliveries = { "CD", "공급사택배", "VD" };
		for (int i = 0; i < 3; i++) {
			var product = new HashMap<String, Object>(Map.of("supplier_id", supplier, "supplier_item_code", codes[i], "product_name", "상품" + codes[i],
					"order_unit", "EA", "base_qty", BigDecimal.ONE, "base_unit", "EA", "user_id", "T"));
			product.put("alt_base_qty", null); product.put("alt_base_unit", null); product.put("tax_type", null);
			s.insert("com.example.demo.mapper.PurchaseHistoryMapper.insertHistoryProduct", product);
			products[i] = jdbc.queryForObject("SELECT supplier_product_id FROM tb_supplier_ingredient_product WHERE supplier_item_code=?", Long.class, codes[i]);
			jdbc.update("INSERT INTO tb_supplier_site_product_offer(supplier_site_id,supplier_product_id,delivery_type,active_yn) VALUES(?,?,?,'Y')",
					siteMaster, products[i], deliveries[i]);
		}

		// 발주서 P1(어제 납품, 접수 확인): 양파 10(센터), 계란 5(택배), 기름 4(직송). 배송 구분은 비워 두고 백필 SQL로 채운다
		order(jdbc, "P1", "7261119100012301", yesterday, supplier);
		item(jdbc, "P1", "1", products[0], "111", "ONION", "10", "1000");
		item(jdbc, "P1", "2", products[1], "222", "EGG", "5", "300");
		item(jdbc, "P1", "3", products[2], "333", "OIL", "4", "5000");
		String sql = Files.readString(Path.of("docs/SHORTAGE_DB_UPDATE_20261119.sql"));
		String backfill = sql.substring(sql.indexOf("UPDATE tb_account_purchase_order_item oi"), sql.indexOf("SET SQL_SAFE_UPDATES=1;"));
		assertThat(jdbc.update(backfill)).isEqualTo(3);
		assertThat(jdbc.queryForList("SELECT delivery_type FROM tb_account_purchase_order_item ORDER BY client_ord_item", String.class))
				.containsExactly("CENTER", "PARCEL", "DIRECT");

		var welstory = mock(WelstoryItemLookupService.class);
		var service = new ReceivingShortageService(jdbc, welstory, json);

		// 대기 목록: 센터·직송은 납품 다음 날부터 "결품 확인 필요", 택배는 이틀까지 "배송 대기"
		Map<String, String> states = states(service.pending("A1", null));
		assertThat(states).containsEntry("1", "CHECK_NEEDED").containsEntry("2", "WAITING").containsEntry("3", "CHECK_NEEDED");

		// 양파 8개만 입고(기존 입고 SQL) → 부분 입고
		long onion = itemId(jdbc, "P1", "1");
		s.update(SI + "addReceivedOrderQty", Map.of("purchase_order_item_id", onion, "received_qty", new BigDecimal("8")));
		s.update(SI + "refreshPurchaseOrderReceiptStatus", Map.of("purchase_order_id", "P1"));
		assertThat(status(jdbc, "P1")).isEqualTo("PARTIAL_RECEIVED");

		// 남은 양파 2개 결품(수량 생략 = 남은 수량 전부) → 양파 품목 완료, 입고 예정에서 빠짐
		var result = service.register(body("{\"account_id\":\"A1\",\"purchase_order_id\":\"P1\",\"source\":\"SUPPLIER_CONTACT\","
				+ "\"reason\":\"산지 수급 불가\",\"contact_name\":\"웰스토리 김OO\",\"user_id\":\"u1\",\"items\":[{\"client_ord_item\":\"1\"}]}"));
		assertThat(result.get("status")).isEqualTo("PARTIAL_RECEIVED");
		assertThat(jdbc.queryForMap("SELECT shortage_order_qty,item_status FROM tb_account_purchase_order_item WHERE purchase_order_item_id=?", onion))
				.satisfies(r -> {
					assertThat((BigDecimal) r.get("shortage_order_qty")).isEqualByComparingTo("2");
					assertThat(r.get("item_status")).isEqualTo("RECEIVED");
				});
		List<Map<String, Object>> incoming = s.selectList("com.example.demo.mapper.ShortageProcurementMapper.incoming",
				Map.of("account_id", "A1", "date_to", today.toString()));
		assertThat(incoming.stream().filter(r -> "ONION".equals(r.get("ingredient_id"))).map(r -> (BigDecimal) r.get("quantity")))
				.allMatch(q -> q.signum() == 0);
		assertThat(states(service.pending("A1", null))).doesNotContainKey("1");
		// 조회 시작일 전 식단 사용량 SQL(식단 없음 → 빈 목록)이 실제 스키마에서 돈다
		assertThat(s.<Map<String, Object>>selectList("com.example.demo.mapper.ShortageProcurementMapper.upcomingUsage",
				Map.of("account_id", "A1", "date_from", today.plusDays(7).toString()))).isEmpty();

		// 결품 수량이 남은 수량보다 많으면 거절
		assertThatThrownBy(() -> service.register(body("{\"account_id\":\"A1\",\"purchase_order_id\":\"P1\",\"items\":[{\"client_ord_item\":\"2\",\"shortage_qty\":\"6\"}]}")))
				.hasMessageContaining("안 들어온 수량");

		// 공급사 주문 대사(updateItem)는 결품 품목 상태를 덮어쓰지 않는다
		assertThat(s.update("com.example.demo.mapper.OrderWorkflowMapper.updateItem", new HashMap<>(Map.of("purchase_order_id", "P1",
				"client_ord_item", "3", "item_status", "CONFIRMED", "supplier_result_code", "S0000", "supplier_error_message", "")))).isEqualTo(1);
		service.register(body("{\"account_id\":\"A1\",\"purchase_order_id\":\"P1\",\"items\":[{\"client_ord_item\":\"2\"},{\"client_ord_item\":\"3\"}]}"));
		assertThat(s.update("com.example.demo.mapper.OrderWorkflowMapper.updateItem", new HashMap<>(Map.of("purchase_order_id", "P1",
				"client_ord_item", "3", "item_status", "CONFIRMED", "supplier_result_code", "S0000", "supplier_error_message", "")))).isZero();
		// 남은 품목이 모두 결품 → 발주서 입고 완료(입고된 품목이 있으므로)
		assertThat(status(jdbc, "P1")).isEqualTo("RECEIVED");
		assertThat(jdbc.queryForObject("SELECT received_at IS NOT NULL FROM tb_account_purchase_order WHERE purchase_order_id='P1'", Boolean.class)).isTrue();
		assertThat((List<?>) service.pending("A1", null).get("recent_shortages")).hasSize(3);

		// 기름 결품 취소(늦게 옴) → 다시 입고 예정, 발주서 부분 입고
		var canceled = service.cancel(body("{\"account_id\":\"A1\",\"purchase_order_id\":\"P1\",\"client_ord_item\":\"3\",\"user_id\":\"u1\"}"));
		assertThat(canceled.get("item_status")).isEqualTo("CONFIRMED");
		assertThat(canceled.get("status")).isEqualTo("PARTIAL_RECEIVED");
		assertThat(states(service.pending("A1", null))).containsOnlyKeys("3");
		assertThat(jdbc.queryForObject("SELECT status FROM tb_supplier_incident WHERE provider_incident_key=?", String.class,
				"OUR|" + itemId(jdbc, "P1", "3"))).isEqualTo("CANCELED");
		// 기존 입고 SQL: 결품 2 + 입고 0 + 기름 4 입고 → 품목 RECEIVED, 발주서 RECEIVED
		s.update(SI + "addReceivedOrderQty", Map.of("purchase_order_item_id", itemId(jdbc, "P1", "3"), "received_qty", new BigDecimal("4")));
		s.update(SI + "refreshPurchaseOrderReceiptStatus", Map.of("purchase_order_id", "P1"));
		assertThat(status(jdbc, "P1")).isEqualTo("RECEIVED");

		// 발주서 P2: 아무것도 안 들어오고 전부 결품 → 미입고(NOT_RECEIVED), 사용 확정 전 미입고 목록에서 빠짐. 취소하면 원래대로
		order(jdbc, "P2", "7261119100012302", yesterday, supplier);
		item(jdbc, "P2", "1", products[0], "111", "ONION", "3", "1000");
		var mealUsageQuery = Map.of("account_id", "A1", "date_to", today.toString());
		assertThat(s.<Map<String, Object>>selectList("com.example.demo.mapper.MealUsageMapper.unreceivedOrders", mealUsageQuery))
				.extracting(r -> r.get("purchase_order_id")).containsExactly("P2");
		service.register(body("{\"account_id\":\"A1\",\"purchase_order_id\":\"P2\",\"items\":[{\"client_ord_item\":\"1\"}]}"));
		assertThat(status(jdbc, "P2")).isEqualTo("NOT_RECEIVED");
		s.clearCache(); // JdbcTemplate로 바꾼 뒤라 MyBatis 세션 캐시를 비운다
		assertThat(s.<Map<String, Object>>selectList("com.example.demo.mapper.MealUsageMapper.unreceivedOrders", mealUsageQuery)).isEmpty();
		service.cancel(body("{\"account_id\":\"A1\",\"purchase_order_id\":\"P2\",\"client_ord_item\":\"1\"}"));
		assertThat(status(jdbc, "P2")).isEqualTo("CONFIRMED");
		assertThat(jdbc.queryForObject("SELECT supplier_result_message FROM tb_account_purchase_order WHERE purchase_order_id='P2'", String.class)).isNull();

		// 웰스토리 상황 조회: P2 1번 품목 미입(E) → "웰스토리 미입 등록", 새 입고일이 미래면 배송 대기로 연장
		JsonNode situation = json.readTree("""
				{"dataBody":{"resCd":"S0000","resMsg":"ok","data":[
				 {"csNo":"CS1","csNoItem":"10","csType":"E","clientOrd":"7261119100012302","clientOrdItem":"000001","itemCode":"111",
				  "csQty":"3","confirmQty":"3","csNote":"미입 확인","ndeliDate":"","crDate":"%s","crDateTime":"093000"}]}}
				""".formatted(today.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)));
		when(welstory.call(eq("/fdapi/service/payer-situation-detail"), any())).thenReturn(situation);
		var sync = service.syncSituations("A1", null, null, true);
		assertThat(sync).containsEntry("linked_count", 1).containsEntry("missing_count", 1);
		assertThat(states(service.pending("A1", null))).containsEntry("1", "SUPPLIER_REPORTED");
		assertThat(service.syncSituations("A1", null, null, false)).containsEntry("skipped", true);

		JsonNode later = json.readTree(situation.toString().replace("\"ndeliDate\":\"\"",
				"\"ndeliDate\":\"" + today.plusDays(2).format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE) + "\""));
		when(welstory.call(eq("/fdapi/service/payer-situation-detail"), any())).thenReturn(later);
		service.syncSituations("A1", null, null, true);
		var pending = service.pending("A1", null);
		assertThat(states(pending)).containsEntry("1", "WAITING");
		assertThat(((List<Map<String, Object>>) pending.get("items")).get(0).get("expected_date")).isEqualTo(today.plusDays(2).toString());

		// 우리가 결품으로 확정하면 웰스토리 미입 상황은 반영됨(APPLIED)
		service.register(body("{\"account_id\":\"A1\",\"purchase_order_id\":\"P2\",\"source\":\"SUPPLIER_SITUATION\",\"items\":[{\"client_ord_item\":\"1\"}]}"));
		assertThat(jdbc.queryForObject("SELECT status FROM tb_supplier_incident WHERE provider_incident_key='W|CS1|10'", String.class)).isEqualTo("APPLIED");
		// 다시 불러와도 상태는 그대로
		service.syncSituations("A1", null, null, true);
		assertThat(jdbc.queryForObject("SELECT status FROM tb_supplier_incident WHERE provider_incident_key='W|CS1|10'", String.class)).isEqualTo("APPLIED");
	}

	@SuppressWarnings("unchecked")
	static Map<String, String> states(Map<String, Object> pending) {
		Map<String, String> map = new TreeMap<>();
		for (var row : (List<Map<String, Object>>) pending.get("items"))
			map.put(String.valueOf(row.get("client_ord_item")), String.valueOf(row.get("state")));
		return map;
	}

	JsonNode body(String text) throws Exception {
		return json.readTree(text);
	}

	static void order(JdbcTemplate jdbc, String id, String clientOrd, LocalDate date, long supplier) {
		jdbc.update("INSERT INTO tb_account_purchase_order(purchase_order_id,account_id,supplier_id,sold_to,client_ord,requested_delivery_date,status,confirmed_at)"
				+ " VALUES(?,'A1',?,'1000123',?,?,'CONFIRMED',NOW())", id, supplier, clientOrd, date.toString());
	}

	static void item(JdbcTemplate jdbc, String order, String seq, long product, String code, String ingredient, String qty, String price) {
		jdbc.update("INSERT INTO tb_account_purchase_order_item(purchase_order_id,client_ord_item,supplier_product_id,source_ingredient_id,"
				+ "supplier_item_code,product_name_snapshot,order_qty,order_unit,base_qty_per_order_unit,base_unit,unit_price_snapshot,line_amount,item_status)"
				+ " VALUES(?,?,?,?,?,?,?,'EA',1,'EA',?,?*?,'CONFIRMED')", order, seq, product, ingredient, code, "상품" + code,
				new BigDecimal(qty), new BigDecimal(price), new BigDecimal(qty), new BigDecimal(price));
	}

	static long itemId(JdbcTemplate jdbc, String order, String seq) {
		return jdbc.queryForObject("SELECT purchase_order_item_id FROM tb_account_purchase_order_item WHERE purchase_order_id=? AND client_ord_item=?",
				Long.class, order, seq);
	}

	static String status(JdbcTemplate jdbc, String order) {
		return jdbc.queryForObject("SELECT status FROM tb_account_purchase_order WHERE purchase_order_id=?", String.class, order);
	}
}

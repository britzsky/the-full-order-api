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
import com.example.demo.mapper.PurchaseLedgerMapper;

/**
 * 매입 관리(ASP, 2026-11-23) SQL을 로컬 MySQL 임시 스키마(tfo_ledger_verify, 운영 DDL로 생성)에서 실제로 돌린 뒤 지운다.
 * 발주 입고(부가세 추정) → 공급사 청구 줄 저장·발주 품목 찾기 → 대조 → 매입 합계·목록·단가 기준 확인.
 * 로컬 the_full_order·운영 RDS는 건드리지 않는다. 실행: RUN_PURCHASE_HISTORY_DB=true
 */
@EnabledIfEnvironmentVariable(named = "RUN_PURCHASE_HISTORY_DB", matches = "true")
class LocalPurchaseLedgerDatabaseTest {
	static final String SCHEMA = "tfo_ledger_verify";
	static final String PH = "com.example.demo.mapper.PurchaseHistoryMapper.";
	static final String SI = "com.example.demo.mapper.SupplierIntegrationMapper.";

	@Test
	void ledgerSqlOnRealSchema() throws Exception {
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
		c.setAutoCommit(true);
		var jdbc = new JdbcTemplate(new SingleConnectionDataSource(c, true));
		var cfg = new org.apache.ibatis.session.Configuration(new org.apache.ibatis.mapping.Environment("ledger",
				new org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory(), new SingleConnectionDataSource(c, true)));
		for (String file : List.of("PurchaseHistoryMapper.xml", "SupplierIntegrationMapper.xml", "PurchaseLedgerMapper.xml", "OrderWorkflowMapper.xml"))
			try (var in = Files.newInputStream(root.resolve("mybatis-mapper/" + file))) {
				new org.apache.ibatis.builder.xml.XMLMapperBuilder(in, cfg, "mybatis-mapper/" + file, cfg.getSqlFragments()).parse();
			}
		SqlSession s = new org.apache.ibatis.session.SqlSessionFactoryBuilder().build(cfg).openSession(true);
		PurchaseLedgerMapper ledgerMapper = s.getMapper(PurchaseLedgerMapper.class);
		var ledger = new PurchaseLedgerService(ledgerMapper, PurchasePriceBasis.excludingVat());

		// 준비: 웰스토리·사업장·거래처 연결·상품 2개(과세 양파, 면세 쌀)·발주서(품목 1, 2)
		jdbc.update("INSERT INTO tb_supplier(supplier_code,supplier_name) VALUES('WELSTORY','삼성웰스토리')");
		long supplier = jdbc.queryForObject("SELECT supplier_id FROM tb_supplier", Long.class);
		jdbc.update("INSERT INTO tb_supplier_site(supplier_id,external_site_code) VALUES(?,'1000123')", supplier);
		long siteMaster = jdbc.queryForObject("SELECT supplier_site_id FROM tb_supplier_site", Long.class);
		jdbc.update("INSERT INTO tb_supplier_account_site(account_id,supplier_id,supplier_site_id,external_site_code,active_yn) VALUES('A1',?,?,'1000123','Y')", supplier, siteMaster);
		long site = jdbc.queryForObject("SELECT supplier_account_site_id FROM tb_supplier_account_site", Long.class);
		jdbc.update("INSERT INTO tb_supplier_ingredient_product(supplier_id,supplier_item_code,product_name,order_unit,package_unit,base_qty,base_unit,tax_type) VALUES(?,'111','양파','망','망',1000,'g','Full tax')", supplier);
		jdbc.update("INSERT INTO tb_supplier_ingredient_product(supplier_id,supplier_item_code,product_name,order_unit,package_unit,base_qty,base_unit,tax_type) VALUES(?,'222','쌀','포','포',10000,'g','No tax')", supplier);
		long onion = jdbc.queryForObject("SELECT supplier_product_id FROM tb_supplier_ingredient_product WHERE supplier_item_code='111'", Long.class);
		long rice = jdbc.queryForObject("SELECT supplier_product_id FROM tb_supplier_ingredient_product WHERE supplier_item_code='222'", Long.class);
		jdbc.update("INSERT INTO tb_account_purchase_order(purchase_order_id,account_id,supplier_id,sold_to,client_ord,requested_delivery_date,status) VALUES('PO1','A1',?,'1000123','ORD1','2026-11-10','CONFIRMED')", supplier);
		jdbc.update("INSERT INTO tb_account_purchase_order_item(purchase_order_id,client_ord_item,supplier_product_id,supplier_item_code,order_qty,order_unit,base_qty_per_order_unit,base_unit,unit_price_snapshot,line_amount,item_status) VALUES('PO1','1',?,'111',3,'망',1000,'g',1500,4500,'ORDERED')", onion);
		jdbc.update("INSERT INTO tb_account_purchase_order_item(purchase_order_id,client_ord_item,supplier_product_id,supplier_item_code,order_qty,order_unit,base_qty_per_order_unit,base_unit,unit_price_snapshot,line_amount,item_status) VALUES('PO1','2',?,'222',1,'포',10000,'g',30000,30000,'ORDERED')", rice);
		long item1 = jdbc.queryForObject("SELECT purchase_order_item_id FROM tb_account_purchase_order_item WHERE client_ord_item='1'", Long.class);
		long item2 = jdbc.queryForObject("SELECT purchase_order_item_id FROM tb_account_purchase_order_item WHERE client_ord_item='2'", Long.class);

		// 발주 품목 조회에 과세 구분·공급사 코드가 함께 나온다(입고 부가세 추정에 씀)
		List<Map<String, Object>> orderItems = s.selectList("com.example.demo.mapper.OrderWorkflowMapper.items", new HashMap<>(Map.of("account_id", "A1", "purchase_order_id", "PO1")));
		assertThat(orderItems).hasSize(2).first().satisfies(r -> {
			assertThat(r.get("product_tax_type")).isEqualTo("Full tax");
			assertThat(r.get("supplier_code")).isEqualTo("WELSTORY");
		});

		// 발주 입고 2줄(같은 입고 문서): 양파 3망 1500원(과세 추정), 쌀 1포 30000원(면세)
		String receiptId = "GR1";
		receiveLine(s, receiptId, supplier, site, item1, onion, "1", "3", "1500", "4500", "450", "TAXABLE");
		receiveLine(s, receiptId, supplier, site, item2, rice, "2", "1", "30000", "30000", "0", "TAX_FREE");
		s.update(SI + "refreshReceiptTotals", Map.of("goods_receipt_id", receiptId));
		assertThat(jdbc.queryForObject("SELECT total_amount FROM tb_goods_receipt WHERE goods_receipt_id='GR1'", BigDecimal.class)).isEqualByComparingTo("34950");

		// 공급사 청구 줄: 웰스토리 순번 '000001' → 우리 '1'. 양파는 공급가 4800(단가 다름), 쌀은 같음
		Map<String, Object> found = s.selectOne(PH + "orderItemForBill", new HashMap<>(Map.of("account_id", "A1", "supplier_id", supplier, "client_ord", "ORD1", "client_ord_item", "000001")));
		assertThat(((Number) found.get("purchase_order_item_id")).longValue()).isEqualTo(item1);
		bill(s, supplier, site, item1, "K1", "000001", "3", "1600", "4800", "480");
		bill(s, supplier, site, item2, "K2", "000002", "1", "30000", "30000", "0");
		bill(s, supplier, site, item1, "K1", "000001", "3", "1600", "4800", "480"); // 다시 받아도 한 줄
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_supplier_bill_line", Integer.class)).isEqualTo(2);

		var counts = ledger.reconcile(List.of(item1, item2));
		assertThat(counts).containsEntry("checked", 2).containsEntry("matched", 1).containsEntry("price_diff", 1);
		assertThat(jdbc.queryForObject("SELECT CONCAT(amount_source,'|',amount_check_status,'|',vat_amount+0) FROM tb_goods_receipt_item WHERE purchase_order_item_id=?", String.class, item1))
				.isEqualTo("SUPPLIER|PRICE_DIFF|480.00");
		assertThat(jdbc.queryForObject("SELECT total_amount FROM tb_goods_receipt WHERE goods_receipt_id='GR1'", BigDecimal.class)).isEqualByComparingTo("35280");
		// 다시 대조해도 같은 결과(단가로 다시 계산해 비교)
		assertThat(ledger.recheck(new HashMap<>(Map.of("from_date", "2026-11-01", "to_date", "2026-11-30")))).containsEntry("price_diff", 1).containsEntry("matched", 1);

		// 같은 입고를 다시 보내도(공급사 금액으로 바뀐 줄) 수량이 같으면 중복 없이 무시 — receiptItem이 amount_source를 돌려준다
		Map<String, Object> prior = s.selectOne("com.example.demo.mapper.OrderWorkflowMapper.receiptItem", new HashMap<>(Map.of("account_id", "A1", "supplier_id", supplier, "provider_line_key", "GR1|1")));
		assertThat(prior).containsEntry("amount_source", "SUPPLIER").containsEntry("amount_check_status", "PRICE_DIFF");

		// 직접 구매(UNMATCHED) 1줄 — 구매 이력 SQL로
		var h = new HashMap<String, Object>(Map.of("goods_receipt_id", "GR2", "account_id", "A1", "supplier_id", supplier,
				"supplier_account_site_id", site, "provider_receipt_key", "EXT|A1|X", "receipt_date", "2026-11-12", "status", "UNMATCHED",
				"receipt_attributes", "{}"));
		s.insert(PH + "upsertHistoryReceipt", h);
		var line = new HashMap<String, Object>(h);
		line.putAll(Map.of("supplier_product_id", onion, "provider_line_key", "W|1|111|1", "ordered_qty", BigDecimal.ONE, "received_qty", BigDecimal.ONE,
				"order_unit", "망", "base_received_qty", new BigDecimal("1000"), "base_unit", "g", "unit_price", new BigDecimal("1000"),
				"supply_amount", new BigDecimal("1000"), "vat_amount", new BigDecimal("100")));
		line.put("total_amount", new BigDecimal("1100"));
		line.put("tax_type", "TAXABLE");
		line.put("provider_attributes", "{}");
		s.insert(PH + "upsertHistoryReceiptItem", line);
		s.update(PH + "refreshHistoryReceiptTotals", Map.of("goods_receipt_id", "GR2"));

		// 합계·묶음·목록
		var period = new HashMap<String, Object>(Map.of("from_date", "2026-11-01", "to_date", "2026-11-30"));
		var summary = ledger.summary(new HashMap<>(Map.of("from_date", "2026-11-01", "to_date", "2026-11-30", "group_by", "account")));
		@SuppressWarnings("unchecked")
		var totals = (Map<String, Object>) summary.get("totals");
		assertThat(((Number) totals.get("line_count")).intValue()).isEqualTo(3);
		assertThat((BigDecimal) totals.get("total_amount")).isEqualByComparingTo("36380");
		assertThat((BigDecimal) totals.get("taxable_vat_amount")).isEqualByComparingTo("580");
		assertThat((BigDecimal) totals.get("tax_free_supply_amount")).isEqualByComparingTo("30000");
		assertThat((BigDecimal) totals.get("order_total_amount")).isEqualByComparingTo("35280");
		assertThat((BigDecimal) totals.get("direct_total_amount")).isEqualByComparingTo("1100");
		assertThat(((Number) totals.get("price_diff_line_count")).intValue()).isEqualTo(1);
		assertThat((List<?>) summary.get("groups")).hasSize(1);
		for (String g : List.of("supplier", "month", "day"))
			assertThat((List<?>) ledger.summary(new HashMap<>(Map.of("from_date", "2026-11-01", "to_date", "2026-11-30", "group_by", g))).get("groups")).isNotEmpty();

		var lines = ledger.lines(new HashMap<>(Map.of("from_date", "2026-11-01", "to_date", "2026-11-30", "source", "ORDER", "check_status", "DIFF")));
		assertThat(((Number) lines.get("total_count")).longValue()).isEqualTo(1);
		@SuppressWarnings("unchecked")
		var row = ((List<Map<String, Object>>) lines.get("rows")).get(0);
		assertThat(row).containsEntry("source", "ORDER").containsEntry("client_ord", "ORD1").containsEntry("product_name", "양파");
		assertThat((BigDecimal) row.get("bill_supply_amount")).isEqualByComparingTo("4800");
		assertThat(((Number) ledger.lines(new HashMap<>(Map.of("from_date", "2026-11-01", "to_date", "2026-11-30", "keyword", "쌀", "tax_type", "TAX_FREE"))).get("total_count")).longValue()).isEqualTo(1);
		assertThat(((Number) ledger.lines(new HashMap<>(Map.of("from_date", "2026-11-01", "to_date", "2026-11-30", "source", "DIRECT"))).get("total_count")).longValue()).isEqualTo(1);

		// 단가 기준 확인: 양파 청구는 발주 단가 1500×3=4500 ≠ 공급가 4800, ≠ 합계 5280 → 맞지 않는 예시로 나옴. 직접 구매 줄은 단가×수량=공급가
		var check = ledger.priceCheck(period);
		@SuppressWarnings("unchecked")
		var suppliers = (List<Map<String, Object>>) check.get("suppliers");
		assertThat(suppliers).singleElement().satisfies(sup -> {
			assertThat(sup).containsEntry("order_price_basis", "MIXED").containsEntry("supplier_unit_price_basis", "EXCLUDES_VAT");
		});
		assertThat((List<?>) check.get("mismatch_samples")).hasSize(1);
		assertThat((List<?>) check.get("unlinked_bill_lines")).isEmpty();
		assertThat((List<?>) check.get("billed_not_received")).isEmpty();

		// 우리 발주 품목을 못 찾은 청구 줄, 청구됐지만 입고 없는 품목
		bill(s, supplier, site, null, "K9", "000009", "1", "500", "500", "50");
		jdbc.update("INSERT INTO tb_account_purchase_order_item(purchase_order_id,client_ord_item,supplier_product_id,supplier_item_code,order_qty,order_unit,base_qty_per_order_unit,base_unit,unit_price_snapshot,line_amount,item_status) VALUES('PO1','3',?,'111',1,'망',1000,'g',1500,1500,'ORDERED')", onion);
		long item3 = jdbc.queryForObject("SELECT purchase_order_item_id FROM tb_account_purchase_order_item WHERE client_ord_item='3'", Long.class);
		bill(s, supplier, site, item3, "K3", "000003", "1", "1500", "1500", "150");
		check = ledger.priceCheck(period);
		assertThat((List<?>) check.get("unlinked_bill_lines")).hasSize(1);
		assertThat((List<?>) check.get("billed_not_received")).hasSize(1);
	}

	void receiveLine(SqlSession s, String receiptId, long supplier, long site, long orderItem, long product, String seq, String qty,
			String unitPrice, String supply, String vat, String taxType) {
		var p = new HashMap<String, Object>();
		p.put("goods_receipt_id", receiptId);
		p.put("account_id", "A1");
		p.put("supplier_id", supplier);
		p.put("supplier_account_site_id", site);
		p.put("purchase_order_id", "PO1");
		p.put("provider_receipt_key", "MANUAL|PO1|K");
		p.put("receipt_date", "2026-11-10");
		p.put("purchase_order_item_id", orderItem);
		p.put("supplier_product_id", product);
		p.put("provider_line_key", receiptId + "|" + seq);
		p.put("ordered_qty", new BigDecimal(qty));
		p.put("received_qty", new BigDecimal(qty));
		p.put("order_unit", "EA");
		p.put("base_received_qty", new BigDecimal(qty));
		p.put("base_unit", "EA");
		p.put("unit_price", new BigDecimal(unitPrice));
		p.put("supply_amount", new BigDecimal(supply));
		p.put("vat_amount", new BigDecimal(vat));
		p.put("total_amount", new BigDecimal(supply).add(new BigDecimal(vat)));
		p.put("tax_type", taxType);
		p.put("amount_source", "ORDER_ESTIMATE");
		p.put("amount_check_status", "UNCHECKED");
		p.put("reconciliation_status", "MATCHED");
		p.put("provider_attributes", "{}");
		s.insert(SI + "insertGoodsReceipt", p);
		assertThat(s.insert(SI + "insertGoodsReceiptItem", p)).isEqualTo(1);
	}

	void bill(SqlSession s, long supplier, long site, Long orderItem, String key, String seq, String qty, String unitPrice, String supply, String vat) {
		var p = new HashMap<String, Object>();
		p.put("account_id", "A1");
		p.put("supplier_id", supplier);
		p.put("supplier_account_site_id", site);
		p.put("purchase_order_id", orderItem == null ? null : "PO1");
		p.put("purchase_order_item_id", orderItem);
		p.put("provider_line_key", "EXT|A1|1000123|20261110|ORD1|" + key);
		p.put("bill_date", "2026-11-10");
		p.put("client_ord", "ORD1");
		p.put("client_ord_item", seq);
		p.put("supplier_item_code", "111");
		p.put("billed_qty", new BigDecimal(qty));
		p.put("unit_price", new BigDecimal(unitPrice));
		p.put("supply_amount", new BigDecimal(supply));
		p.put("vat_amount", new BigDecimal(vat));
		p.put("total_amount", new BigDecimal(supply).add(new BigDecimal(vat)));
		p.put("tax_type", new BigDecimal(vat).signum() > 0 ? "TAXABLE" : "TAX_FREE");
		p.put("provider_attributes", "{}");
		s.insert(PH + "upsertBillLine", p);
	}
}

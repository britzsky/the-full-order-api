package com.example.demo.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.demo.mapper.DistAdminMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 더채움 유통 — 본사 '협력사 관리'(2026-11-02 사용자 결정: 본사가 유통 관리자로 협력사·상품을 관리, 주문은 별도 더채움몰 화면).
 * 1단계는 본사가 직접 입력한다(협력사 로그인은 미정). 본사 입력은 승인 대기 없이 바로 APPROVED, 바뀐 값은 tb_dist_product_approval에 이력으로 남긴다.
 * 판매가는 유형(ACCOUNT 거래처가/PERSONAL 개인가/EVENT 행사가/INTERNAL 사내 대체가)·거래처별로 기간이 겹치지 않게 쌓고, 새 가격을 넣으면 이전 가격을 그 시각에 닫는다.
 */
@Service
public class DistAdminService {
	static final Set<String> PARTNER_TYPES = Set.of("SUPPLIER", "DROP_SHIPPER", "BOTH");
	static final Set<String> PO_METHODS = Set.of("EMAIL", "KAKAO", "API", "PORTAL", "MANUAL");
	static final Set<String> SETTLE_CYCLES = Set.of("MONTHLY", "BIWEEKLY", "WEEKLY");
	static final Set<String> FULFILLMENT = Set.of("DROP_SHIP", "OWN_STOCK");
	static final Set<String> SALE_STATUS = Set.of("DRAFT", "ON_SALE", "SOLD_OUT", "PAUSED", "DISCONTINUED");
	static final Set<String> SALE_TARGETS = Set.of("ALL", "ACCOUNT", "PERSONAL", "INTERNAL");
	static final Set<String> TAX_TYPES = Set.of("TAXABLE", "TAX_FREE");
	static final Set<String> STORAGE = Set.of("ROOM", "CHILLED", "FROZEN");
	static final Set<String> PRICE_TYPES = Set.of("ACCOUNT", "PERSONAL", "EVENT", "INTERNAL");
	static final Set<String> IMAGE_TYPES = Set.of("MAIN", "DETAIL", "LABEL");
	static final Set<String> AVAILABILITY = Set.of("AVAILABLE", "LIMITED", "UNAVAILABLE");
	private static final Pattern CODE = Pattern.compile("[A-Z0-9_-]{2,30}");
	/** 이력에 남길 상품 기본 정보(바뀐 것만 before/after로 저장) */
	static final List<String> PRODUCT_FIELDS = List.of("product_code", "product_group_code", "partner_id", "category_id", "product_name", "option_name",
			"partner_item_code", "fulfillment_type", "sale_target", "tax_type", "storage_type", "origin", "sale_unit", "unit_content_qty", "unit_content_unit",
			"shelf_life_days", "min_order_qty", "max_order_qty", "shipping_fee", "free_shipping_amount", "bundle_ship_yn", "safety_stock_qty",
			"sale_start_at", "sale_end_at", "summary", "description_html");

	private final DistAdminMapper mapper;
	private final ObjectMapper json;

	public DistAdminService(DistAdminMapper mapper, ObjectMapper json) {
		this.mapper = mapper;
		this.json = json;
	}

	/* ===================== 판매 주체 ===================== */

	public Map<String, Object> seller() {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("seller", mapper.seller());
		return result;
	}

	@Transactional
	public Map<String, Object> saveSeller(Map<String, Object> body) {
		Map<String, Object> row = new HashMap<>();
		row.put("seller_name", required(body, "seller_name", "상호", 100));
		String bizNo = required(body, "biz_no", "사업자등록번호", 20).replaceAll("[^0-9]", "");
		if (bizNo.length() != 10) throw new IllegalArgumentException("사업자등록번호는 숫자 10자리입니다.");
		row.put("biz_no", bizNo);
		for (String[] f : new String[][] { { "ceo_name", "50" }, { "mail_order_reg_no", "50" }, { "biz_type", "100" }, { "biz_item", "100" },
				{ "phone", "30" }, { "email", "100" }, { "zipcode", "10" }, { "address1", "255" }, { "address2", "255" },
				{ "deposit_bank_name", "50" }, { "deposit_account_no", "30" }, { "deposit_account_holder", "50" } })
			row.put(f[0], optional(body, f[0], Integer.parseInt(f[1])));
		int dueDays = (int) number(body.get("deposit_due_days"), new BigDecimal(3), "입금 기한 일수").longValue();
		if (dueDays < 1 || dueDays > 30) throw new IllegalArgumentException("입금 기한은 1~30일입니다.");
		row.put("deposit_due_days", dueDays);
		row.put("user_id", body.get("user_id"));
		Map<String, Object> current = mapper.seller();
		if (current == null) {
			row.put("seller_code", "THEFULL");
			mapper.insertSeller(row);
		} else {
			row.put("seller_id", current.get("seller_id"));
			mapper.updateSeller(row);
		}
		return ok("판매 주체 정보를 저장했습니다.");
	}

	/* ===================== 협력사 ===================== */

	public Map<String, Object> partners() {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("partners", mapper.partners());
		result.put("carriers", mapper.carriers());
		return result;
	}

	@Transactional
	public Map<String, Object> savePartner(Map<String, Object> body) {
		long id = optionalId(body.get("partner_id"));
		Map<String, Object> row = new HashMap<>();
		row.put("partner_id", id);
		if (id > 0 && mapper.partnerExists(row) == 0) throw new IllegalArgumentException("협력사를 찾을 수 없습니다.");
		row.put("partner_code", code(body.get("partner_code"), "협력사 코드"));
		if (mapper.samePartnerCode(row) > 0) throw new IllegalArgumentException("같은 협력사 코드가 이미 있습니다: " + row.get("partner_code"));
		row.put("partner_name", required(body, "partner_name", "협력사명", 100));
		row.put("partner_type", oneOf(body.get("partner_type"), PARTNER_TYPES, "SUPPLIER", "협력사 유형"));
		for (String[] f : new String[][] { { "biz_no", "20" }, { "ceo_name", "50" }, { "manager_name", "50" }, { "manager_phone", "30" },
				{ "manager_email", "100" }, { "zipcode", "10" }, { "address1", "255" }, { "address2", "255" }, { "po_send_target", "255" },
				{ "default_carrier_code", "20" } })
			row.put(f[0], optional(body, f[0], Integer.parseInt(f[1])));
		row.put("po_send_method", oneOf(body.get("po_send_method"), PO_METHODS, "MANUAL", "발주서 전달 방식"));
		String cycle = text(body.get("settle_cycle"));
		row.put("settle_cycle", cycle.isEmpty() ? null : oneOf(cycle, SETTLE_CYCLES, null, "정산 주기"));
		row.put("order_cutoff_time", time(body.get("order_cutoff_time")));
		int lead = (int) number(body.get("lead_time_days"), BigDecimal.ONE, "출고 소요일").longValue();
		if (lead < 0 || lead > 60) throw new IllegalArgumentException("출고 소요일은 0~60일입니다.");
		row.put("lead_time_days", lead);
		row.put("active_yn", yn(body.get("active_yn"), "Y"));
		row.put("user_id", body.get("user_id"));
		if (id > 0) mapper.updatePartner(row);
		else mapper.insertPartner(row);
		Map<String, Object> result = ok(id > 0 ? "협력사를 저장했습니다." : "협력사를 등록했습니다.");
		result.put("partner_id", row.get("partner_id"));
		return result;
	}

	/* ===================== 카테고리 ===================== */

	public Map<String, Object> categories() {
		return Map.of("categories", mapper.categories());
	}

	@Transactional
	public Map<String, Object> saveCategory(Map<String, Object> body) {
		long id = optionalId(body.get("category_id"));
		Map<String, Object> row = new HashMap<>();
		row.put("category_id", id);
		if (id > 0 && mapper.categoryExists(row) == 0) throw new IllegalArgumentException("카테고리를 찾을 수 없습니다.");
		row.put("category_code", code(body.get("category_code"), "카테고리 코드"));
		if (mapper.sameCategoryCode(row) > 0) throw new IllegalArgumentException("같은 카테고리 코드가 이미 있습니다: " + row.get("category_code"));
		row.put("category_name", required(body, "category_name", "카테고리명", 100));
		long parent = optionalId(body.get("parent_category_id"));
		if (parent > 0) {
			if (parent == id) throw new IllegalArgumentException("자기 자신을 상위 카테고리로 고를 수 없습니다.");
			if (mapper.categoryExists(Map.of("category_id", parent)) == 0) throw new IllegalArgumentException("상위 카테고리를 찾을 수 없습니다.");
		}
		row.put("parent_category_id", parent > 0 ? parent : null);
		row.put("sort_order", (int) number(body.get("sort_order"), BigDecimal.ZERO, "정렬 순서").longValue());
		row.put("use_yn", yn(body.get("use_yn"), "Y"));
		row.put("user_id", body.get("user_id"));
		if (id > 0) mapper.updateCategory(row);
		else mapper.insertCategory(row);
		Map<String, Object> result = ok("카테고리를 저장했습니다.");
		result.put("category_id", row.get("category_id"));
		return result;
	}

	/* ===================== 상품 ===================== */

	public Map<String, Object> products(Map<String, String> query) {
		Map<String, Object> p = new HashMap<>();
		long partner = optionalId(query.get("partner_id")), category = optionalId(query.get("category_id"));
		p.put("partner_id", partner > 0 ? partner : null);
		p.put("category_id", category > 0 ? category : null);
		String status = text(query.get("sale_status"));
		p.put("sale_status", status.isEmpty() ? null : oneOf(status, SALE_STATUS, null, "판매 상태"));
		String type = text(query.get("fulfillment_type"));
		p.put("fulfillment_type", type.isEmpty() ? null : oneOf(type, FULFILLMENT, null, "출고 방식"));
		p.put("keyword", text(query.get("keyword")));
		return Map.of("products", mapper.products(p));
	}

	public Map<String, Object> product(Object productId) {
		Map<String, Object> key = Map.of("product_id", id(productId, "상품"));
		Map<String, Object> product = mapper.product(key);
		if (product == null) throw new IllegalArgumentException("상품을 찾을 수 없습니다.");
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("product", product);
		result.put("images", mapper.images(key));
		result.put("sale_prices", mapper.salePrices(key));
		result.put("cost_prices", mapper.costPrices(key));
		result.put("availability", mapper.availability(key));
		result.put("history", mapper.history(key));
		return result;
	}

	/**
	 * 상품 등록·수정. 등록은 '작성중(DRAFT)'으로 시작하고 판매가를 넣은 뒤 '판매중'으로 바꾼다.
	 * 등록 때 account_price/personal_price/unit_cost를 같이 주면 오늘부터 적용되는 첫 가격으로 넣는다.
	 */
	@Transactional
	public Map<String, Object> saveProduct(Map<String, Object> body) {
		long id = optionalId(body.get("product_id"));
		Map<String, Object> before = null;
		if (id > 0) {
			before = mapper.product(Map.of("product_id", id));
			if (before == null) throw new IllegalArgumentException("상품을 찾을 수 없습니다.");
		}
		Map<String, Object> row = productValues(body, id);
		Object userId = body.get("user_id");
		row.put("user_id", userId);
		if (id > 0) {
			mapper.updateProduct(row);
		} else {
			Map<String, Object> seller = mapper.seller();
			if (seller == null) throw new IllegalArgumentException("판매 주체(더채움 사업자 정보)를 먼저 등록해주세요. 협력사 관리 › 기본 설정");
			row.put("seller_id", seller.get("seller_id"));
			mapper.insertProduct(row);
			id = ((Number) row.get("product_id")).longValue();
		}
		Map<String, Object> content = new HashMap<>();
		content.put("product_id", id);
		content.put("summary", row.get("summary"));
		content.put("description_html", row.get("description_html"));
		content.put("food_label_json", productInfo(body.get("food_label_json"), "식품 표시사항", false));
		content.put("notice_json", productInfo(body.get("notice_json"), "상품정보제공고시", true));
		content.put("user_id", userId);
		mapper.saveContent(content);

		Map<String, Object> changedBefore = new LinkedHashMap<>(), changedAfter = new LinkedHashMap<>();
		for (String field : PRODUCT_FIELDS) {
			Object after = row.get(field), old = before == null ? null : before.get(field);
			if (before == null ? after != null && !text(after).isEmpty() : !same(old, after)) {
				// 상세 설명은 HTML 전체 대신 글자 수만 남긴다(이력이 커지지 않게)
				if (before != null) changedBefore.put(field, "description_html".equals(field) ? lengthNote(old) : old);
				changedAfter.put(field, "description_html".equals(field) ? lengthNote(after) : after);
			}
		}
		for (String field : List.of("food_label_json", "notice_json")) {
			Object after = content.get(field), old = before == null ? null : before.get(field);
			if (before == null ? after != null : !sameJson(old, after)) {
				if (before != null) changedBefore.put(field, infoNote(old));
				changedAfter.put(field, infoNote(after));
			}
		}
		if (before == null || !changedAfter.isEmpty())
			history(id, before == null ? "CREATE" : "UPDATE", before == null ? null : changedBefore, changedAfter, userId);

		if (before == null) {
			String today = dbNow().toLocalDate().toString();
			for (String type : List.of("ACCOUNT", "PERSONAL")) {
				Object price = body.get(type.toLowerCase() + "_price");
				if (!text(price).isEmpty())
					addSalePrice(new HashMap<>(Map.of("product_id", id, "price_type", type, "unit_price", price, "change_reason", "최초 등록")), userId);
			}
			if (!text(body.get("unit_cost")).isEmpty())
				addCostPrice(new HashMap<>(Map.of("product_id", id, "unit_cost", body.get("unit_cost"), "start_date", today, "change_reason", "최초 등록")), userId);
		}
		Map<String, Object> result = ok(before == null ? "상품을 등록했습니다. 판매가를 확인하고 '판매중'으로 바꾸면 더채움몰에 보입니다." : "상품을 저장했습니다.");
		result.put("product_id", id);
		return result;
	}

	private Map<String, Object> productValues(Map<String, Object> body, long id) {
		Map<String, Object> row = new HashMap<>();
		row.put("product_id", id);
		String productCode = text(body.get("product_code")).toUpperCase();
		if (productCode.isEmpty()) {
			if (id > 0) throw new IllegalArgumentException("상품코드를 입력해주세요.");
			productCode = nextProductCode();
		} else if (!CODE.matcher(productCode).matches()) {
			throw new IllegalArgumentException("상품코드는 영문 대문자·숫자·-·_ 2~30자입니다.");
		}
		row.put("product_code", productCode);
		if (mapper.sameProductCode(row) > 0) throw new IllegalArgumentException("같은 상품코드가 이미 있습니다: " + productCode);
		String group = text(body.get("product_group_code")).toUpperCase();
		if (!group.isEmpty() && !CODE.matcher(group).matches()) throw new IllegalArgumentException("묶음 코드는 영문 대문자·숫자·-·_ 2~30자입니다.");
		row.put("product_group_code", group.isEmpty() ? null : group);
		long partner = id(body.get("partner_id"), "협력사");
		if (mapper.partnerExists(Map.of("partner_id", partner)) == 0) throw new IllegalArgumentException("협력사를 찾을 수 없습니다.");
		row.put("partner_id", partner);
		long category = optionalId(body.get("category_id"));
		if (category > 0 && mapper.categoryExists(Map.of("category_id", category)) == 0) throw new IllegalArgumentException("카테고리를 찾을 수 없습니다.");
		row.put("category_id", category > 0 ? category : null);
		row.put("product_name", required(body, "product_name", "상품명", 255));
		row.put("option_name", optional(body, "option_name", 100));
		row.put("partner_item_code", optional(body, "partner_item_code", 50));
		row.put("fulfillment_type", oneOf(body.get("fulfillment_type"), FULFILLMENT, null, "출고 방식(협력사 직송/자사재고)"));
		row.put("sale_target", oneOf(body.get("sale_target"), SALE_TARGETS, "ALL", "판매 대상"));
		row.put("tax_type", oneOf(body.get("tax_type"), TAX_TYPES, "TAXABLE", "과세 구분"));
		row.put("storage_type", oneOf(body.get("storage_type"), STORAGE, "ROOM", "보관 방법"));
		row.put("origin", optional(body, "origin", 100));
		String unit = text(body.get("sale_unit"));
		if (unit.length() > 30) throw new IllegalArgumentException("판매 단위는 30자까지입니다.");
		row.put("sale_unit", unit.isEmpty() ? "EA" : unit);
		row.put("unit_content_qty", optionalNumber(body.get("unit_content_qty"), "내용량"));
		row.put("unit_content_unit", optional(body, "unit_content_unit", 20));
		BigDecimal shelf = optionalNumber(body.get("shelf_life_days"), "소비기한 일수");
		row.put("shelf_life_days", shelf == null ? null : shelf.intValue());
		BigDecimal min = number(body.get("min_order_qty"), BigDecimal.ONE, "최소 주문 수량");
		if (min.signum() <= 0) throw new IllegalArgumentException("최소 주문 수량은 0보다 커야 합니다.");
		BigDecimal max = optionalNumber(body.get("max_order_qty"), "최대 주문 수량");
		if (max != null && max.compareTo(min) < 0) throw new IllegalArgumentException("최대 주문 수량이 최소 주문 수량보다 작습니다.");
		row.put("min_order_qty", min);
		row.put("max_order_qty", max);
		row.put("shipping_fee", number(body.get("shipping_fee"), BigDecimal.ZERO, "배송비"));
		row.put("free_shipping_amount", optionalNumber(body.get("free_shipping_amount"), "무료 배송 기준 금액"));
		row.put("bundle_ship_yn", yn(body.get("bundle_ship_yn"), "Y"));
		row.put("safety_stock_qty", number(body.get("safety_stock_qty"), BigDecimal.ZERO, "안전재고"));
		LocalDateTime start = dateTime(body.get("sale_start_at"), "판매 시작일시"), end = dateTime(body.get("sale_end_at"), "판매 종료일시");
		if (start != null && end != null && !end.isAfter(start)) throw new IllegalArgumentException("판매 종료일시가 시작일시보다 늦어야 합니다.");
		row.put("sale_start_at", start);
		row.put("sale_end_at", end);
		row.put("summary", optional(body, "summary", 500));
		row.put("description_html", DistHtml.clean(text(body.get("description_html"))));
		return row;
	}

	private String nextProductCode() {
		Map<String, Object> seq = Map.of("seq_name", "PRODUCT");
		mapper.nextSequence(seq);
		return "P" + dbNow().format(DateTimeFormatter.ofPattern("yyMMdd")) + "-" + String.format("%03d", mapper.currentSequence(seq));
	}

	/** 판매 상태 변경. 판매중은 판매가(유형 공통가)가 하나 이상 있어야 한다 */
	@Transactional
	public Map<String, Object> changeStatus(Map<String, Object> body) {
		long id = id(body.get("product_id"), "상품");
		Map<String, Object> product = mapper.product(Map.of("product_id", id));
		if (product == null) throw new IllegalArgumentException("상품을 찾을 수 없습니다.");
		String status = oneOf(body.get("sale_status"), SALE_STATUS, null, "판매 상태");
		String old = text(product.get("sale_status"));
		if (status.equals(old)) return ok("이미 같은 상태입니다.");
		if ("ON_SALE".equals(status) && mapper.currentSalePriceCount(Map.of("product_id", id)) == 0)
			throw new IllegalArgumentException("지금 적용되는 판매가가 없어 판매를 시작할 수 없습니다. 판매가를 먼저 넣어주세요.");
		Map<String, Object> row = new HashMap<>();
		row.put("product_id", id);
		row.put("sale_status", status);
		row.put("user_id", body.get("user_id"));
		mapper.updateSaleStatus(row);
		String reason = optional(body, "reason", 500);
		Map<String, Object> after = new LinkedHashMap<>();
		after.put("sale_status", status);
		if (reason != null) after.put("reason", reason);
		history(id, Set.of("PAUSED", "DISCONTINUED").contains(status) ? "STOP" : "UPDATE", Map.of("sale_status", old), after, body.get("user_id"));
		return ok("판매 상태를 바꿨습니다.");
	}

	/* ===================== 가격 ===================== */

	/** 판매가 추가: 같은 유형·거래처의 이전 가격은 새 가격 시작 시각에 닫는다. 이미 그 이후에 시작하는 가격이 있으면 거절(기간 겹침 방지) */
	@Transactional
	public Map<String, Object> addSalePrice(Map<String, Object> body, Object userId) {
		long id = existingProduct(body.get("product_id"));
		String type = oneOf(body.get("price_type"), PRICE_TYPES, null, "가격 유형");
		BigDecimal price = number(body.get("unit_price"), null, "판매가");
		if (price.signum() < 0 || (price.signum() == 0 && !"INTERNAL".equals(type)))
			throw new IllegalArgumentException("판매가는 0보다 커야 합니다(사내 대체가만 0원 가능).");
		String account = text(body.get("account_id"));
		if (!account.isEmpty()) {
			if (!"ACCOUNT".equals(type) && !"EVENT".equals(type)) throw new IllegalArgumentException("거래처 전용가는 거래처가·행사가에만 지정할 수 있습니다.");
			if (mapper.accountExists(Map.of("account_id", account)) == 0) throw new IllegalArgumentException("거래처를 찾을 수 없습니다: " + account);
		}
		LocalDateTime start = dateTime(body.get("start_at"), "적용 시작일시");
		LocalDateTime now = dbNow();
		if (start == null || start.isBefore(now)) start = now;
		Map<String, Object> row = new HashMap<>();
		row.put("product_id", id);
		row.put("price_type", type);
		row.put("account_id", account.isEmpty() ? null : account);
		row.put("unit_price", price);
		row.put("start_at", start);
		row.put("change_reason", optional(body, "change_reason", 500));
		row.put("user_id", userId);
		if (mapper.laterSalePrices(row) > 0)
			throw new IllegalArgumentException("이 시각 이후에 시작하는 같은 유형의 가격이 이미 있습니다. 예약된 가격을 확인해주세요.");
		mapper.closeSalePrices(row);
		mapper.insertSalePrice(row);
		return ok("판매가를 저장했습니다.");
	}

	/** 판매가 끝내기(예: 행사가 종료). 지금 또는 지정 시각에 닫는다 */
	@Transactional
	public Map<String, Object> endSalePrice(Map<String, Object> body) {
		long id = existingProduct(body.get("product_id"));
		LocalDateTime end = dateTime(body.get("end_at"), "종료일시");
		LocalDateTime now = dbNow();
		Map<String, Object> row = new HashMap<>();
		row.put("product_id", id);
		row.put("sale_price_id", id(body.get("sale_price_id"), "판매가"));
		row.put("end_at", end == null || end.isBefore(now) ? now : end);
		if (mapper.endSalePrice(row) == 0) throw new IllegalArgumentException("끝낼 수 있는 판매가가 아닙니다(이미 끝났거나 아직 시작 전).");
		return ok("판매가를 종료했습니다.");
	}

	/** 매입가 추가(날짜 단위): 이전 매입가는 새 시작일 전날로 닫는다 */
	@Transactional
	public Map<String, Object> addCostPrice(Map<String, Object> body, Object userId) {
		long id = existingProduct(body.get("product_id"));
		Map<String, Object> product = mapper.product(Map.of("product_id", id));
		BigDecimal cost = number(body.get("unit_cost"), null, "매입가");
		if (cost.signum() < 0) throw new IllegalArgumentException("매입가는 0원 이상입니다.");
		LocalDate start = date(body.get("start_date"), "적용 시작일");
		if (start == null) start = dbNow().toLocalDate();
		Map<String, Object> row = new HashMap<>();
		row.put("product_id", id);
		row.put("partner_id", product.get("partner_id"));
		row.put("unit_cost", cost);
		row.put("start_date", start);
		row.put("change_reason", optional(body, "change_reason", 500));
		row.put("user_id", userId);
		if (mapper.laterCostPrices(row) > 0) throw new IllegalArgumentException("이 날짜 이후에 시작하는 매입가가 이미 있습니다.");
		mapper.closeCostPrices(row);
		mapper.insertCostPrice(row);
		return ok("매입가를 저장했습니다.");
	}

	/* ===================== 판매 가능(직송 상품 기간별 수량) ===================== */

	@Transactional
	public Map<String, Object> saveAvailability(Map<String, Object> body) {
		long id = existingProduct(body.get("product_id"));
		long availabilityId = optionalId(body.get("availability_id"));
		LocalDate from = date(body.get("available_from"), "시작일");
		if (from == null) throw new IllegalArgumentException("시작일을 입력해주세요.");
		LocalDate to = date(body.get("available_to"), "종료일");
		if (to != null && to.isBefore(from)) throw new IllegalArgumentException("종료일이 시작일보다 빠릅니다.");
		BigDecimal qty = optionalNumber(body.get("available_qty"), "판매 가능 수량");
		if (qty != null && qty.signum() < 0) throw new IllegalArgumentException("판매 가능 수량은 0 이상입니다.");
		Map<String, Object> row = new HashMap<>();
		row.put("product_id", id);
		row.put("availability_id", availabilityId);
		row.put("available_from", from);
		row.put("available_to", to);
		row.put("available_qty", qty);
		row.put("status", oneOf(body.get("status"), AVAILABILITY, "AVAILABLE", "판매 가능 상태"));
		row.put("note", optional(body, "note", 500));
		row.put("user_id", body.get("user_id"));
		if (mapper.overlappingAvailability(row) > 0) throw new IllegalArgumentException("기간이 겹치는 판매 가능 정보가 이미 있습니다.");
		if (availabilityId > 0) {
			BigDecimal reserved = mapper.reservedQty(row);
			if (reserved == null) throw new IllegalArgumentException("판매 가능 정보를 찾을 수 없습니다.");
			if (qty != null && qty.compareTo(reserved) < 0)
				throw new IllegalArgumentException("이미 주문받은 수량(" + reserved.stripTrailingZeros().toPlainString() + ")보다 적게 줄일 수 없습니다.");
			mapper.updateAvailability(row);
		} else {
			mapper.insertAvailability(row);
		}
		return ok("판매 가능 정보를 저장했습니다.");
	}

	/* ===================== 이미지(1단계: 주소 입력) ===================== */

	@Transactional
	public Map<String, Object> addImage(Map<String, Object> body) {
		long id = existingProduct(body.get("product_id"));
		String url = text(body.get("image_url"));
		if (!url.matches("(?i)https?://\\S+")) throw new IllegalArgumentException("이미지 주소는 http:// 또는 https://로 시작해야 합니다.");
		Map<String, Object> row = new HashMap<>();
		row.put("product_id", id);
		row.put("image_type", oneOf(body.get("image_type"), IMAGE_TYPES, "MAIN", "이미지 종류"));
		row.put("image_url", url);
		row.put("user_id", body.get("user_id"));
		mapper.insertImage(row);
		history(id, "UPDATE", null, Map.of("image_added", url), body.get("user_id"));
		return ok("이미지를 추가했습니다.");
	}

	@Transactional
	public Map<String, Object> deleteImage(Object productId, Object imageId, String userId) {
		long id = existingProduct(productId);
		Map<String, Object> row = Map.of("product_id", id, "product_image_id", id(imageId, "이미지"));
		if (mapper.deleteImage(row) == 0) throw new IllegalArgumentException("이미지를 찾을 수 없습니다.");
		history(id, "UPDATE", null, Map.of("image_deleted", imageId), userId);
		return ok("이미지를 뺐습니다.");
	}

	/* ===================== 공통 ===================== */

	private LocalDateTime dbNow() {
		return LocalDateTime.parse(mapper.dbNow(), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
	}

	private long existingProduct(Object productId) {
		long id = id(productId, "상품");
		if (mapper.product(Map.of("product_id", id)) == null) throw new IllegalArgumentException("상품을 찾을 수 없습니다.");
		return id;
	}

	private void history(long productId, String type, Map<String, Object> before, Map<String, Object> after, Object userId) {
		Map<String, Object> row = new HashMap<>();
		row.put("product_id", productId);
		row.put("request_type", type);
		row.put("before_json", before == null ? null : toJson(before));
		row.put("after_json", toJson(after));
		row.put("user_id", text(userId).isEmpty() ? "SYSTEM" : text(userId));
		mapper.insertHqHistory(row);
	}

	private String toJson(Object value) {
		try {
			return json.writeValueAsString(value);
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("이력을 저장하지 못했습니다.");
		}
	}

	/**
	 * 식품 표시사항·상품정보제공고시(화면의 입력 칸을 화면이 JSON으로 묶어 보냄). 모양:
	 * {"type":"PROCESSED_FOOD","type_name":"가공식품","items":[{"key":"product_name","label":"제품명","value":"…"}]} — type은 고시만.
	 * 라벨을 같이 저장해 두므로 나중에 화면의 항목 목록이 바뀌어도 예전 값은 그대로 보인다. 값이 빈 항목은 빼고, 남는 게 없으면 null.
	 */
	String productInfo(Object value, String label, boolean typed) {
		if (value == null || (value instanceof String s && s.isBlank())) return null;
		JsonNode root;
		try {
			root = value instanceof String s ? json.readTree(s) : json.valueToTree(value);
		} catch (JsonProcessingException | IllegalArgumentException e) {
			throw new IllegalArgumentException(label + " 형식을 확인해주세요.");
		}
		if (root == null || !root.isObject() || !root.path("items").isArray()) throw new IllegalArgumentException(label + " 형식을 확인해주세요.");
		ArrayNode items = json.createArrayNode();
		for (JsonNode item : root.path("items")) {
			String itemLabel = item.path("label").asText("").trim(), itemValue = item.path("value").asText("").trim(), key = item.path("key").asText("").trim();
			if (itemValue.isEmpty()) continue;
			if (itemLabel.isEmpty() || itemLabel.length() > 100) throw new IllegalArgumentException(label + " 항목 이름을 확인해주세요.");
			if (itemValue.length() > 2000) throw new IllegalArgumentException(label + " '" + itemLabel + "'은(는) 2000자까지 입력할 수 있습니다.");
			if (!key.isEmpty() && !key.matches("[a-z0-9_]{1,40}")) throw new IllegalArgumentException(label + " 항목 코드를 확인해주세요.");
			ObjectNode out = items.addObject();
			if (!key.isEmpty()) out.put("key", key);
			out.put("label", itemLabel);
			out.put("value", itemValue);
		}
		if (items.size() > 60) throw new IllegalArgumentException(label + " 항목이 너무 많습니다.");
		if (items.isEmpty()) return null;
		ObjectNode result = json.createObjectNode();
		if (typed) {
			String type = root.path("type").asText("").trim(), typeName = root.path("type_name").asText("").trim();
			if (!type.matches("[A-Z_]{2,40}")) throw new IllegalArgumentException(label + " 종류를 골라주세요.");
			result.put("type", type);
			result.put("type_name", typeName.length() > 50 ? typeName.substring(0, 50) : typeName);
		}
		result.set("items", items);
		return result.toString();
	}

	private boolean sameJson(Object a, Object b) {
		if (a == null || b == null) return a == b;
		try {
			return json.readTree(a.toString()).equals(json.readTree(b.toString()));
		} catch (JsonProcessingException e) {
			return false;
		}
	}

	private String infoNote(Object value) {
		if (value == null) return null;
		try {
			return "(" + json.readTree(value.toString()).path("items").size() + "개 항목)";
		} catch (JsonProcessingException e) {
			return "(입력됨)";
		}
	}

	private static String lengthNote(Object html) {
		String value = html == null ? "" : html.toString();
		return value.isEmpty() ? null : "(HTML " + value.length() + "자)";
	}

	/** DB 값(BigDecimal·Timestamp 등)과 입력 값을 글자로 비교. 숫자는 0 뒤를 떼고 비교 */
	static boolean same(Object a, Object b) {
		return normalize(a).equals(normalize(b));
	}

	private static String normalize(Object value) {
		if (value == null) return "";
		if (value instanceof BigDecimal d) return d.stripTrailingZeros().toPlainString();
		if (value instanceof Number n) return new BigDecimal(n.toString()).stripTrailingZeros().toPlainString();
		if (value instanceof LocalDateTime t) return t.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
		String text = value.toString().trim();
		if (text.matches("-?\\d+(\\.\\d+)?")) return new BigDecimal(text).stripTrailingZeros().toPlainString();
		return text;
	}

	private static Map<String, Object> ok(String message) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("code", 200);
		result.put("message", message);
		return result;
	}

	static String text(Object value) {
		return value == null ? "" : value.toString().trim();
	}

	private static String required(Map<String, Object> body, String key, String label, int max) {
		String value = text(body.get(key));
		if (value.isEmpty()) throw new IllegalArgumentException(label + "을(를) 입력해주세요.");
		if (value.length() > max) throw new IllegalArgumentException(label + "은(는) " + max + "자까지입니다.");
		return value;
	}

	private static String optional(Map<String, Object> body, String key, int max) {
		String value = text(body.get(key));
		if (value.length() > max) throw new IllegalArgumentException(key + "은(는) " + max + "자까지입니다.");
		return value.isEmpty() ? null : value;
	}

	private static String code(Object value, String label) {
		String code = text(value).toUpperCase();
		if (!CODE.matcher(code).matches()) throw new IllegalArgumentException(label + "는 영문 대문자·숫자·-·_ 2~30자입니다.");
		return code;
	}

	static String oneOf(Object value, Set<String> allowed, String fallback, String label) {
		String text = text(value).toUpperCase();
		if (text.isEmpty() && fallback != null) return fallback;
		if (!allowed.contains(text)) throw new IllegalArgumentException(label + " 값을 확인해주세요: " + (text.isEmpty() ? "(없음)" : text));
		return text;
	}

	private static String yn(Object value, String fallback) {
		String text = text(value).toUpperCase();
		return "Y".equals(text) || "N".equals(text) ? text : fallback;
	}

	private static long id(Object value, String label) {
		long id = optionalId(value);
		if (id <= 0) throw new IllegalArgumentException(label + "을(를) 골라주세요.");
		return id;
	}

	private static long optionalId(Object value) {
		String text = text(value);
		if (text.isEmpty()) return 0;
		try {
			return Long.parseLong(text);
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("번호 형식을 확인해주세요: " + text);
		}
	}

	private static BigDecimal number(Object value, BigDecimal fallback, String label) {
		BigDecimal number = optionalNumber(value, label);
		if (number != null) return number;
		if (fallback == null) throw new IllegalArgumentException(label + "을(를) 입력해주세요.");
		return fallback;
	}

	private static BigDecimal optionalNumber(Object value, String label) {
		String text = text(value).replace(",", "");
		if (text.isEmpty()) return null;
		try {
			BigDecimal number = new BigDecimal(text);
			if (number.abs().compareTo(new BigDecimal("1000000000000")) >= 0) throw new NumberFormatException();
			return number;
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException(label + "은(는) 숫자로 입력해주세요: " + text);
		}
	}

	private static LocalDate date(Object value, String label) {
		String text = text(value);
		if (text.isEmpty()) return null;
		try {
			return LocalDate.parse(text.length() > 10 ? text.substring(0, 10) : text);
		} catch (DateTimeParseException e) {
			throw new IllegalArgumentException(label + " 형식(YYYY-MM-DD)을 확인해주세요.");
		}
	}

	private static LocalDateTime dateTime(Object value, String label) {
		String text = text(value).replace('T', ' ');
		if (text.isEmpty()) return null;
		try {
			if (text.length() == 10) return LocalDate.parse(text).atStartOfDay();
			return LocalDateTime.parse(text.length() == 16 ? text + ":00" : text.substring(0, 19), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
		} catch (DateTimeParseException | StringIndexOutOfBoundsException e) {
			throw new IllegalArgumentException(label + " 형식(YYYY-MM-DD HH:MM)을 확인해주세요.");
		}
	}

	private static LocalTime time(Object value) {
		String text = text(value);
		if (text.isEmpty()) return null;
		try {
			return LocalTime.parse(text.length() == 5 ? text : text.substring(0, 5));
		} catch (DateTimeParseException | StringIndexOutOfBoundsException e) {
			throw new IllegalArgumentException("출고 마감 시각 형식(HH:MM)을 확인해주세요.");
		}
	}
}

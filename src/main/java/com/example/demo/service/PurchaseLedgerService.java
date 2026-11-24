package com.example.demo.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.example.demo.mapper.PurchaseLedgerMapper;

/**
 * 매입 관리(ASP, 2026-11-23, 본사 전용).
 * <ul>
 * <li>매입 목록·합계: 입고 줄 전체(발주 입고·직접 구매·구매 이력)를 기간·거래처·공급사·과세 구분으로</li>
 * <li>공급사 청구 대조: 발주 입고는 발주 단가로 금액을 추정해 두고(ORDER_ESTIMATE), 구매 이력 가져오기로 받은 공급사 청구 줄과
 * 수량이 맞으면 공급사 공급가·부가세로 바꾼다(SUPPLIER). 수량이 다르면 QTY_DIFF로 표시만 하고 사람이 확인한다.
 * 재고 평균단가는 바꾸지 않는다(입고 때 공급가 기준으로 이미 반영됨).</li>
 * <li>단가 기준 확인: 공급사 발주 단가가 부가세 포함인지 별도인지 청구 내역으로 확인</li>
 * </ul>
 */
@Service
public class PurchaseLedgerService {
	static final int MAX_DAYS = 366;
	static final int MAX_PAGE = 500;
	static final BigDecimal TOLERANCE = BigDecimal.ONE;
	private static final Set<String> GROUPS = Set.of("supplier", "account", "month", "day");
	private static final Set<String> SOURCES = Set.of("ORDER", "DIRECT", "HISTORY");

	private final PurchaseLedgerMapper mapper;
	private final PurchasePriceBasis priceBasis;

	public PurchaseLedgerService(PurchaseLedgerMapper mapper, PurchasePriceBasis priceBasis) {
		this.mapper = mapper;
		this.priceBasis = priceBasis;
	}

	public Map<String, Object> summary(Map<String, Object> params) {
		Map<String, Object> q = filters(params);
		String group = text(params.get("group_by")).toLowerCase(Locale.ROOT);
		q.put("group_by", GROUPS.contains(group) ? group : "supplier");
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("from_date", q.get("from_date"));
		result.put("to_date", q.get("to_date"));
		result.put("group_by", q.get("group_by"));
		result.put("totals", mapper.totals(q));
		result.put("groups", mapper.summary(q));
		result.put("price_includes_vat_suppliers", priceBasis.suppliersIncludingVat());
		return result;
	}

	public Map<String, Object> lines(Map<String, Object> params) {
		Map<String, Object> q = filters(params);
		int size = Math.min(MAX_PAGE, Math.max(1, integer(params.get("size"), 100)));
		int page = Math.max(0, integer(params.get("page"), 0));
		q.put("limit", size);
		q.put("offset", page * size);
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("rows", mapper.lines(q));
		result.put("total_count", mapper.lineCount(q));
		result.put("page", page);
		result.put("size", size);
		return result;
	}

	/** 공급사 발주 단가가 부가세 포함인지: 청구 내역으로 확인한 결과와 지금 설정 */
	public Map<String, Object> priceCheck(Map<String, Object> params) {
		Map<String, Object> q = filters(params);
		List<Map<String, Object>> suppliers = new ArrayList<>();
		Map<String, Map<String, Object>> bySupplier = new LinkedHashMap<>();
		for (var row : mapper.priceCheckOrders(q))
			bySupplier.computeIfAbsent(text(row.get("supplier_code")), k -> base(row)).put("order", counts(row));
		for (var row : mapper.priceCheckHistory(q))
			bySupplier.computeIfAbsent(text(row.get("supplier_code")), k -> base(row)).put("history", counts(row));
		for (var entry : bySupplier.entrySet()) {
			Map<String, Object> s = entry.getValue();
			@SuppressWarnings("unchecked")
			Map<String, Object> order = (Map<String, Object>) s.get("order");
			@SuppressWarnings("unchecked")
			Map<String, Object> history = (Map<String, Object>) s.get("history");
			s.put("order_price_basis", verdict(order));
			s.put("supplier_unit_price_basis", verdict(history));
			s.put("configured_includes_vat", priceBasis.includesVat(entry.getKey()));
			String v = text(s.get("order_price_basis"));
			s.put("setting_ok", "NO_DATA".equals(v) || "MIXED".equals(v)
					|| ("INCLUDES_VAT".equals(v) == priceBasis.includesVat(entry.getKey())));
			suppliers.add(s);
		}
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("from_date", q.get("from_date"));
		result.put("to_date", q.get("to_date"));
		result.put("suppliers", suppliers);
		result.put("mismatch_samples", mapper.priceCheckSamples(q));
		result.put("unlinked_bill_lines", mapper.unlinkedBillLines(q));
		result.put("billed_not_received", mapper.billedNotReceived(q));
		return result;
	}

	/** 기간 안에 청구 줄이 있는 발주 품목을 다시 대조(설정을 바꾼 뒤 등) */
	public Map<String, Object> recheck(Map<String, Object> body) {
		Map<String, Object> q = filters(body);
		List<Long> ids = mapper.billedOrderItems(q);
		Map<String, Object> result = new LinkedHashMap<>(reconcile(ids));
		result.put("order_item_count", ids.size());
		return result;
	}

	/**
	 * 발주 품목별로 공급사 청구 합계와 우리 입고 줄을 대조한다. 수량이 같으면 공급사 금액을 입고 줄에 수량 비율로 나눠 넣고
	 * (마지막 줄이 나머지), 발주 단가로 계산한 공급가와 1원 넘게 다르면 PRICE_DIFF. 수량이 다르면 QTY_DIFF(금액은 그대로).
	 */
	@Transactional
	public Map<String, Integer> reconcile(Collection<Long> orderItemIds) {
		Map<String, Integer> counts = new LinkedHashMap<>(Map.of("checked", 0, "matched", 0, "price_diff", 0, "qty_diff", 0));
		List<Long> ids = orderItemIds == null ? List.of() : orderItemIds.stream().filter(Objects::nonNull).distinct().toList();
		for (int from = 0; from < ids.size(); from += 200) {
			List<Long> chunk = ids.subList(from, Math.min(ids.size(), from + 200));
			Map<String, Object> p = Map.of("ids", chunk);
			Map<Long, Map<String, Object>> bills = new HashMap<>();
			for (var b : mapper.billTotals(p))
				bills.put(Long.valueOf(text(b.get("purchase_order_item_id"))), b);
			Map<Long, List<Map<String, Object>>> receipts = new LinkedHashMap<>();
			for (var r : mapper.orderReceiptItems(p))
				receipts.computeIfAbsent(Long.valueOf(text(r.get("purchase_order_item_id"))), k -> new ArrayList<>()).add(r);
			Set<String> touched = new LinkedHashSet<>();
			for (var entry : receipts.entrySet()) {
				Map<String, Object> bill = bills.get(entry.getKey());
				if (bill == null)
					continue;
				String status = apply(bill, entry.getValue(), touched);
				counts.merge("checked", 1, Integer::sum);
				counts.merge(switch (status) {
				case "MATCHED" -> "matched";
				case "PRICE_DIFF" -> "price_diff";
				default -> "qty_diff";
				}, 1, Integer::sum);
			}
			for (String receiptId : touched)
				mapper.refreshReceiptTotals(Map.of("goods_receipt_id", receiptId));
		}
		return counts;
	}

	private String apply(Map<String, Object> bill, List<Map<String, Object>> items, Set<String> touched) {
		BigDecimal billedQty = number(bill.get("billed_qty"));
		BigDecimal receivedQty = items.stream().map(i -> number(i.get("received_qty"))).reduce(BigDecimal.ZERO, BigDecimal::add);
		String taxType = PurchaseAmounts.taxType(bill.get("tax_type"));
		if (taxType == null && number(bill.get("vat_amount")).signum() != 0)
			taxType = PurchaseAmounts.TAXABLE;
		if (billedQty.compareTo(receivedQty) != 0 || receivedQty.signum() == 0) {
			for (var item : items) {
				Map<String, Object> u = new HashMap<>();
				u.put("goods_receipt_item_id", item.get("goods_receipt_item_id"));
				u.put("tax_type", taxType);
				u.put("amount_check_status", "QTY_DIFF");
				mapper.applyCheck(u);
			}
			return "QTY_DIFF";
		}
		// 발주 단가로 계산한 공급가(지금 줄 금액이 이미 공급사 값일 수 있어 단가로 다시 계산)
		BigDecimal estimated = BigDecimal.ZERO;
		for (var item : items) {
			boolean includesVat = priceBasis.includesVat(item.get("supplier_code"));
			String t = taxType != null ? taxType : PurchaseAmounts.taxType(item.get("tax_type"));
			estimated = estimated.add(PurchaseAmounts.estimate(number(item.get("received_qty")), number(item.get("unit_price")), t,
					includesVat).supply());
		}
		BigDecimal supply = number(bill.get("supply_amount")), vat = number(bill.get("vat_amount")),
				total = number(bill.get("total_amount"));
		String status = estimated.subtract(supply).abs().compareTo(TOLERANCE) <= 0 ? "MATCHED" : "PRICE_DIFF";
		BigDecimal restSupply = supply, restVat = vat, restTotal = total;
		for (int n = 0; n < items.size(); n++) {
			var item = items.get(n);
			BigDecimal s, v, t;
			if (n == items.size() - 1) {
				s = restSupply;
				v = restVat;
				t = restTotal;
			} else {
				BigDecimal ratio = number(item.get("received_qty"));
				s = supply.multiply(ratio).divide(receivedQty, 2, RoundingMode.HALF_UP);
				v = vat.multiply(ratio).divide(receivedQty, 2, RoundingMode.HALF_UP);
				t = total.multiply(ratio).divide(receivedQty, 2, RoundingMode.HALF_UP);
				restSupply = restSupply.subtract(s);
				restVat = restVat.subtract(v);
				restTotal = restTotal.subtract(t);
			}
			Map<String, Object> u = new HashMap<>();
			u.put("goods_receipt_item_id", item.get("goods_receipt_item_id"));
			u.put("supply_amount", s);
			u.put("vat_amount", v);
			u.put("total_amount", t);
			u.put("tax_type", taxType);
			u.put("amount_check_status", status);
			mapper.applyCheck(u);
			touched.add(text(item.get("goods_receipt_id")));
		}
		return status;
	}

	private static Map<String, Object> base(Map<String, Object> row) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("supplier_id", row.get("supplier_id"));
		m.put("supplier_code", row.get("supplier_code"));
		m.put("supplier_name", row.get("supplier_name"));
		return m;
	}

	private static Map<String, Object> counts(Map<String, Object> row) {
		long lines = number(row.get("line_count")).longValue(), excl = number(row.get("excludes_vat_count")).longValue(),
				incl = number(row.get("includes_vat_count")).longValue();
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("line_count", lines);
		m.put("excludes_vat_count", excl);
		m.put("includes_vat_count", incl);
		m.put("other_count", lines - excl - incl);
		return m;
	}

	/** 과세 줄 90% 이상이 한쪽이면 그쪽. 비교할 줄이 없으면 NO_DATA */
	static String verdict(Map<String, Object> counts) {
		if (counts == null)
			return "NO_DATA";
		long lines = number(counts.get("line_count")).longValue();
		if (lines == 0)
			return "NO_DATA";
		if (number(counts.get("excludes_vat_count")).longValue() * 10 >= lines * 9)
			return "EXCLUDES_VAT";
		if (number(counts.get("includes_vat_count")).longValue() * 10 >= lines * 9)
			return "INCLUDES_VAT";
		return "MIXED";
	}

	/** 공통 조건: 기간(필수, 최대 1년)·거래처·공급사·출처·과세 구분·대조 상태·검색어 */
	static Map<String, Object> filters(Map<String, Object> params) {
		Map<String, Object> q = new HashMap<>();
		LocalDate from = date(params.get("from_date"), "from_date"), to = date(params.get("to_date"), "to_date");
		if (from.isAfter(to))
			throw new IllegalArgumentException("시작 날짜가 끝 날짜보다 늦습니다.");
		if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS)
			throw new IllegalArgumentException("한 번에 " + MAX_DAYS + "일까지 조회할 수 있습니다.");
		q.put("from_date", from.toString());
		q.put("to_date", to.toString());
		q.put("account_id", text(params.get("account_id")));
		String supplier = text(params.get("supplier_id"));
		if (!supplier.isEmpty() && !supplier.matches("\\d{1,18}"))
			throw new IllegalArgumentException("supplier_id 형식이 올바르지 않습니다.");
		q.put("supplier_id", supplier);
		String source = text(params.get("source")).toUpperCase(Locale.ROOT);
		q.put("source", SOURCES.contains(source) ? source : "");
		q.put("tax_type", text(params.get("tax_type")).toUpperCase(Locale.ROOT));
		q.put("check_status", text(params.get("check_status")).toUpperCase(Locale.ROOT));
		String keyword = text(params.get("keyword"));
		q.put("keyword", keyword.length() > 50 ? keyword.substring(0, 50) : keyword);
		return q;
	}

	private static LocalDate date(Object value, String name) {
		try {
			return LocalDate.parse(text(value));
		} catch (RuntimeException e) {
			throw new IllegalArgumentException(name + "는 YYYY-MM-DD 형식이어야 합니다.");
		}
	}

	private static int integer(Object value, int fallback) {
		try {
			return Integer.parseInt(text(value));
		} catch (RuntimeException e) {
			return fallback;
		}
	}

	static BigDecimal number(Object value) {
		if (value instanceof BigDecimal b)
			return b;
		if (value instanceof Number n)
			return new BigDecimal(n.toString());
		String v = text(value);
		return v.isEmpty() ? BigDecimal.ZERO : new BigDecimal(v);
	}

	private static String text(Object value) {
		return value == null ? "" : value.toString().trim();
	}
}

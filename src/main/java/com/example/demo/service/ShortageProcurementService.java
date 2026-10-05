package com.example.demo.service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Service;
import com.example.demo.mapper.ShortageProcurementMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Read-only demand aggregation and order preview. Quantities use the
 * ingredient's base unit.
 */
@Service
public class ShortageProcurementService {
	private final ShortageProcurementMapper mapper;
	private final SupplierIntegrationService suppliers;
	private final ObjectMapper json;

	public ShortageProcurementService(ShortageProcurementMapper mapper, SupplierIntegrationService suppliers,
			ObjectMapper json) {
		this.mapper = mapper;
		this.suppliers = suppliers;
		this.json = json;
	}

	public Map<String, Object> criteria(Map<String, Object> input) {
		Map<String, Object> p = new LinkedHashMap<>(input);
		if (text(p.get("account_id")).isBlank())
			throw new IllegalArgumentException("거래처를 선택해주세요.");
		String source = text(p.getOrDefault("source", "INVENTORY"));
		if (!Set.of("PLAN", "INVENTORY", "DRAFT").contains(source))
			throw new IllegalArgumentException("부족량 조회 기준을 확인해주세요.");
		if ("DRAFT".equals(source)) {
			if (!(p.get("draft_meals") instanceof List<?> meals) || meals.isEmpty() || meals.size() > 2000)
				throw new IllegalArgumentException("구성 중인 식단의 메뉴를 확인해주세요.");
			for (Object item : meals) {
				if (!(item instanceof Map<?, ?> meal) || text(meal.get("menu_id")).isBlank())
					throw new IllegalArgumentException("식단 메뉴를 확인해주세요.");
				BigDecimal servings = number(meal.get("planned_servings"));
				if (servings.signum() <= 0 || servings.scale() > 0 && servings.stripTrailingZeros().scale() > 0
						|| servings.compareTo(new BigDecimal("1000000")) > 0)
					throw new IllegalArgumentException("예정 식수는 1~1000000 사이의 정수로 입력해주세요.");
			}
		}
		LocalDate from = LocalDate.parse(text(p.getOrDefault("date_from", LocalDate.now().toString())));
		LocalDate to = LocalDate.parse(text(p.getOrDefault("date_to", from.plusDays(6).toString())));
		if (to.isBefore(from) || to.isAfter(from.plusDays(366)))
			throw new IllegalArgumentException("조회 기간은 1~367일 범위로 선택해주세요.");
		LocalDate delivery = LocalDate.parse(text(p.getOrDefault("delivery_date", from.toString())));
		if (delivery.isBefore(LocalDate.now()) || delivery.isAfter(to))
			throw new IllegalArgumentException("납품일은 오늘 이후, 조회 종료일 이전이어야 합니다.");
		int days = Integer.parseInt(text(p.getOrDefault("average_days", 30)));
		int coverage = Integer.parseInt(text(p.getOrDefault("coverage_days", 1)));
		if (days < 1 || days > 365 || coverage < 1 || coverage > 30)
			throw new IllegalArgumentException("평균 조회일수(1~365)와 확보일수(1~30)를 확인해주세요.");
		p.put("source", source);
		p.put("date_from", from.toString());
		p.put("date_to", to.toString());
		p.put("delivery_date", delivery.toString());
		p.put("average_days", days);
		p.put("coverage_days", coverage);
		if ("DRAFT".equals(source)) {
			List<Map<String,Object>> meals = new ArrayList<>();
			for (Object item : (List<?>) p.get("draft_meals")) {
				Map<String,Object> meal = new LinkedHashMap<>();
				((Map<?,?>) item).forEach((key,value) -> meal.put(key.toString(),value));
				LocalDate date = text(meal.get("meal_date")).isBlank() ? from : LocalDate.parse(text(meal.get("meal_date")));
				if (date.isBefore(from) || date.isAfter(to)) throw new IllegalArgumentException("식단 날짜가 조회 기간을 벗어났습니다.");
				meal.put("meal_date",date.toString()); meals.add(meal);
			}
			p.put("draft_meals",meals);
		}
		return p;
	}

	private static final class Bucket {
		String id, name, unit;
		BigDecimal demand = BigDecimal.ZERO, stock = BigDecimal.ZERO, incoming = BigDecimal.ZERO,
				safety = BigDecimal.ZERO, history = BigDecimal.ZERO;
		Set<String> dates = new HashSet<>(), menus = new TreeSet<>(), issues = new LinkedHashSet<>();
		NavigableMap<LocalDate,BigDecimal> demandByDate = new TreeMap<>(), incomingByDate = new TreeMap<>();
		BigDecimal pending = BigDecimal.ZERO, overdue = BigDecimal.ZERO;
		Set<String> pendingOrders = new LinkedHashSet<>();

		Bucket(Map<String, Object> row) {
			id = text(row.get("ingredient_id"));
			name = text(row.get("ingredient_name"));
			unit = QuantityUnits.unit(row.get("master_unit"));
		}

		BigDecimal quantity(Map<String, Object> row) {
			try {
				return QuantityUnits.convert(number(row.get("quantity")), row.get("base_unit"), unit);
			} catch (IllegalArgumentException e) {
				issues.add("단위 환산 확인: " + text(row.get("base_unit")) + " → " + unit);
				return BigDecimal.ZERO;
			}
		}
	}

	public Map<String, Object> shortages(Map<String, Object> input) {
		Map<String, Object> p = criteria(input);
		Map<String, Bucket> buckets = new TreeMap<>();
		for (var row : mapper.targets(p)) {
			Bucket b = buckets.computeIfAbsent(text(row.get("ingredient_id")), k -> new Bucket(row));
			b.safety = b.safety.max(b.quantity(row));
		}
		for (var row : mapper.stock(p)) {
			Bucket b = buckets.computeIfAbsent(text(row.get("ingredient_id")), k -> new Bucket(row));
			b.stock = b.stock.add(b.quantity(row));
		}
		for (var row : mapper.incoming(p)) {
			Bucket b = buckets.computeIfAbsent(text(row.get("ingredient_id")), k -> new Bucket(row));
			BigDecimal quantity = b.quantity(row);
			if (quantity.signum() <= 0) continue;
			String itemStatus = text(row.get("item_status")), orderStatus = text(row.get("order_status"));
			if (!Set.of("ORDERED","CONFIRMED","PARTIAL_RECEIVED","NOT_RECEIVED").contains(itemStatus)
					|| Set.of("SENDING","UNKNOWN").contains(orderStatus)) {
				b.pending = b.pending.add(quantity);
				b.pendingOrders.add(text(row.get("purchase_order_id")));
				b.issues.add("기존 주문 접수 확인 필요 · 확인 전 중복 발주할 수 없습니다.");
				continue;
			}
			LocalDate date = rowDate(row.get("delivery_date"), LocalDate.parse(text(p.get("date_from"))));
			if (date.isBefore(LocalDate.now())) {
				b.overdue = b.overdue.add(quantity);
				b.issues.add("납품일이 지난 미입고 주문이 있습니다. 수령·취소 여부를 먼저 확인해주세요.");
				continue;
			}
			b.incoming = b.incoming.add(quantity);
			b.incomingByDate.merge(date,quantity,BigDecimal::add);
		}
		if (!"INVENTORY".equals(p.get("source"))) {
			var demand = "DRAFT".equals(p.get("source")) ? mapper.draftDemand(p) : mapper.demand(p);
			for (var row : demand) {
				if (text(row.get("ingredient_id")).isBlank())
					throw new IllegalArgumentException("메뉴의 식재료 레시피가 없어 필요량을 계산할 수 없습니다: " + text(row.get("menu_id")));
				Bucket b = buckets.computeIfAbsent(text(row.get("ingredient_id")), k -> new Bucket(row));
				if (row.get("quantity") == null || number(row.get("quantity")).signum() <= 0)
					b.issues.add("레시피의 1인분 수량·단위를 확인해주세요.");
				BigDecimal quantity = b.quantity(row);
				b.demand = b.demand.add(quantity);
				b.demandByDate.merge(rowDate(row.get("meal_date"),LocalDate.parse(text(p.get("date_from")))),quantity,BigDecimal::add);
				b.menus.add(text(row.get("menu_name")));
			}
		} else {
			for (var row : mapper.history(p)) {
				Bucket b = buckets.computeIfAbsent(text(row.get("ingredient_id")), k -> new Bucket(row));
				b.history = b.history.add(b.quantity(row));
				b.dates.add(text(row.get("meal_date")));
			}
			for (Bucket b : buckets.values())
				if (!b.dates.isEmpty()) {
					BigDecimal daily = b.history.divide(BigDecimal.valueOf(b.dates.size()), 6, RoundingMode.HALF_UP);
					b.demand = daily.multiply(number(p.get("coverage_days")));
					// A configured target is a total target, not an additional copy of the same
					// demand.
					b.safety = b.safety.max(b.demand.multiply(new BigDecimal("1.10"))).subtract(b.demand)
							.max(BigDecimal.ZERO);
				}
		}
        Map<String,Object> q = new LinkedHashMap<>(p);
        if (!text(p.get("sold_to")).isBlank()) q.put("external_site_code", p.get("sold_to"));
        q.put("orderable_only", "N");
        List<Map<String,Object>> offers = suppliers.offers(q);
		List<Map<String, Object>> rows = new ArrayList<>();
		int unknown = 0;
		for (Bucket b : buckets.values()) {
			if (!"INVENTORY".equals(p.get("source")) && b.menus.isEmpty())
				continue;
			LocalDate delivery = LocalDate.parse(text(p.get("delivery_date")));
			if ("INVENTORY".equals(p.get("source"))) b.demandByDate.put(delivery,b.demand);
			BigDecimal balance = b.stock, shortage = BigDecimal.ZERO;
			LocalDate neededBy = null;
			Set<LocalDate> timeline = new TreeSet<>(b.demandByDate.keySet());
			timeline.addAll(b.incomingByDate.keySet());
			if (timeline.isEmpty()) timeline.add(delivery);
			for (LocalDate date : timeline) {
				balance = balance.add(b.incomingByDate.getOrDefault(date,BigDecimal.ZERO))
						.subtract(b.demandByDate.getOrDefault(date,BigDecimal.ZERO));
				BigDecimal deficit = b.safety.subtract(balance).max(BigDecimal.ZERO);
				if (deficit.signum()>0 && neededBy==null) neededBy=date;
				shortage=shortage.max(deficit);
				if (balance.signum()<0 && date.isBefore(delivery))
					b.issues.add("선택한 납품일보다 먼저 재료가 필요합니다. 납품일 또는 식단을 확인해주세요.");
			}
			if (b.demand.add(b.safety).signum() == 0) {
				unknown++;
				if (b.stock.signum() <= 0)
					b.issues.add("현재고 없음 · 필요량 미산정: 식단 구성에서 메뉴와 예정 식수를 입력하거나 안전재고 기준을 설정해주세요.");
			}
			if (shortage.signum() <= 0 && b.issues.isEmpty() && !Boolean.TRUE.equals(p.get("include_sufficient")))
				continue;
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("ingredient_id", b.id);
			row.put("ingredient_name", b.name);
			row.put("base_unit", b.unit);
			row.put("required_qty", b.demand);
			row.put("safe_stock_qty", b.safety);
			row.put("current_qty", b.stock);
			row.put("incoming_qty", b.incoming);
			row.put("pending_incoming_qty", b.pending);
			row.put("overdue_incoming_qty", b.overdue);
			row.put("pending_order_ids", b.pendingOrders);
			row.put("needed_by", neededBy == null ? "" : neededBy.toString());
			row.put("shortage_qty", shortage);
			row.put("menu_names", b.menus);
			row.put("issues", b.issues);
			List<Map<String, Object>> candidates = new ArrayList<>();
			for (var offer : offers)
				if (b.id.equals(text(offer.get("ingredient_id"))))
					candidates.add(candidate(offer, b.unit, shortage, b.issues.isEmpty()));
			candidates.sort(Comparator.comparing((Map<String, Object> c) -> !Boolean.TRUE.equals(c.get("orderable")))
					.thenComparing(c -> !"Y".equals(text(c.get("preferred_yn"))))
					.thenComparing(c -> number(c.get("estimated_amount"))));
			row.put("offers", candidates);
			var preferred = candidates.stream().filter(c -> "Y".equals(text(c.get("preferred_yn")))).toList();
			Map<String,Object> recommendedOffer = preferred.size()==1 ? preferred.get(0) : candidates.size()==1 ? candidates.get(0) : null;
			row.put("recommended_offer_id",recommendedOffer!=null && Boolean.TRUE.equals(recommendedOffer.get("orderable"))
					&& "AVAILABLE".equals(recommendedOffer.get("orderability_status"))
					? recommendedOffer.get("supplier_offer_id") : null);
			rows.add(row);
		}
		rows.sort(Comparator.comparing(r -> text(r.get("ingredient_name"))));
		return Map.of("criteria", p, "ingredients", rows, "unconfigured_count", unknown, "checked_at",
				java.time.LocalDateTime.now().toString());
	}

	private Map<String, Object> candidate(Map<String, Object> offer, String unit, BigDecimal shortage, boolean valid) {
		Map<String, Object> c = new LinkedHashMap<>(offer);
		BigDecimal pack = BigDecimal.ZERO, qty = BigDecimal.ZERO;
		String reason = text(offer.get("availability_reason"));
		try {
			pack = QuantityUnits.convert(number(offer.get("base_qty")), offer.get("base_unit"), unit);
			qty = recommended(shortage, pack, number(offer.get("minimum_order_qty")),
					number(offer.get("order_increment_qty")), text(offer.get("decimal_order_allowed")));
		} catch (IllegalArgumentException e) {
			valid = false;
			reason = e.getMessage();
		}
		boolean orderable = valid && shortage.signum() > 0 && "Y".equals(offer.get("orderable_yn"))
				&& "ACTIVE".equals(offer.get("product_status")) && Set.of("WELSTORY", "OURHOME").contains(text(offer.get("supplier_code")));
		c.put("orderable", orderable);
		if (!orderable) {
			c.put("orderability_status", "UNAVAILABLE");
			c.put("orderability_reason", reason.isBlank() ? "필요량·단위·기존 주문 상태를 먼저 확인해주세요." : reason);
		}
		c.put("availability_reason", reason);
		c.put("package_base_qty", pack);
		c.put("recommended_qty", qty);
		c.put("estimated_amount", qty.multiply(number(offer.get("purchase_price"))).setScale(2, RoundingMode.HALF_UP));
		return c;
	}

	static BigDecimal recommended(BigDecimal shortage, BigDecimal pack, BigDecimal minimum, BigDecimal increment,
			String decimals) {
		if (pack.signum() <= 0 || minimum.signum() <= 0 || increment.signum() <= 0)
			throw new IllegalArgumentException("포장용량·최소수량·발주배수 확인 필요");
		if (shortage.signum() <= 0)
			return BigDecimal.ZERO;
		BigDecimal step = increment.stripTrailingZeros();
		if (!"Y".equals(decimals) && step.scale() > 0) {
			BigInteger numerator = step.unscaledValue(), denominator = BigInteger.TEN.pow(step.scale());
			step = new BigDecimal(numerator.divide(numerator.gcd(denominator)));
		}
		BigDecimal needed = shortage.divide(pack, 12, RoundingMode.CEILING).max(minimum);
		BigDecimal qty = needed.divide(step, 0, RoundingMode.CEILING).multiply(step).stripTrailingZeros();
		OrderWorkflowService.validateQuantity(qty, minimum, increment, decimals);
		return qty;
	}

	@SuppressWarnings("unchecked")
	public Map<String, Object> preview(JsonNode payload) {
		Map<String, Object> p = json.convertValue(payload.path("criteria"), Map.class);
		Map<String, Object> result = shortages(p);
		List<Map<String, Object>> available = (List<Map<String, Object>>) result.get("ingredients");
		JsonNode selections = payload.path("items");
		if (!selections.isArray() || selections.isEmpty())
			throw new IllegalArgumentException("발주할 부족 식재료를 선택해주세요.");
		Set<String> seen = new HashSet<>();
		List<Map<String, Object>> rows = new ArrayList<>();
		BigDecimal total = BigDecimal.ZERO;
		for (JsonNode selection : selections) {
			String id = selection.path("ingredient_id").asText();
			if (!seen.add(id))
				throw new IllegalArgumentException("동일 식재료를 중복 선택할 수 없습니다: " + id);
			Map<String, Object> shortage = available.stream().filter(r -> id.equals(r.get("ingredient_id"))).findFirst()
					.orElseThrow(() -> new IllegalArgumentException("부족량이 변경되었습니다. 다시 조회해주세요: " + id));
			String offerId = selection.path("supplier_offer_id").asText();
			Map<String, Object> offer = ((List<Map<String, Object>>) shortage.get("offers")).stream()
					.filter(o -> offerId.equals(text(o.get("supplier_offer_id")))).findFirst()
					.orElseThrow(() -> new IllegalArgumentException("공급상품을 다시 선택해주세요."));
			if (!Boolean.TRUE.equals(offer.get("orderable")))
				throw new IllegalArgumentException(
						"발주 불가: " + shortage.get("ingredient_name") + " · " + offer.get("availability_reason"));
			boolean confirmed = selection.path("delivery_terms_confirmed").asBoolean(false);
			if (!"AVAILABLE".equals(offer.get("orderability_status")) && !confirmed)
				throw new IllegalArgumentException("납품·마감 조건을 먼저 확인해주세요: " + shortage.get("ingredient_name"));
			BigDecimal qty = number(selection.path("order_qty").asText());
			OrderWorkflowService.validateQuantity(qty, number(offer.get("minimum_order_qty")),
					number(offer.get("order_increment_qty")), text(offer.get("decimal_order_allowed")));
			if (qty.compareTo(number(offer.get("recommended_qty"))) > 0)
				throw new IllegalArgumentException("현재 부족량의 권장 발주수량을 초과했습니다. 기발주량을 확인해주세요: " + id);
			Map<String, Object> row = new LinkedHashMap<>(shortage);
			row.remove("offers");
			row.put("offer", offer);
			row.put("delivery_terms_confirmed", confirmed);
			row.put("order_qty", qty);
			BigDecimal amount = qty.multiply(number(offer.get("purchase_price"))).setScale(2, RoundingMode.HALF_UP);
			row.put("item_amount", amount);
			row.put("ordered_base_qty", qty.multiply(number(offer.get("package_base_qty"))));
			row.put("tax_type", text(offer.get("tax_type")));
			// Supplier documentation specifies tax classification, but not whether price
			// includes VAT.
			boolean exempt = "No tax".equalsIgnoreCase(text(offer.get("tax_type")));
			row.put("supply_amount", exempt ? amount : null);
			row.put("vat_amount", exempt ? BigDecimal.ZERO : null);
			rows.add(row);
			total = total.add(amount);
		}
		return Map.of("criteria", result.get("criteria"), "items", rows, "item_amount", total);
	}

	public void validateSubmission(Map<String, Object> criteria, List<Map<String, Object>> rows) {
		var payload = json.createObjectNode();
		payload.set("criteria", json.valueToTree(criteria));
		var items = payload.putArray("items");
		for (var row : rows)
			items.addObject().put("ingredient_id", text(row.get("ingredient_id")))
					.put("supplier_offer_id", text(row.get("supplier_offer_id")))
					.put("order_qty", text(row.get("order_qty")))
					.put("delivery_terms_confirmed", Boolean.TRUE.equals(row.get("delivery_terms_confirmed")));
		preview(payload);
	}

	static String text(Object value) {
		return value == null ? "" : value.toString();
	}

	private static LocalDate rowDate(Object value, LocalDate fallback) {
		String date = text(value);
		return date.isBlank() ? fallback : LocalDate.parse(date.substring(0,Math.min(10,date.length())));
	}

	static BigDecimal number(Object value) {
		return text(value).isBlank() ? BigDecimal.ZERO : new BigDecimal(text(value));
	}
}

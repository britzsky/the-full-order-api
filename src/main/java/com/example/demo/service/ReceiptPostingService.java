package com.example.demo.service;

import static com.example.demo.service.OrderWorkflowService.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.example.demo.mapper.OrderWorkflowMapper;
import com.example.demo.mapper.SupplierIntegrationMapper;
import com.fasterxml.jackson.databind.JsonNode;

/** 자동·수동 입고의 중복 확인과 이동평균 재고 반영을 한 트랜잭션으로 처리한다. */
@Service
public class ReceiptPostingService {
	private final SupplierIntegrationMapper mapper;
	private final OrderWorkflowMapper orders;

	public ReceiptPostingService(SupplierIntegrationMapper mapper, OrderWorkflowMapper orders) {
		this.mapper = mapper;
		this.orders = orders;
	}

	@Transactional
	public Map<String, Object> receive(JsonNode payload) {
		Map<String, Object> owner = new LinkedHashMap<>();
		owner.put("account_id", required(payload, "account_id"));
		owner.put("purchase_order_id", required(payload, "purchase_order_id"));
		Map<String, Object> order = orders.lockOrder(owner);
		if (order == null)
			throw new IllegalArgumentException("발주서를 찾을 수 없습니다.");
		if (!Set.of("ORDERED", "CONFIRMED", "PARTIAL_RECEIVED", "RECEIVED", "NOT_RECEIVED")
				.contains(text(order.get("status"))))
			throw new IllegalArgumentException("주문 확정 상태를 확인한 뒤 입고 처리하세요.");
		String key = required(payload, "receipt_key");
		if (!key.matches("[A-Za-z0-9-]{1,60}"))
			throw new IllegalArgumentException("입고 요청키 형식이 올바르지 않습니다.");
		String date = required(payload, "receipt_date");
		LocalDate.parse(date);
		JsonNode items = payload.path("items");
		if (!items.isArray() || items.isEmpty())
			throw new IllegalArgumentException("입고 품목이 필요합니다.");
		List<Map<String, Object>> lines = orders.items(owner);
		int posted = 0;
		Set<String> seen = new HashSet<>();
		for (JsonNode item : items) {
			String sequence = required(item, "client_ord_item");
			if (!seen.add(sequence))
				throw new IllegalArgumentException("같은 입고 품목이 중복되었습니다.");
			Map<String, Object> row = new LinkedHashMap<>(
					lines.stream().filter(l -> sequence.equals(text(l.get("client_ord_item")))).findFirst()
							.orElseThrow(() -> new IllegalArgumentException("주문에 없는 품목입니다.")));
			BigDecimal qty = number(required(item, "received_qty"));
			if (qty.signum() <= 0 || qty.scale() > 3)
				throw new IllegalArgumentException("이번 입고량은 양수이며 소수점 3자리 이하여야 합니다.");
			row.put("provider_receipt_key", "MANUAL|" + owner.get("purchase_order_id") + "|" + key);
			row.put("provider_line_key", row.get("provider_receipt_key") + "|" + sequence);
			row.put("received_qty", qty);
			row.put("receipt_date", date);
			row.put("order_unit", row.get("order_unit"));
			row.put("user_id", payload.path("user_id").asText(""));
			row.put("location_id", item.path("location_id").asText(text(row.get("default_location_id"))));
			row.put("unit_price", row.get("unit_price_snapshot"));
			row.put("supply_amount", qty.multiply(number(row.get("unit_price_snapshot"))));
			row.put("vat_amount", BigDecimal.ZERO);
			row.put("total_amount", row.get("supply_amount"));
			row.put("provider_attributes", item.toString());
			if (post(row))
				posted++;
		}
		return Map.of("purchase_order_id", owner.get("purchase_order_id"), "received_item_count", posted, "status",
				orders.order(owner).get("status"));
	}

	/** 같은 외부 입고의 재조회는 무시하고, 정정·수동 선입고 충돌은 대사 대상으로 돌린다. */
	@Transactional
	public boolean post(Map<String, Object> row) {
		if (orders.lockOrder(row) == null)
			throw new IllegalArgumentException("입고 대상 주문을 찾을 수 없습니다.");
		row.put("lock_rows", true);
		Map<String, Object> prior = orders.receiptItem(row);
		if (prior != null) {
			if (number(prior.get("received_qty")).compareTo(number(row.get("received_qty"))) != 0
					|| number(prior.get("supply_amount")).compareTo(number(row.get("supply_amount"))) != 0)
				throw new IllegalArgumentException("기존 입고와 수량·금액이 다릅니다. 입고 정정 대사가 필요합니다.");
			return false;
		}
		if (!text(row.get("provider_receipt_key")).startsWith("MANUAL|") && orders.manualReceiptCount(row) > 0)
			throw new IllegalArgumentException("수동 입고가 존재합니다. 공급사 입고와 대사 후 반영하세요.");
		Map<String, Object> current = orders.items(row).stream()
				.filter(l -> text(l.get("purchase_order_item_id")).equals(text(row.get("purchase_order_item_id"))))
				.findFirst().orElseThrow(() -> new IllegalArgumentException("입고 품목 연결이 없습니다."));
		BigDecimal qty = number(row.get("received_qty"));
		if (qty.signum() <= 0
				|| qty.add(number(current.get("received_order_qty"))).compareTo(number(current.get("order_qty"))) > 0)
			throw new IllegalArgumentException("입고량이 주문 잔량을 초과합니다. 초과입고 대사가 필요합니다.");
		BigDecimal conversion = number(current.get("base_qty_per_order_unit"));
		// Inventory identity and conversion always come from the saved order, never provider input.
		for (String field : List.of("account_ingredient_product_id", "supplier_product_id", "base_unit", "order_unit"))
			row.put(field, current.get(field));
		if (conversion.signum() <= 0)
			throw new IllegalArgumentException("입고 단위 환산값을 확인하세요.");
		BigDecimal base = qty.multiply(conversion), amount = number(row.get("supply_amount"));
		if (amount.signum() < 0)
			throw new IllegalArgumentException("입고 금액을 확인하세요.");
		row.put("base_received_qty", base);
		row.put("receipt_amount", amount);
		row.put("receipt_base_unit_cost", amount.divide(base, 6, RoundingMode.HALF_UP));
		row.put("ordered_qty", current.get("order_qty"));
		// Prefer the actual receipt origin; otherwise retain the immutable order snapshot.
		String origin = ProductOrigins.describe(row.get("provider_attributes"));
		if ("원산지 미확인".equals(origin)) {
			try {
				var snapshot = new com.fasterxml.jackson.databind.ObjectMapper().readTree(text(current.get("product_snapshot")));
				origin = ProductOrigins.describe(snapshot);
				if ("원산지 미확인".equals(origin)) origin = ProductOrigins.describe(snapshot.path("provider_realtime_product"));
				if ("원산지 미확인".equals(origin)) origin = ProductOrigins.describe(snapshot.path("provider_attributes").isTextual() ? snapshot.path("provider_attributes").asText() : snapshot.path("provider_attributes"));
			} catch (Exception ignored) { /* Historical orders may not have a snapshot. */ }
		}
		row.put("origin_name_snapshot", origin);
		if (text(row.get("location_id")).isBlank())
			row.put("location_id", "L999");
		row.put("goods_receipt_id",
				UUID.nameUUIDFromBytes(
						(row.get("account_id") + "|" + row.get("supplier_id") + "|" + row.get("provider_receipt_key"))
								.getBytes(StandardCharsets.UTF_8))
						.toString());
		row.put("reconciliation_status", "MATCHED");
		mapper.insertGoodsReceipt(row);
		if (mapper.insertGoodsReceiptItem(row) == 0)
			throw new IllegalStateException("입고행을 저장하지 못했습니다.");
		linkSourceAfterReceipt(row, current);
		orders.ensureBalance(row);
		Map<String, Object> before = mapper.inventoryBalanceForUpdate(row);
		row.put("quantity_before", before.get("current_base_qty"));
		row.put("average_unit_cost_before", before.get("average_unit_cost"));
		mapper.upsertInventoryAtAverageCost(row);
		Map<String, Object> after = mapper.inventoryBalanceForUpdate(row);
		row.put("quantity_after", after.get("current_base_qty"));
		row.put("average_unit_cost_after", after.get("average_unit_cost"));
		row.put("movement_id", UUID.randomUUID().toString());
		mapper.insertInventoryLot(row);
		mapper.insertCostedInventoryMovement(row);
		mapper.markReceiptItemPosted(row);
		mapper.addReceivedOrderQty(row);
		mapper.refreshPurchaseOrderReceiptStatus(row);
		orders.refreshReceipt(row);
		return true;
	}

	private void linkSourceAfterReceipt(Map<String, Object> receipt, Map<String, Object> current) {
		if (!"PENDING".equals(text(current.get("ingredient_link_status")))) return;
		Map<String, Object> p = new LinkedHashMap<>(current);
		p.put("account_id", receipt.get("account_id"));
		p.put("purchase_order_id", receipt.get("purchase_order_id"));
		p.put("ingredient_link_status", "CONFLICT");
		if (!text(p.get("source_ingredient_id")).isBlank() && orders.lockSourceIngredient(p) != null) {
			List<Map<String, Object>> links = orders.sourceLinks(p);
			boolean same = links.stream().anyMatch(l -> text(l.get("supplier_product_id")).equals(text(p.get("supplier_product_id"))));
			boolean unchanged = text(p.get("link_context_snapshot")).equals(linkContextFingerprint(orders.linkContext(p)));
			if (same || (unchanged && links.isEmpty() && orders.linkReceivedProduct(p) == 1)) p.put("ingredient_link_status", "LINKED");
		}
		// A later user mapping wins; receipt still posts to the actual purchased product.
		orders.updateLinkStatus(p);
	}
}

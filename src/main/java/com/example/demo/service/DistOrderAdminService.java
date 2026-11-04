package com.example.demo.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Pattern;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.demo.mapper.DistAdminMapper;
import com.example.demo.mapper.DistOrderAdminMapper;
import com.example.demo.mapper.DistShopMapper;

/**
 * 더채움 유통 — 본사 주문 처리(2026-11-02, 3단계). 본사가 유통 관리자로 모든 주문을 본다(주문자·배송지 복호화 포함).
 * 주문 상태: 입금 대기(PENDING_PAYMENT) →(입금 확인) 주문 확정(PAID, 후불은 처음부터) →(직송 발주서·자사 출고 준비) 상품 준비(PREPARING)
 *            →(송장) 일부 출고(PARTIALLY_SHIPPED)/배송 중(SHIPPED) →(모든 배송 완료) 배송 완료(DELIVERED). 취소는 출고 전까지.
 * 품목 상태: ORDERED → PO_SENT(직송 발주서)/PREPARING(자사재고) → SHIPPED → DELIVERED, 또는 CANCELED.
 * 입금 확인·송장·배송 완료는 지금은 본사가 손으로 넣는다(은행 입금 통지·택배 연동은 업체를 정한 뒤). 환불도 본사가 계좌로 보낸 뒤 기록만 남긴다.
 * 자사재고(하츄핑) 재고 차감은 아직 없다(재고 단계).
 */
@Service
public class DistOrderAdminService {
	static final Set<String> HQ_CANCEL_REASONS = Set.of("OUT_OF_STOCK", "DEPOSIT_EXPIRED", "CUSTOMER_REQUEST", "DELAY", "ETC");
	private static final Pattern TRACKING = Pattern.compile("[A-Za-z0-9-]{6,30}");
	private static final DateTimeFormatter DB_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	private final DistOrderAdminMapper mapper;
	private final DistShopMapper shop;
	private final DistAdminMapper admin;
	private final DistCrypto crypto;

	public DistOrderAdminService(DistOrderAdminMapper mapper, DistShopMapper shop, DistAdminMapper admin, DistCrypto crypto) {
		this.mapper = mapper;
		this.shop = shop;
		this.admin = admin;
		this.crypto = crypto;
	}

	/* ===================== 조회 ===================== */

	public Map<String, Object> orders(Map<String, String> query) {
		Map<String, Object> p = new HashMap<>();
		p.put("status", text(query.get("status")).toUpperCase());
		p.put("buyer_type", text(query.get("buyer_type")).toUpperCase());
		long partner = longOrZero(query.get("partner_id"));
		p.put("partner_id", partner > 0 ? partner : null);
		p.put("from", date(query.get("from"), "시작일"));
		p.put("to", date(query.get("to"), "종료일"));
		p.put("keyword", text(query.get("keyword")));
		List<Map<String, Object>> rows = mapper.orders(p);
		String work = text(query.get("work"));
		if (!work.isEmpty()) rows = rows.stream().filter(row -> truthy(row.get(work))).toList();
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("orders", rows);
		result.put("work", mapper.workCounts());
		return result;
	}

	public Map<String, Object> order(Object orderId) {
		Map<String, Object> key = Map.of("order_id", longOrZero(orderId));
		Map<String, Object> order = mapper.order(key);
		if (order == null) throw new IllegalArgumentException("주문을 찾을 수 없습니다.");
		Map<String, Object> view = new LinkedHashMap<>(order);
		for (String field : List.of("orderer_name_enc", "orderer_phone_enc", "orderer_email_enc", "receipt_info_enc", "client_request_key"))
			view.remove(field);
		view.put("orderer_name", crypto.decrypt((String) order.get("orderer_name_enc")));
		view.put("orderer_phone", crypto.decrypt((String) order.get("orderer_phone_enc")));
		view.put("orderer_email", crypto.decrypt((String) order.get("orderer_email_enc")));
		Map<String, Object> rcpt = shop.recipient(key);
		Map<String, Object> recipient = new LinkedHashMap<>();
		if (rcpt != null) {
			recipient.put("name", crypto.decrypt((String) rcpt.get("recipient_name_enc")));
			recipient.put("phone", crypto.decrypt((String) rcpt.get("recipient_phone_enc")));
			recipient.put("zipcode", rcpt.get("zipcode"));
			recipient.put("address1", crypto.decrypt((String) rcpt.get("address1_enc")));
			recipient.put("address2", crypto.decrypt((String) rcpt.get("address2_enc")));
			recipient.put("delivery_request", rcpt.get("delivery_request"));
		}
		List<Map<String, Object>> items = mapper.items(key);
		for (Map<String, Object> item : items) item.put("remaining_qty", remaining(item));
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("order", view);
		result.put("recipient", recipient);
		result.put("items", items);
		result.put("payments", shop.payments(key));
		result.put("transactions", mapper.transactions(key));
		result.put("purchase_orders", mapper.purchaseOrders(key));
		result.put("purchase_order_items", mapper.purchaseOrderItems(key));
		result.put("shipments", mapper.shipments(key));
		result.put("claims", shop.claims(key));
		result.put("history", shop.history(key));
		result.put("actions", actions(order, items, shop.claims(key)));
		return result;
	}

	/** 화면에 보여 줄 버튼: 지금 상태에서 할 수 있는 일 */
	static Map<String, Object> actions(Map<String, Object> order, List<Map<String, Object>> items, List<Map<String, Object>> claims) {
		String status = text(order.get("order_status"));
		boolean open = !Set.of("CANCELED", "DELIVERED", "COMPLETED").contains(status);
		boolean paidOrCredit = Set.of("PAID", "PREPARING", "PARTIALLY_SHIPPED").contains(status);
		boolean shippedAny = items.stream().anyMatch(item -> ((BigDecimal) item.get("shipped_qty")).signum() > 0);
		Set<Object> poPartners = new LinkedHashSet<>();
		boolean ownStockToPrepare = false, shippable = false;
		for (Map<String, Object> item : items) {
			if ("DROP_SHIP".equals(item.get("fulfillment_type")) && "ORDERED".equals(item.get("item_status"))) poPartners.add(item.get("partner_id"));
			if ("OWN_STOCK".equals(item.get("fulfillment_type")) && "ORDERED".equals(item.get("item_status"))) ownStockToPrepare = true;
			if (((BigDecimal) item.get("remaining_qty")).signum() > 0 && !"CANCELED".equals(item.get("item_status"))) shippable = true;
		}
		Map<String, Object> actions = new LinkedHashMap<>();
		actions.put("confirm_deposit", "PENDING_PAYMENT".equals(status));
		actions.put("po_partner_ids", paidOrCredit ? new ArrayList<>(poPartners) : List.of());
		actions.put("prepare_own_stock", paidOrCredit && ownStockToPrepare);
		actions.put("add_shipment", paidOrCredit && shippable);
		actions.put("cancel", open && !shippedAny && claims.stream().noneMatch(claim -> "REQUESTED".equals(claim.get("status"))));
		return actions;
	}

	static BigDecimal remaining(Map<String, Object> item) {
		BigDecimal qty = (BigDecimal) item.get("qty"), canceled = (BigDecimal) item.get("canceled_qty"), shipped = (BigDecimal) item.get("shipped_qty");
		return qty.subtract(canceled).subtract(shipped).max(BigDecimal.ZERO);
	}

	@Transactional
	public Map<String, Object> saveNote(Object orderId, Map<String, Object> body) {
		Map<String, Object> row = new HashMap<>();
		row.put("order_id", existing(orderId));
		String note = text(body.get("admin_note"));
		if (note.length() > 1000) throw new IllegalArgumentException("메모는 1000자까지입니다.");
		row.put("admin_note", note.isEmpty() ? null : note);
		row.put("user_id", body.get("user_id"));
		mapper.saveNote(row);
		return ok("메모를 저장했습니다.");
	}

	/* ===================== 입금 확인 ===================== */

	/** 무통장 입금 확인: 금액은 결제 요청 금액과 같아야 한다(부분 입금은 받지 않음) */
	@Transactional
	public Map<String, Object> confirmDeposit(Object orderId, Map<String, Object> body) {
		long id = longOrZero(orderId);
		Map<String, Object> order = lock(id);
		if (!"PENDING_PAYMENT".equals(order.get("order_status"))) throw new IllegalArgumentException("입금 대기 중인 주문이 아닙니다.");
		Map<String, Object> payment = mapper.lockPayment(Map.of("order_id", id));
		if (payment == null || !"BANK_TRANSFER".equals(payment.get("payment_method")) || !Set.of("WAITING_DEPOSIT", "EXPIRED").contains(payment.get("status")))
			throw new IllegalArgumentException("입금 확인할 무통장 결제가 없습니다.");
		BigDecimal amount = (BigDecimal) payment.get("amount");
		String entered = text(body.get("amount")).replace(",", "");
		if (!entered.isEmpty() && decimal(entered).compareTo(amount) != 0)
			throw new IllegalArgumentException("입금액이 주문 금액(" + plain(amount) + "원)과 다릅니다. 금액이 다르면 주문자와 확인 후 처리해주세요.");
		LocalDateTime paidAt = dateTime(body.get("paid_at"));
		if (paidAt == null) paidAt = now();
		Object userId = body.get("user_id");
		Map<String, Object> row = new HashMap<>();
		row.put("order_id", id);
		row.put("payment_id", payment.get("payment_id"));
		row.put("amount", amount);
		row.put("paid_at", paidAt);
		row.put("depositor_name", optional(body, "depositor_name", 50));
		row.put("user_id", userId);
		mapper.markPaymentPaid(row);
		mapper.markOrderPaid(row);
		transaction(payment.get("payment_id"), "DEPOSIT", amount, null, null, optional(body, "note", 500), userId);
		history(id, null, "PAYMENT", text(order.get("payment_status")), "PAID", "입금 확인" + (row.get("depositor_name") == null ? "" : " · " + row.get("depositor_name")), userId);
		history(id, null, "ORDER", "PENDING_PAYMENT", "PAID", "입금 확인", userId);
		return ok("입금을 확인했습니다. 이제 발주서·출고를 진행할 수 있습니다.");
	}

	/* ===================== 직송 발주서 ===================== */

	/** 협력사 하나에 대해 아직 발주 안 한 직송 품목으로 발주서를 만든다(전달은 지금은 본사가 전화·문자·메일로) */
	@Transactional
	public Map<String, Object> createPurchaseOrder(Object orderId, Map<String, Object> body) {
		long id = longOrZero(orderId);
		Map<String, Object> order = lock(id);
		requirePaid(order);
		long partnerId = longOrZero(body.get("partner_id"));
		List<Map<String, Object>> targets = new ArrayList<>();
		for (Map<String, Object> item : mapper.items(Map.of("order_id", id)))
			if (((Number) item.get("partner_id")).longValue() == partnerId && "DROP_SHIP".equals(item.get("fulfillment_type"))
					&& "ORDERED".equals(item.get("item_status")) && remaining(item).signum() > 0) targets.add(item);
		if (targets.isEmpty()) throw new IllegalArgumentException("이 협력사에 발주할 직송 상품이 없습니다(이미 발주했거나 취소됨).");
		Object userId = body.get("user_id");
		BigDecimal total = BigDecimal.ZERO;
		for (Map<String, Object> item : targets) total = total.add(cost(item).multiply(remaining(item)));
		Map<String, Object> po = new HashMap<>();
		po.put("po_no", number("PO", "PO"));
		po.put("partner_id", partnerId);
		po.put("order_id", id);
		po.put("send_method", targets.get(0).get("po_send_method"));
		po.put("total_amount", total);
		po.put("expected_date", date(body.get("expected_date"), "출고 예정일"));
		po.put("note", optional(body, "note", 1000));
		po.put("user_id", userId);
		mapper.insertPurchaseOrder(po);
		for (Map<String, Object> item : targets) {
			Map<String, Object> line = new HashMap<>();
			line.put("purchase_order_id", po.get("purchase_order_id"));
			line.put("product_id", item.get("product_id"));
			line.put("order_item_id", item.get("order_item_id"));
			line.put("qty", remaining(item));
			line.put("unit_cost", cost(item));
			line.put("amount", cost(item).multiply(remaining(item)));
			mapper.insertPurchaseOrderItem(line);
			mapper.setItemStatus(Map.of("order_item_id", item.get("order_item_id"), "item_status", "PO_SENT"));
		}
		toPreparing(id, order, "발주서 " + po.get("po_no") + " · " + targets.get(0).get("partner_name"), userId);
		Map<String, Object> result = ok("발주서를 만들었습니다. 협력사에 전달한 뒤 '협력사 확인'을 눌러 주세요.");
		result.put("purchase_order_id", po.get("purchase_order_id"));
		result.put("po_no", po.get("po_no"));
		return result;
	}

	@Transactional
	public Map<String, Object> confirmPurchaseOrder(Object purchaseOrderId, Map<String, Object> body) {
		Map<String, Object> po = mapper.lockPurchaseOrder(Map.of("purchase_order_id", longOrZero(purchaseOrderId)));
		if (po == null) throw new IllegalArgumentException("발주서를 찾을 수 없습니다.");
		if (!"SENT".equals(po.get("status"))) throw new IllegalArgumentException("전달 상태인 발주서만 확인 처리할 수 있습니다.");
		Map<String, Object> row = new HashMap<>();
		row.put("purchase_order_id", po.get("purchase_order_id"));
		row.put("expected_date", date(body.get("expected_date"), "출고 예정일"));
		row.put("note", optional(body, "note", 1000));
		row.put("user_id", body.get("user_id"));
		mapper.confirmPurchaseOrder(row);
		history(((Number) po.get("order_id")).longValue(), null, "ORDER", null, "PO_CONFIRMED", "협력사 확인(본사 대리) " + po.get("po_no"), body.get("user_id"));
		return ok("협력사 확인으로 표시했습니다.");
	}

	/** 자사재고 상품 출고 준비 표시(재고 차감은 재고 단계에서) */
	@Transactional
	public Map<String, Object> prepareOwnStock(Object orderId, Map<String, Object> body) {
		long id = longOrZero(orderId);
		Map<String, Object> order = lock(id);
		requirePaid(order);
		int count = 0;
		for (Map<String, Object> item : mapper.items(Map.of("order_id", id)))
			if ("OWN_STOCK".equals(item.get("fulfillment_type")) && "ORDERED".equals(item.get("item_status"))) {
				mapper.setItemStatus(Map.of("order_item_id", item.get("order_item_id"), "item_status", "PREPARING"));
				count++;
			}
		if (count == 0) throw new IllegalArgumentException("출고 준비할 자사재고 상품이 없습니다.");
		toPreparing(id, order, "자사재고 출고 준비", body.get("user_id"));
		return ok("출고 준비로 바꿨습니다.");
	}

	private void toPreparing(long orderId, Map<String, Object> order, String reason, Object userId) {
		String status = text(order.get("order_status"));
		if ("PAID".equals(status)) {
			mapper.setOrderStatus(Map.of("order_id", orderId, "order_status", "PREPARING", "user_id", text(userId)));
			order.put("order_status", "PREPARING");
		}
		history(orderId, null, "ORDER", status, "PAID".equals(status) ? "PREPARING" : status, reason, userId);
	}

	/* ===================== 송장·배송 ===================== */

	/**
	 * 송장 등록. body: carrier_code, tracking_no, items[{order_item_id, qty}]. 한 송장은 한 출고지(같은 협력사 직송 또는 본사 창고)만.
	 */
	@Transactional
	public Map<String, Object> addShipment(Object orderId, Map<String, Object> body) {
		long id = longOrZero(orderId);
		Map<String, Object> order = lock(id);
		requirePaid(order);
		String carrier = text(body.get("carrier_code")).toUpperCase();
		if (mapper.carrierExists(Map.of("carrier_code", carrier)) == 0) throw new IllegalArgumentException("택배사를 골라주세요.");
		String tracking = text(body.get("tracking_no")).replace(" ", "");
		if (!TRACKING.matcher(tracking).matches()) throw new IllegalArgumentException("송장번호는 숫자·영문·- 6~30자입니다.");
		if (mapper.trackingExists(Map.of("carrier_code", carrier, "tracking_no", tracking)) > 0) throw new IllegalArgumentException("이미 등록된 송장번호입니다.");
		Map<Long, Map<String, Object>> items = new HashMap<>();
		for (Map<String, Object> item : mapper.items(Map.of("order_id", id))) items.put(((Number) item.get("order_item_id")).longValue(), item);
		if (!(body.get("items") instanceof List<?> lines) || lines.isEmpty()) throw new IllegalArgumentException("송장에 담을 상품을 골라주세요.");
		Map<Long, BigDecimal> picked = new LinkedHashMap<>();
		String fulfillment = null;
		Object partner = null;
		for (Object raw : lines) {
			Map<?, ?> line = raw instanceof Map<?, ?> m ? m : Map.of();
			long itemId = longOrZero(line.get("order_item_id"));
			Map<String, Object> item = items.get(itemId);
			if (item == null) throw new IllegalArgumentException("이 주문의 상품이 아닙니다.");
			BigDecimal qty = decimal(line.get("qty"));
			if (qty.signum() == 0) continue;
			if (qty.signum() < 0 || qty.stripTrailingZeros().scale() > 0) throw new IllegalArgumentException("출고 수량은 정수로 넣어주세요.");
			if (qty.compareTo(remaining(item)) > 0 || "CANCELED".equals(item.get("item_status")))
				throw new IllegalArgumentException(item.get("product_name") + ": 남은 출고 수량은 " + plain(remaining(item)) + "개입니다.");
			if (fulfillment == null) { fulfillment = text(item.get("fulfillment_type")); partner = item.get("partner_id"); }
			else if (!fulfillment.equals(item.get("fulfillment_type")) || ("DROP_SHIP".equals(fulfillment) && !Objects.equals(partner, item.get("partner_id"))))
				throw new IllegalArgumentException("한 송장에는 같은 출고지 상품만 담을 수 있습니다(협력사별·본사 창고 따로).");
			picked.merge(itemId, qty, BigDecimal::add);
		}
		if (picked.isEmpty()) throw new IllegalArgumentException("출고 수량을 넣어주세요.");
		boolean dropShip = "DROP_SHIP".equals(fulfillment);
		Object userId = body.get("user_id");
		Map<String, Object> shipment = new HashMap<>();
		shipment.put("order_id", id);
		shipment.put("ship_from_type", dropShip ? "PARTNER" : "WAREHOUSE");
		shipment.put("partner_id", dropShip ? partner : null);
		shipment.put("purchase_order_id", dropShip ? mapper.purchaseOrderFor(Map.of("order_id", id, "partner_id", partner)) : null);
		shipment.put("carrier_code", carrier);
		shipment.put("tracking_no", tracking);
		shipment.put("user_id", userId);
		try {
			mapper.insertShipment(shipment);
		} catch (DuplicateKeyException e) {
			throw new IllegalArgumentException("이미 등록된 송장번호입니다.");
		}
		for (Map.Entry<Long, BigDecimal> entry : picked.entrySet()) {
			Map<String, Object> row = Map.of("shipment_id", shipment.get("shipment_id"), "order_item_id", entry.getKey(), "qty", entry.getValue());
			mapper.insertShipmentItem(row);
			mapper.addShipped(row);
			if (dropShip) mapper.addPurchaseDone(row);
		}
		if (dropShip) mapper.refreshPurchaseOrders(Map.of("order_id", id));
		boolean all = mapper.items(Map.of("order_id", id)).stream().allMatch(item -> remaining(item).signum() == 0);
		String to = all ? "SHIPPED" : "PARTIALLY_SHIPPED";
		mapper.setShippingStatus(Map.of("order_id", id, "order_status", to, "user_id", text(userId)));
		history(id, null, "ORDER", text(order.get("order_status")), to, carrier + " " + tracking, userId);
		return ok(all ? "송장을 등록했습니다. 모든 상품이 출고되었습니다." : "송장을 등록했습니다. 아직 출고 안 된 상품이 있습니다.");
	}

	/** 배송 완료(택배 연동 전에는 본사가 확인 후 누른다). 모든 배송이 끝나면 주문도 배송 완료 */
	@Transactional
	public Map<String, Object> markDelivered(Object shipmentId, Map<String, Object> body) {
		Map<String, Object> shipment = mapper.lockShipment(Map.of("shipment_id", longOrZero(shipmentId)));
		if (shipment == null) throw new IllegalArgumentException("배송을 찾을 수 없습니다.");
		if (Set.of("DELIVERED", "CANCELED").contains(shipment.get("status"))) throw new IllegalArgumentException("이미 끝난 배송입니다.");
		long orderId = ((Number) shipment.get("order_id")).longValue();
		Map<String, Object> order = lock(orderId);
		Object userId = body.get("user_id");
		mapper.markDelivered(Map.of("shipment_id", shipment.get("shipment_id"), "user_id", text(userId)));
		mapper.markItemsDelivered(Map.of("order_id", orderId));
		boolean all = mapper.items(Map.of("order_id", orderId)).stream()
				.allMatch(item -> "CANCELED".equals(item.get("item_status")) || "DELIVERED".equals(item.get("item_status")));
		if (all && "SHIPPED".equals(order.get("order_status"))) {
			mapper.setShippingStatus(Map.of("order_id", orderId, "order_status", "DELIVERED", "user_id", text(userId)));
			history(orderId, null, "ORDER", "SHIPPED", "DELIVERED", "배송 완료", userId);
		} else {
			history(orderId, null, "ORDER", text(order.get("order_status")), text(order.get("order_status")), "배송 완료 " + shipment.get("tracking_no"), userId);
		}
		return ok(all ? "배송 완료로 바꿨습니다. 주문이 끝났습니다." : "이 배송을 완료로 바꿨습니다.");
	}

	/* ===================== 취소·환불 ===================== */

	/** 본사 취소(품절·입금 기한 지남·고객 전화 요청 등). 출고 전만. 입금된 주문은 환불 계좌 필요 */
	@Transactional
	public Map<String, Object> cancelByHq(Object orderId, Map<String, Object> body) {
		long id = longOrZero(orderId);
		Map<String, Object> order = lock(id);
		requireCancelable(id, order);
		if (shop.openCancelClaims(Map.of("order_id", id)) > 0) throw new IllegalArgumentException("처리 중인 취소 요청이 있습니다. 취소 요청에서 승인해주세요.");
		String reasonCode = text(body.get("reason_code")).toUpperCase();
		if (!HQ_CANCEL_REASONS.contains(reasonCode)) throw new IllegalArgumentException("취소 사유를 골라주세요.");
		Map<String, Object> claim = new HashMap<>();
		claim.put("order_id", id);
		claim.put("claim_no", number("CLAIM", "C"));
		claim.put("reason_code", reasonCode);
		claim.put("reason_detail", optional(body, "reason", 500));
		claim.put("status", "REQUESTED");
		claim.put("refund_amount", order.get("paid_amount"));
		claim.put("user_id", text(body.get("user_id")));
		shop.insertCancelClaim(claim);
		shop.insertCancelClaimItems(claim);
		completeCancel(id, order, ((Number) claim.get("claim_id")).longValue(), body, "본사 취소: " + reasonCode + (claim.get("reason_detail") == null ? "" : " · " + claim.get("reason_detail")));
		return ok(paid(order) ? "주문을 취소하고 환불을 기록했습니다." : "주문을 취소했습니다.");
	}

	/** 고객 취소 요청 승인 = 환불 후 취소 완료 */
	@Transactional
	public Map<String, Object> approveClaim(Object claimId, Map<String, Object> body) {
		Map<String, Object> claim = openCancelClaim(claimId);
		long orderId = ((Number) claim.get("order_id")).longValue();
		Map<String, Object> order = lock(orderId);
		requireCancelable(orderId, order);
		completeCancel(orderId, order, ((Number) claim.get("claim_id")).longValue(), body, "취소 요청 승인" + (claim.get("reason_detail") == null ? "" : " · " + claim.get("reason_detail")));
		return ok(paid(order) ? "취소 요청을 승인하고 환불을 기록했습니다." : "취소 요청을 승인했습니다.");
	}

	@Transactional
	public Map<String, Object> rejectClaim(Object claimId, Map<String, Object> body) {
		Map<String, Object> claim = openCancelClaim(claimId);
		String reason = optional(body, "reason", 500);
		if (reason == null) throw new IllegalArgumentException("거절 사유를 넣어주세요(주문자에게 보입니다).");
		Map<String, Object> row = new HashMap<>();
		row.put("claim_id", claim.get("claim_id"));
		row.put("status", "REJECTED");
		row.put("refund_amount", BigDecimal.ZERO);
		row.put("reject_reason", reason);
		row.put("user_id", text(body.get("user_id")));
		mapper.closeClaim(row);
		long orderId = ((Number) claim.get("order_id")).longValue();
		history(orderId, null, "ORDER", "CANCEL_REQUESTED", "CANCEL_REJECTED", reason, body.get("user_id"));
		return ok("취소 요청을 거절했습니다.");
	}

	private Map<String, Object> openCancelClaim(Object claimId) {
		Map<String, Object> claim = mapper.lockClaim(Map.of("claim_id", longOrZero(claimId)));
		if (claim == null || !"CANCEL".equals(claim.get("claim_type"))) throw new IllegalArgumentException("취소 요청을 찾을 수 없습니다.");
		if (!"REQUESTED".equals(claim.get("status"))) throw new IllegalArgumentException("이미 처리된 취소 요청입니다.");
		return claim;
	}

	private void requireCancelable(long orderId, Map<String, Object> order) {
		String status = text(order.get("order_status"));
		if ("CANCELED".equals(status)) throw new IllegalArgumentException("이미 취소된 주문입니다.");
		boolean shipped = mapper.items(Map.of("order_id", orderId)).stream().anyMatch(item -> ((BigDecimal) item.get("shipped_qty")).signum() > 0);
		if (shipped || Set.of("PARTIALLY_SHIPPED", "SHIPPED", "DELIVERED", "COMPLETED").contains(status))
			throw new IllegalArgumentException("이미 출고된 상품이 있어 취소할 수 없습니다(반품으로 처리 — 다음 단계).");
	}

	private static boolean paid(Map<String, Object> order) {
		return order.get("paid_amount") instanceof BigDecimal paid && paid.signum() > 0;
	}

	/** 취소 마무리: 판매 가능 수량 반환·품목·발주서·결제 취소, 입금된 돈은 환불 기록(본사가 계좌로 보낸 뒤) */
	private void completeCancel(long orderId, Map<String, Object> order, long claimId, Map<String, Object> body, String reason) {
		Object userId = body.get("user_id");
		boolean paid = paid(order);
		BigDecimal paidAmount = paid ? (BigDecimal) order.get("paid_amount") : BigDecimal.ZERO;
		Map<String, Object> refund = body.get("refund") instanceof Map<?, ?> m ? castMap(m) : Map.of();
		String bank = null, account = null, holder = null;
		if (paid) {
			bank = text(refund.get("bank_name"));
			account = text(refund.get("account_no")).replaceAll("[^0-9-]", "");
			holder = text(refund.get("holder"));
			if (bank.isEmpty() || account.length() < 6 || holder.isEmpty()) throw new IllegalArgumentException("입금된 주문입니다. 환불한 은행·계좌번호·예금주를 넣어주세요.");
			if (bank.length() > 10) bank = bank.substring(0, 10);
		}
		Map<String, Object> key = Map.of("order_id", orderId);
		Object orderedAt = order.get("ordered_at");
		Object orderDate = orderedAt instanceof LocalDateTime t ? t.toLocalDate() : text(orderedAt).substring(0, 10);
		for (Map<String, Object> item : mapper.items(key)) {
			if ("CANCELED".equals(item.get("item_status"))) continue;
			Map<String, Object> release = new HashMap<>();
			release.put("product_id", item.get("product_id"));
			release.put("qty", ((BigDecimal) item.get("qty")).subtract((BigDecimal) item.get("canceled_qty")));
			release.put("order_date", orderDate);
			shop.release(release);
		}
		shop.cancelItems(key);
		mapper.cancelPurchaseOrderItems(key);
		mapper.cancelPurchaseOrders(Map.of("order_id", orderId, "user_id", text(userId)));
		Map<String, Object> payment = mapper.lockPayment(key);
		if (paid && payment != null)
			transaction(payment.get("payment_id"), "REFUND", paidAmount, claimId, Map.of("bank", bank, "account", account, "holder", holder), reason, userId);
		mapper.cancelPayments(Map.of("order_id", orderId, "user_id", text(userId)));
		Map<String, Object> cancel = new HashMap<>();
		cancel.put("order_id", orderId);
		cancel.put("payment_status", paid ? "REFUNDED" : "NONE");
		cancel.put("refunded_amount", paidAmount);
		cancel.put("reason", reason.length() > 500 ? reason.substring(0, 500) : reason);
		cancel.put("user_id", text(userId));
		mapper.cancelOrder(cancel);
		Map<String, Object> close = new HashMap<>();
		close.put("claim_id", claimId);
		close.put("status", "COMPLETED");
		close.put("refund_amount", paidAmount);
		close.put("reject_reason", null);
		close.put("user_id", text(userId));
		mapper.closeClaim(close);
		mapper.settleClaimItems(Map.of("claim_id", claimId, "refund", paid));
		history(orderId, null, "ORDER", text(order.get("order_status")), "CANCELED", reason, userId);
		if (paid) history(orderId, null, "PAYMENT", "PAID", "REFUNDED", "환불 " + plain(paidAmount) + "원 · " + bank + " " + holder, userId);
	}

	/* ===================== 거래처 결제 설정 ===================== */

	public Map<String, Object> accountProfiles(String keyword, boolean onlySet) {
		Map<String, Object> p = new HashMap<>();
		p.put("keyword", text(keyword));
		p.put("only_set", onlySet && text(keyword).isEmpty());
		return Map.of("accounts", mapper.accountProfiles(p));
	}

	public Map<String, Object> accountProfile(String accountId) {
		Map<String, Object> row = mapper.accountProfile(Map.of("account_id", text(accountId)));
		if (row == null) throw new IllegalArgumentException("거래처를 찾을 수 없습니다.");
		return Map.of("profile", row);
	}

	/** 거래처 결제 설정 저장. 설정이 없는 거래처는 후불 허용(2단계 결정) — 처음 저장할 때 기본값도 후불 허용 */
	@Transactional
	public Map<String, Object> saveAccountProfile(Map<String, Object> body) {
		String accountId = text(body.get("account_id"));
		if (accountId.isEmpty() || mapper.accountProfile(Map.of("account_id", accountId)) == null) throw new IllegalArgumentException("거래처를 골라주세요.");
		Map<String, Object> row = new HashMap<>();
		row.put("account_id", accountId);
		String terms = text(body.get("payment_terms")).toUpperCase();
		if (terms.isEmpty()) terms = "MONTHLY";
		if (!Set.of("PREPAID", "MONTHLY").contains(terms)) throw new IllegalArgumentException("결제 조건을 확인해주세요.");
		row.put("payment_terms", terms);
		String limit = text(body.get("credit_limit")).replace(",", "");
		BigDecimal creditLimit = limit.isEmpty() ? null : decimal(limit);
		if (creditLimit != null && creditLimit.signum() < 0) throw new IllegalArgumentException("후불 한도는 0원 이상입니다.");
		row.put("credit_limit", creditLimit);
		int closeDay = (int) intOr(body.get("billing_close_day"), 31);
		if (closeDay < 1 || closeDay > 31) throw new IllegalArgumentException("청구 마감일은 1~31일입니다(31 = 말일).");
		row.put("billing_close_day", closeDay);
		int dueDays = (int) intOr(body.get("payment_due_days"), 30);
		if (dueDays < 1 || dueDays > 120) throw new IllegalArgumentException("결제 기한은 1~120일입니다.");
		row.put("payment_due_days", dueDays);
		row.put("credit_hold_yn", yn(body.get("credit_hold_yn"), "N"));
		row.put("order_enabled_yn", yn(body.get("order_enabled_yn"), "Y"));
		for (String[] f : new String[][] { { "tax_invoice_email", "100" }, { "tax_invoice_manager", "50" }, { "default_recipient_name", "50" },
				{ "default_phone", "30" }, { "default_zipcode", "10" }, { "default_address1", "255" }, { "default_address2", "255" } })
			row.put(f[0], optional(body, f[0], Integer.parseInt(f[1])));
		row.put("user_id", body.get("user_id"));
		mapper.saveAccountProfile(row);
		return ok("거래처 결제 설정을 저장했습니다.");
	}

	/* ===================== 공통 ===================== */

	private Map<String, Object> lock(long orderId) {
		Map<String, Object> order = mapper.lockOrder(Map.of("order_id", orderId));
		if (order == null) throw new IllegalArgumentException("주문을 찾을 수 없습니다.");
		return order;
	}

	private long existing(Object orderId) {
		long id = longOrZero(orderId);
		if (mapper.order(Map.of("order_id", id)) == null) throw new IllegalArgumentException("주문을 찾을 수 없습니다.");
		return id;
	}

	private static void requirePaid(Map<String, Object> order) {
		String status = text(order.get("order_status"));
		if ("PENDING_PAYMENT".equals(status)) throw new IllegalArgumentException("입금 확인 전 주문입니다. 입금을 먼저 확인해주세요.");
		if (!Set.of("PAID", "PREPARING", "PARTIALLY_SHIPPED").contains(status)) throw new IllegalArgumentException("지금 상태(" + status + ")에서는 할 수 없습니다.");
	}

	private static BigDecimal cost(Map<String, Object> item) {
		return item.get("unit_cost") instanceof BigDecimal value ? value : BigDecimal.ZERO;
	}

	private String number(String seqName, String prefix) {
		Map<String, Object> seq = Map.of("seq_name", seqName);
		admin.nextSequence(seq);
		return prefix + now().format(DateTimeFormatter.BASIC_ISO_DATE) + "-" + String.format("%04d", admin.currentSequence(seq));
	}

	private LocalDateTime now() {
		return LocalDateTime.parse(admin.dbNow(), DB_TIME);
	}

	private void transaction(Object paymentId, String type, BigDecimal amount, Long claimId, Map<String, String> refund, String reason, Object userId) {
		Map<String, Object> row = new HashMap<>();
		row.put("payment_id", paymentId);
		row.put("tx_type", type);
		row.put("amount", amount);
		row.put("claim_id", claimId);
		row.put("refund_bank_code", refund == null ? null : refund.get("bank"));
		row.put("refund_account_no_enc", refund == null ? null : crypto.encrypt(refund.get("account")));
		row.put("refund_holder_name", refund == null ? null : refund.get("holder"));
		row.put("reason", reason == null ? null : reason.length() > 500 ? reason.substring(0, 500) : reason);
		row.put("user_id", text(userId));
		mapper.insertTransaction(row);
	}

	private void history(long orderId, Object itemId, String kind, String from, String to, String reason, Object userId) {
		Map<String, Object> row = new HashMap<>();
		row.put("order_id", orderId);
		row.put("order_item_id", itemId);
		row.put("status_kind", kind);
		row.put("from_status", from);
		row.put("to_status", to);
		row.put("reason", reason == null ? null : reason.length() > 500 ? reason.substring(0, 500) : reason);
		row.put("source_type", "USER");
		row.put("user_id", text(userId));
		mapper.insertHistory(row);
	}

	private static Map<String, Object> ok(String message) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("code", 200);
		result.put("message", message);
		return result;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> castMap(Map<?, ?> map) {
		return (Map<String, Object>) map;
	}

	static boolean truthy(Object value) {
		return value instanceof Boolean b ? b : value instanceof Number n ? n.intValue() != 0 : "true".equalsIgnoreCase(text(value)) || "1".equals(text(value));
	}

	static String text(Object value) {
		return value == null ? "" : value.toString().trim();
	}

	private static String optional(Map<String, Object> body, String key, int max) {
		String value = text(body.get(key));
		if (value.length() > max) throw new IllegalArgumentException(key + "은(는) " + max + "자까지입니다.");
		return value.isEmpty() ? null : value;
	}

	private static String yn(Object value, String fallback) {
		String text = text(value).toUpperCase();
		return "Y".equals(text) || "N".equals(text) ? text : fallback;
	}

	private static long longOrZero(Object value) {
		try {
			return text(value).isEmpty() ? 0 : Long.parseLong(text(value));
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("번호 형식을 확인해주세요: " + value);
		}
	}

	private static long intOr(Object value, long fallback) {
		String text = text(value);
		if (text.isEmpty()) return fallback;
		try {
			return Long.parseLong(text);
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("숫자로 넣어주세요: " + text);
		}
	}

	private static BigDecimal decimal(Object value) {
		try {
			return new BigDecimal(text(value).replace(",", ""));
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("숫자로 넣어주세요: " + value);
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

	private static LocalDateTime dateTime(Object value) {
		String text = text(value).replace('T', ' ');
		if (text.isEmpty()) return null;
		try {
			if (text.length() == 10) return LocalDate.parse(text).atStartOfDay();
			return LocalDateTime.parse(text.length() == 16 ? text + ":00" : text.substring(0, 19), DB_TIME);
		} catch (DateTimeParseException | StringIndexOutOfBoundsException e) {
			throw new IllegalArgumentException("입금일시 형식(YYYY-MM-DD HH:MM)을 확인해주세요.");
		}
	}

	private static String plain(BigDecimal value) {
		return value == null ? "0" : value.stripTrailingZeros().toPlainString();
	}
}

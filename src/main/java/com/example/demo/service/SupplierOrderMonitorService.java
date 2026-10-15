package com.example.demo.service;

import java.math.BigDecimal;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 공급사 발주 상태 자동 확인(배치용).
 * ① 접수 불명확 주문(SENDING/UNKNOWN/미확정 품목이 남은 PARTIAL_FAILED)을 화면의 '주문 대사'와 같은 경로로 다시 조회한다.
 * ② 아워홈 주문의 출고 상태를 조회해 업무 알림으로 알린다. 재고는 바꾸지 않는다 —
 *    clhaqty가 누적/증분인지, 정정·반품·분할 입고가 어떻게 오는지 아워홈 회신 전이라 자동 입고는 하지 않는다(OURHOME_API_REVIEW.md).
 */
@Service
public class SupplierOrderMonitorService {
	private static final Logger log = LoggerFactory.getLogger(SupplierOrderMonitorService.class);
	private final JdbcTemplate jdbc;
	private final OrderWorkflowService orders;
	private final OurhomeSiteService ourhome;
	private final WorkspaceNotificationService notifications;
	private KakaoNotificationService kakao;

	@org.springframework.beans.factory.annotation.Autowired(required = false)
	public void setKakao(KakaoNotificationService kakao) { this.kakao = kakao; }

	public SupplierOrderMonitorService(JdbcTemplate jdbc, OrderWorkflowService orders, OurhomeSiteService ourhome,
			WorkspaceNotificationService notifications) {
		this.jdbc = jdbc;
		this.orders = orders;
		this.ourhome = ourhome;
		this.notifications = notifications;
	}

	/** 전송 후 5분(아워홈 반영 지연 대비)이 지난 최근 7일 접수 불명확 주문을 대사한다. */
	public Map<String, Object> reconcileUnresolved() {
		int checked = 0, resolved = 0, failed = 0;
		for (var order : jdbc.queryForList("""
				SELECT po.purchase_order_id,po.account_id,po.status FROM the_full_order.tb_account_purchase_order po
				JOIN the_full_order.tb_supplier s ON s.supplier_id=po.supplier_id
				WHERE s.supplier_code IN ('WELSTORY','OURHOME')
				  AND COALESCE(po.ordered_at,po.created_at) BETWEEN DATE_SUB(NOW(),INTERVAL 7 DAY) AND DATE_SUB(NOW(),INTERVAL 5 MINUTE)
				  AND (po.status IN ('SENDING','UNKNOWN') OR (po.status='PARTIAL_FAILED' AND EXISTS (
				    SELECT 1 FROM the_full_order.tb_account_purchase_order_item i WHERE i.purchase_order_id=po.purchase_order_id
				      AND i.item_status NOT IN ('ORDERED','CONFIRMED','FAILED','RECEIVED','PARTIAL_RECEIVED','NOT_RECEIVED','CANCELED'))))
				ORDER BY COALESCE(po.ordered_at,po.created_at) LIMIT 100
				""")) {
			checked++;
			try {
				var result = orders.reconcile(Map.of("account_id", order.get("account_id"), "purchase_order_id",
						order.get("purchase_order_id")));
				if (!Objects.equals(result.get("status"), order.get("status")))
					resolved++;
			} catch (RuntimeException e) {
				failed++;
				log.warn("접수 불명확 주문 자동 대사 실패 purchase_order_id={}: {}", order.get("purchase_order_id"), e.getMessage());
			}
		}
		return Map.of("checked", checked, "changed", resolved, "failed", failed);
	}

	/** 아워홈 주문(납품일 7일 전~내일)의 출고·삭제 상태를 사업장·납품일별로 한 번씩 조회해 알린다. */
	public Map<String, Object> checkOurhomeShipments() {
		var openOrders = jdbc.queryForList("""
				SELECT po.purchase_order_id,po.account_id,po.sold_to,po.client_ord,DATE_FORMAT(po.requested_delivery_date,'%Y-%m-%d') delivery_date
				FROM the_full_order.tb_account_purchase_order po JOIN the_full_order.tb_supplier s ON s.supplier_id=po.supplier_id
				WHERE s.supplier_code='OURHOME' AND po.status IN ('ORDERED','CONFIRMED','PARTIAL_RECEIVED')
				  AND po.requested_delivery_date BETWEEN DATE_SUB(CURRENT_DATE,INTERVAL 7 DAY) AND DATE_ADD(CURRENT_DATE,INTERVAL 1 DAY)
				  AND COALESCE(po.ordered_at,po.created_at)<DATE_SUB(NOW(),INTERVAL 10 MINUTE)
				ORDER BY po.requested_delivery_date,po.sold_to
				""");
		var groups = new LinkedHashMap<String, List<Map<String, Object>>>();
		for (var order : openOrders)
			groups.computeIfAbsent(order.get("sold_to") + "|" + order.get("delivery_date"), k -> new ArrayList<>()).add(order);
		int notified = 0, failedLookups = 0;
		for (var group : groups.values()) {
			var first = group.get(0);
			List<Map<String, String>> lines;
			try {
				lines = ourhome.orders(text(first.get("sold_to")), text(first.get("delivery_date"))).orders();
			} catch (RuntimeException e) {
				failedLookups++;
				log.warn("아워홈 출고 상태 조회 실패 site={} date={}: {}", first.get("sold_to"), first.get("delivery_date"), e.getMessage());
				continue;
			}
			for (var order : group)
				try {
					if (notify(order, items(order), lines))
						notified++;
				} catch (RuntimeException e) {
					log.warn("아워홈 출고 상태 알림 저장 실패 purchase_order_id={}: {}", order.get("purchase_order_id"), e.getMessage());
				}
		}
		return Map.of("orders", openOrders.size(), "lookups", groups.size(), "failed_lookups", failedLookups, "notified", notified);
	}

	/**
	 * 발주서 한 장의 아워홈 품목별 출고 상태(화면 '공급사 출고' 칸). 실시간 조회만 하고 저장·재고 반영은 하지 않는다.
	 * state: SHIPPED(출고완료, shipped_qty=clhaqty) / OPEN(출고 전) / DELETED(아워홈 삭제) / MISSING(아워홈 주문 조회에 없음)
	 */
	public Map<String, Object> ourhomeShipment(String account, String orderId) {
		var found = jdbc.queryForList("""
				SELECT po.purchase_order_id,po.sold_to,po.client_ord,DATE_FORMAT(po.requested_delivery_date,'%Y-%m-%d') delivery_date,s.supplier_code
				FROM the_full_order.tb_account_purchase_order po JOIN the_full_order.tb_supplier s ON s.supplier_id=po.supplier_id
				WHERE po.account_id=? AND po.purchase_order_id=?
				""", account, orderId);
		if (found.isEmpty())
			throw new IllegalArgumentException("발주서를 찾을 수 없습니다.");
		var order = found.get(0);
		if (!"OURHOME".equals(order.get("supplier_code")))
			return Map.of("supported", false, "lines", List.of());
		var lines = ourhome.orders(text(order.get("sold_to")), text(order.get("delivery_date"))).orders();
		List<Map<String, Object>> result = new ArrayList<>();
		for (var item : jdbc.queryForList("SELECT client_ord_item FROM the_full_order.tb_account_purchase_order_item WHERE purchase_order_id=? ORDER BY client_ord_item", orderId)) {
			var matches = lines.stream().filter(l -> text(order.get("client_ord")).equals(l.get("extnOrderKey"))
					&& text(item.get("client_ord_item")).equals(l.get("extnOrderNo"))).toList();
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("client_ord_item", item.get("client_ord_item"));
			if (matches.size() != 1)
				row.put("state", "MISSING");
			else if ("Y".equals(matches.get(0).get("delYn")))
				row.put("state", "DELETED");
			else if ("Y".equals(matches.get(0).get("prcsStatYn"))) {
				row.put("state", "SHIPPED");
				row.put("shipped_qty", matches.get(0).get("clhaqty"));
			} else
				row.put("state", "OPEN");
			result.add(row);
		}
		return Map.of("supported", true, "checked_at", java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Seoul")).toString(), "lines", result);
	}

	private List<Map<String, Object>> items(Map<String, Object> order) {
		return jdbc.queryForList("""
				SELECT client_ord_item,supplier_item_code,order_qty,item_status FROM the_full_order.tb_account_purchase_order_item
				WHERE purchase_order_id=? AND item_status NOT IN ('FAILED','CANCELED') ORDER BY client_ord_item
				""", order.get("purchase_order_id"));
	}

	/** 품목을 외부 주문키(extnOrderKey=client_ord, extnOrderNo=client_ord_item)로 맞춰 본다. 알림을 남겼으면 true. */
	boolean notify(Map<String, Object> order, List<Map<String, Object>> items, List<Map<String, String>> lines) {
		String account = text(order.get("account_id")), id = text(order.get("purchase_order_id"));
		int shipped = 0, changed = 0, deleted = 0, missing = 0;
		var facts = new StringBuilder();
		for (var item : items) {
			var matches = lines.stream().filter(l -> text(order.get("client_ord")).equals(l.get("extnOrderKey"))
					&& text(item.get("client_ord_item")).equals(l.get("extnOrderNo"))).toList();
			String state;
			if (matches.size() != 1) {
				missing++;
				state = "MISSING";
			} else {
				var line = matches.get(0);
				if ("Y".equals(line.get("delYn"))) {
					deleted++;
					state = "DELETED";
				} else if ("Y".equals(line.get("prcsStatYn"))) {
					shipped++;
					boolean sameQty = sameNumber(line.get("clhaqty"), item.get("order_qty"));
					if (!sameQty)
						changed++;
					state = "SHIPPED:" + line.get("clhaqty");
				} else
					state = "OPEN";
			}
			facts.append(item.get("client_ord_item")).append('=').append(state).append(';');
		}
		int live = items.size() - deleted - missing;
		String date = text(order.get("delivery_date"));
		String quantity = changed > 0 ? " · 출고 수량 다름 " + changed + "품목" : "";
		String status; // 업무 알림 제목과 알림톡 '상태' 변수에 같이 쓴다
		if (deleted + missing > 0)
			status = "주문 확인 요망" + (deleted > 0 ? " · 아워홈 삭제 " + deleted + "품목" : "")
					+ (missing > 0 ? " · 주문 조회 안 됨 " + missing + "품목" : "");
		else if (live > 0 && shipped == live)
			status = "출고완료 · " + shipped + "품목 입고 처리 요망" + quantity;
		else if (shipped > 0)
			status = "일부 출고 · " + shipped + "/" + live + "품목" + quantity;
		else {
			notifications.resolveSupplierStatus(account, id);
			return false;
		}
		int split = status.indexOf(" · ");
		String title = "아워홈 " + (split < 0 ? status + " · " + date : status.substring(0, split) + " · " + date + status.substring(split));
		String fingerprint = notifications.supplierStatus(account, id, title, facts.toString());
		if (kakao != null && fingerprint != null)
			try {
				kakao.supplierStatus(account, id, fingerprint, "아워홈", date, status);
			} catch (RuntimeException e) {
				log.warn("아워홈 출고 상태 알림톡 실패 purchase_order_id={}: {}", id, e.getMessage());
			}
		return true;
	}

	private static boolean sameNumber(String value, Object expected) {
		try {
			return value != null && !value.isBlank() && new BigDecimal(value.trim()).compareTo(new BigDecimal(text(expected))) == 0;
		} catch (NumberFormatException e) {
			return false;
		}
	}

	private static String text(Object value) {
		return value == null ? "" : value.toString();
	}
}

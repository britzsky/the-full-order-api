package com.example.demo.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 결품 처리(2026-11-19). 발주한 품목이 안 들어왔을 때 남은 수량을 "결품"으로 닫는다.
 * <ul>
 * <li>결품 확정은 사람이 누른다(공급사가 먼저 연락 / 안 와서 현장이 확인 / 웰스토리 CRM 상황 확인). 자동으로 닫지 않는다.</li>
 * <li>결품 수량은 입고 예정·재고·금액에서 빠진다. 같은 품목에 더 입고할 수 없다(취소하면 다시 가능).</li>
 * <li>같은 발주서라도 배송 구분(센터·직송·택배)이 다르면 따로 오므로, 납품일이 지난 뒤 배송 구분별 대기일까지는 "배송 대기"로 둔다.</li>
 * <li>웰스토리 상황 조회(payer-situation-detail)는 읽기만 한다 — 상황 등록 API는 웰스토리에 없다.</li>
 * </ul>
 * 기록은 tb_supplier_incident: 우리가 확정한 결품은 OUR|발주품목ID(incident_type SHORTAGE), 웰스토리 상황은 W|csNo|csNoItem.
 */
@Service
public class ReceivingShortageService {
	static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
	static final DateTimeFormatter BASIC = DateTimeFormatter.BASIC_ISO_DATE;
	/** 결품을 등록할 수 있는 발주서·품목 상태(접수 확인 뒤, 다 들어오기 전) */
	static final Set<String> OPEN_STATUSES = Set.of("ORDERED", "CONFIRMED", "PARTIAL_RECEIVED", "NOT_RECEIVED");
	static final Map<String, String> SOURCES = Map.of("SUPPLIER_CONTACT", "공급사 연락", "SITE_CHECK", "현장 확인",
			"SUPPLIER_SITUATION", "웰스토리 상황");
	/** 웰스토리 csType → 사건 유형 */
	static final Map<String, String> WELSTORY_TYPES = Map.of("A", "RETURN", "B", "EXCHANGE", "C", "CHANGE", "D", "ADDITION",
			"E", "MISSING", "F", "CORRECTIVE");
	static final String ALL_CLOSED_MESSAGE = "남은 품목을 모두 결품으로 닫았습니다.";
	static final long SYNC_THROTTLE_MINUTES = 10;

	private final JdbcTemplate jdbc;
	private final WelstoryItemLookupService welstory;
	private final ObjectMapper json;
	private final Map<String, LocalDateTime> lastSync = new ConcurrentHashMap<>();

	public ReceivingShortageService(JdbcTemplate jdbc, WelstoryItemLookupService welstory, ObjectMapper json) {
		this.jdbc = jdbc;
		this.welstory = welstory;
		this.json = json;
	}

	/** 결품 확정. items: [{client_ord_item, shortage_qty(없으면 남은 수량 전부)}] */
	@Transactional
	public Map<String, Object> register(JsonNode body) {
		String account = required(body, "account_id"), orderId = required(body, "purchase_order_id");
		String source = body.path("source").asText("SITE_CHECK").trim();
		if (!SOURCES.containsKey(source))
			throw new IllegalArgumentException("결품 출처를 확인하세요.");
		String reason = body.path("reason").asText("").trim(), contact = body.path("contact_name").asText("").trim();
		String user = body.path("user_id").asText("").trim();
		if (reason.length() > 300 || contact.length() > 50)
			throw new IllegalArgumentException("사유는 300자, 연락한 사람은 50자 이내로 적어주세요.");
		JsonNode items = body.path("items");
		if (!items.isArray() || items.isEmpty())
			throw new IllegalArgumentException("결품 품목을 고르세요.");
		Map<String, Object> order = lockOrder(account, orderId);
		if (!OPEN_STATUSES.contains(text(order.get("status"))))
			throw new IllegalArgumentException("공급사 접수 확인 전이거나 입고가 끝난 발주서는 결품 처리할 수 없습니다.");
		Set<String> seen = new HashSet<>();
		List<Map<String, Object>> done = new ArrayList<>();
		for (JsonNode item : items) {
			String sequence = required(item, "client_ord_item");
			if (!seen.add(sequence))
				throw new IllegalArgumentException("같은 품목이 중복되었습니다.");
			Map<String, Object> line = lockItem(orderId, sequence);
			if (!OPEN_STATUSES.contains(text(line.get("item_status"))) && number(line.get("shortage_order_qty")).signum() == 0)
				throw new IllegalArgumentException("결품 처리할 수 없는 품목 상태입니다: " + text(line.get("product_name")));
			BigDecimal ordered = number(line.get("order_qty")), received = number(line.get("received_order_qty"));
			BigDecimal remaining = ordered.subtract(received);
			BigDecimal qty = item.hasNonNull("shortage_qty") && !item.path("shortage_qty").asText().isBlank()
					? number(item.path("shortage_qty").asText()) : remaining;
			if (qty.signum() <= 0 || qty.scale() > 3)
				throw new IllegalArgumentException("결품 수량은 양수이며 소수점 3자리 이하여야 합니다.");
			if (qty.compareTo(remaining) > 0)
				throw new IllegalArgumentException("결품 수량이 아직 안 들어온 수량(" + remaining.stripTrailingZeros().toPlainString()
						+ ")보다 많습니다: " + text(line.get("product_name")));
			// 전에 전부 결품으로 닫았던 품목을 일부 결품으로 고치면 접수 상태로 되돌린다
			String open = number(line.get("shortage_order_qty")).signum() > 0 && "NOT_RECEIVED".equals(text(line.get("item_status")))
					? (order.get("confirmed_at") != null ? "CONFIRMED" : "ORDERED") : text(line.get("item_status"));
			String status = qty.compareTo(remaining) >= 0 ? (received.signum() > 0 ? "RECEIVED" : "NOT_RECEIVED")
					: received.signum() > 0 ? "PARTIAL_RECEIVED" : open;
			jdbc.update("UPDATE tb_account_purchase_order_item SET shortage_order_qty=?,shortage_at=NOW(),item_status=?,mod_at=NOW()"
					+ " WHERE purchase_order_item_id=?", qty, status, line.get("purchase_order_item_id"));
			ObjectNode attributes = json.createObjectNode().put("source", source).put("source_label", SOURCES.get(source))
					.put("reason", reason).put("contact_name", contact).put("user_id", user)
					.put("registered_at", LocalDateTime.now(SEOUL).toString());
			jdbc.update("INSERT INTO tb_supplier_incident(account_id,supplier_id,purchase_order_id,purchase_order_item_id,"
					+ "provider_incident_key,incident_type,incident_qty,confirmed_qty,status,action_note,occurred_at,provider_attributes)"
					+ " VALUES(?,?,?,?,?,'SHORTAGE',?,?,'CONFIRMED',?,NOW(),?)"
					+ " ON DUPLICATE KEY UPDATE incident_qty=VALUES(incident_qty),confirmed_qty=VALUES(confirmed_qty),status='CONFIRMED',"
					+ "action_note=VALUES(action_note),occurred_at=NOW(),provider_attributes=VALUES(provider_attributes)",
					account, order.get("supplier_id"), orderId, line.get("purchase_order_item_id"), ourKey(line), qty, qty,
					reason.isBlank() ? null : reason, attributes.toString());
			// 같은 품목의 웰스토리 미입 상황은 반영됨으로 표시(다시 "확인 필요"로 띄우지 않게)
			jdbc.update("UPDATE tb_supplier_incident SET status='APPLIED' WHERE purchase_order_item_id=? AND provider_incident_key LIKE 'W|%'"
					+ " AND incident_type='MISSING' AND status='OPEN'", line.get("purchase_order_item_id"));
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("client_ord_item", sequence);
			result.put("product_name", line.get("product_name"));
			result.put("shortage_qty", qty);
			result.put("order_unit", line.get("order_unit"));
			result.put("shortage_amount", qty.multiply(number(line.get("unit_price_snapshot"))));
			result.put("item_status", status);
			done.add(result);
		}
		String orderStatus = refreshOrder(order);
		return Map.of("purchase_order_id", orderId, "status", orderStatus, "items", done);
	}

	/** 결품 취소(늦게라도 들어온 경우). 남은 수량이 다시 입고 예정으로 돌아간다. */
	@Transactional
	public Map<String, Object> cancel(JsonNode body) {
		String account = required(body, "account_id"), orderId = required(body, "purchase_order_id");
		String sequence = required(body, "client_ord_item"), user = body.path("user_id").asText("").trim();
		Map<String, Object> order = lockOrder(account, orderId);
		if (!OPEN_STATUSES.contains(text(order.get("status"))) && !"RECEIVED".equals(text(order.get("status"))))
			throw new IllegalArgumentException("결품을 취소할 수 없는 발주서 상태입니다.");
		Map<String, Object> line = lockItem(orderId, sequence);
		if (number(line.get("shortage_order_qty")).signum() == 0)
			throw new IllegalArgumentException("결품 처리된 품목이 아닙니다.");
		BigDecimal ordered = number(line.get("order_qty")), received = number(line.get("received_order_qty"));
		String status = received.compareTo(ordered) >= 0 ? "RECEIVED" : received.signum() > 0 ? "PARTIAL_RECEIVED"
				: order.get("confirmed_at") != null ? "CONFIRMED" : "ORDERED";
		jdbc.update("UPDATE tb_account_purchase_order_item SET shortage_order_qty=0,shortage_at=NULL,item_status=?,mod_at=NOW()"
				+ " WHERE purchase_order_item_id=?", status, line.get("purchase_order_item_id"));
		jdbc.update("UPDATE tb_supplier_incident SET status='CANCELED',provider_attributes=JSON_SET(COALESCE(provider_attributes,JSON_OBJECT()),"
				+ "'$.canceled_by',?,'$.canceled_at',?) WHERE supplier_id=? AND provider_incident_key=?",
				user, LocalDateTime.now(SEOUL).toString(), order.get("supplier_id"), ourKey(line));
		return Map.of("purchase_order_id", orderId, "client_ord_item", sequence, "item_status", status, "status",
				refreshOrder(order));
	}

	/**
	 * 아직 안 들어온 품목(납품일 date_to 이전). 상태:
	 * WAITING 배송 대기(납품일+배송 구분별 대기일 전, 또는 공급사 새 입고일 전) / CHECK_NEEDED 결품 확인 필요 /
	 * SUPPLIER_REPORTED 웰스토리가 미입으로 등록함.
	 */
	public Map<String, Object> pending(String account, String dateTo) {
		if (account == null || account.isBlank())
			throw new IllegalArgumentException("거래처를 확인하세요.");
		LocalDate today = LocalDate.now(SEOUL);
		LocalDate to = dateTo == null || dateTo.isBlank() ? today : LocalDate.parse(dateTo);
		List<Map<String, Object>> rows = jdbc.queryForList("""
				SELECT po.purchase_order_id,po.status order_status,po.client_ord,po.sold_to,po.inspected_at,
				       DATE_FORMAT(po.requested_delivery_date,'%Y-%m-%d') delivery_date,s.supplier_code,s.supplier_name,
				       ss.external_site_name site_name,
				       oi.purchase_order_item_id,oi.client_ord_item,oi.supplier_item_code,
				       COALESCE(oi.product_name_snapshot,p.product_name) product_name,oi.order_qty,oi.received_order_qty,
				       oi.shortage_order_qty,oi.order_unit,oi.unit_price_snapshot,oi.item_status,
				       oi.delivery_type,COALESCE(oi.delivery_type_raw,o.delivery_type) delivery_type_raw
				  FROM tb_account_purchase_order po
				  JOIN tb_supplier s ON s.supplier_id=po.supplier_id
				  JOIN tb_account_purchase_order_item oi ON oi.purchase_order_id=po.purchase_order_id
				  JOIN tb_supplier_ingredient_product p ON p.supplier_product_id=oi.supplier_product_id
				  LEFT JOIN tb_supplier_account_site ss ON ss.account_id=po.account_id AND ss.supplier_id=po.supplier_id AND ss.external_site_code=po.sold_to
				  LEFT JOIN tb_supplier_site_product_offer o ON o.supplier_site_id=ss.supplier_site_id AND o.supplier_product_id=oi.supplier_product_id
				 WHERE po.account_id=? AND po.requested_delivery_date<=?
				   AND po.status IN ('ORDERED','CONFIRMED','PARTIAL_RECEIVED','NOT_RECEIVED')
				   AND oi.item_status IN ('ORDERED','CONFIRMED','PARTIAL_RECEIVED','NOT_RECEIVED')
				   AND oi.received_order_qty+oi.shortage_order_qty<oi.order_qty
				 ORDER BY po.requested_delivery_date,po.purchase_order_id,oi.purchase_order_item_id
				 LIMIT 2000
				""", account, to.toString());
		Map<Object, List<Map<String, Object>>> situations = situationsByItem(account);
		int waiting = 0, check = 0, reported = 0;
		List<Map<String, Object>> items = new ArrayList<>();
		for (Map<String, Object> row : rows) {
			Map<String, Object> item = new LinkedHashMap<>(row);
			String type = text(row.get("delivery_type")).isBlank() ? DeliveryTypes.normalize(row.get("delivery_type_raw"))
					: text(row.get("delivery_type"));
			item.put("delivery_type", type);
			item.put("delivery_type_label", DeliveryTypes.label(type));
			BigDecimal remaining = number(row.get("order_qty")).subtract(number(row.get("received_order_qty")))
					.subtract(number(row.get("shortage_order_qty")));
			item.put("remaining_qty", remaining);
			item.put("remaining_amount", remaining.multiply(number(row.get("unit_price_snapshot"))));
			LocalDate expected = LocalDate.parse(text(row.get("delivery_date")));
			List<Map<String, Object>> related = situations.getOrDefault(row.get("purchase_order_item_id"), List.of());
			Map<String, Object> missing = null;
			for (Map<String, Object> situation : related) {
				Object next = situation.get("new_delivery_date");
				if (next != null) {
					LocalDate date = LocalDate.parse(next.toString().substring(0, 10));
					if (date.isAfter(expected))
						expected = date;
				}
				if ("MISSING".equals(situation.get("incident_type")) && "OPEN".equals(situation.get("status")))
					missing = situation;
			}
			item.put("expected_date", expected.toString());
			item.put("supplier_situations", related);
			LocalDate checkFrom = expected.plusDays(DeliveryTypes.graceDays(type));
			item.put("check_from_date", checkFrom.toString());
			String state;
			if (missing != null && !expected.isAfter(today)) {
				state = "SUPPLIER_REPORTED";
				reported++;
			} else if (!today.isBefore(checkFrom)) {
				state = "CHECK_NEEDED";
				check++;
			} else {
				state = "WAITING";
				waiting++;
			}
			item.put("state", state);
			items.add(item);
		}
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("today", today.toString());
		result.put("date_to", to.toString());
		result.put("counts", Map.of("WAITING", waiting, "CHECK_NEEDED", check, "SUPPLIER_REPORTED", reported));
		result.put("items", items);
		result.put("recent_shortages", recentShortages(account, today.minusDays(14)));
		result.put("last_situation_sync", lastSync.get(account) == null ? null : lastSync.get(account).toString());
		return result;
	}

	/** 최근 결품 확정 내역(공급사에 보낼 메시지·확인용) */
	List<Map<String, Object>> recentShortages(String account, LocalDate from) {
		return jdbc.queryForList("""
				SELECT i.supplier_incident_id,i.purchase_order_id,oi.client_ord_item,po.client_ord,po.sold_to,
				       DATE_FORMAT(po.requested_delivery_date,'%Y-%m-%d') delivery_date,s.supplier_code,s.supplier_name,
				       oi.supplier_item_code,COALESCE(oi.product_name_snapshot,p.product_name) product_name,oi.order_unit,
				       oi.order_qty,oi.received_order_qty,oi.shortage_order_qty,oi.unit_price_snapshot,
				       oi.shortage_order_qty*oi.unit_price_snapshot shortage_amount,i.action_note reason,
				       JSON_UNQUOTE(JSON_EXTRACT(i.provider_attributes,'$.source')) source,
				       JSON_UNQUOTE(JSON_EXTRACT(i.provider_attributes,'$.contact_name')) contact_name,
				       JSON_UNQUOTE(JSON_EXTRACT(i.provider_attributes,'$.user_id')) user_id,
				       DATE_FORMAT(i.occurred_at,'%Y-%m-%d %H:%i') shortage_at,
				       (SELECT COUNT(*) FROM tb_supplier_incident w WHERE w.purchase_order_item_id=i.purchase_order_item_id
				           AND w.provider_incident_key LIKE 'W|%' AND w.incident_type='MISSING') supplier_registered_count
				  FROM tb_supplier_incident i
				  JOIN tb_supplier s ON s.supplier_id=i.supplier_id
				  JOIN tb_account_purchase_order po ON po.purchase_order_id=i.purchase_order_id
				  JOIN tb_account_purchase_order_item oi ON oi.purchase_order_item_id=i.purchase_order_item_id
				  JOIN tb_supplier_ingredient_product p ON p.supplier_product_id=oi.supplier_product_id
				 WHERE i.account_id=? AND i.incident_type='SHORTAGE' AND i.status='CONFIRMED' AND i.provider_incident_key LIKE 'OUR|%'
				   AND po.requested_delivery_date>=?
				 ORDER BY po.requested_delivery_date DESC,i.purchase_order_id,oi.purchase_order_item_id
				 LIMIT 300
				""", account, from.toString());
	}

	private Map<Object, List<Map<String, Object>>> situationsByItem(String account) {
		Map<Object, List<Map<String, Object>>> map = new HashMap<>();
		for (Map<String, Object> row : jdbc.queryForList("""
				SELECT purchase_order_item_id,incident_type,incident_qty,confirmed_qty,status,action_note,
				       DATE_FORMAT(new_delivery_date,'%Y-%m-%d') new_delivery_date,DATE_FORMAT(occurred_at,'%Y-%m-%d %H:%i') occurred_at
				  FROM tb_supplier_incident
				 WHERE account_id=? AND purchase_order_item_id IS NOT NULL AND provider_incident_key LIKE 'W|%' AND status<>'CANCELED'
				   AND COALESCE(occurred_at,created_at)>=DATE_SUB(NOW(),INTERVAL 60 DAY)
				 ORDER BY COALESCE(occurred_at,created_at)
				""", account))
			map.computeIfAbsent(row.get("purchase_order_item_id"), k -> new ArrayList<>()).add(row);
		return map;
	}

	/**
	 * 웰스토리 상황 조회(읽기 전용)를 받아 tb_supplier_incident에 저장한다. 우리 발주 품목(주문번호+순번)과 연결한다.
	 * 결품을 확정하지는 않는다 — 미입(E)은 화면에 "웰스토리 미입 등록 · 확인 필요"로 띄운다. force가 아니면 10분에 한 번.
	 */
	public Map<String, Object> syncSituations(String account, String from, String to, boolean force) {
		if (account == null || account.isBlank())
			throw new IllegalArgumentException("거래처를 확인하세요.");
		LocalDateTime now = LocalDateTime.now(SEOUL);
		LocalDateTime last = lastSync.get(account);
		Map<String, Object> result = new LinkedHashMap<>();
		if (!force && last != null && last.plusMinutes(SYNC_THROTTLE_MINUTES).isAfter(now)) {
			result.put("skipped", true);
			result.put("message", "최근에 불러왔습니다.");
			result.put("last_situation_sync", last.toString());
			return result;
		}
		if (now.toLocalTime().isBefore(LocalTime.of(0, 31)))
			throw new IllegalArgumentException("웰스토리 점검 시간(00:00~00:30)에는 불러올 수 없습니다.");
		LocalDate today = now.toLocalDate();
		LocalDate start = from == null || from.isBlank() ? today.minusDays(7) : LocalDate.parse(from);
		LocalDate end = to == null || to.isBlank() ? today.plusDays(3) : LocalDate.parse(to);
		if (end.isBefore(start) || start.plusDays(31).isBefore(end))
			throw new IllegalArgumentException("기간은 31일 이내로 지정하세요.");
		List<Map<String, Object>> sites = jdbc.queryForList("""
				SELECT ss.supplier_id,ss.external_site_code FROM tb_supplier_account_site ss JOIN tb_supplier s ON s.supplier_id=ss.supplier_id
				 WHERE ss.account_id=? AND ss.active_yn='Y' AND s.supplier_code='WELSTORY' ORDER BY ss.external_site_code
				""", account);
		int saved = 0, linked = 0, missing = 0;
		List<String> failures = new ArrayList<>();
		for (Map<String, Object> site : sites) {
			String soldTo = text(site.get("external_site_code"));
			ObjectNode request = json.createObjectNode();
			request.putObject("dataHeader").put("soldTo", soldTo).put("deliDateFrom", start.format(BASIC))
					.put("deliDateTo", end.format(BASIC)).put("crDateFrom", "").put("crDateTo", "").put("clientOrd", "");
			request.putObject("dataBody");
			JsonNode body;
			try {
				body = welstory.call("/fdapi/service/payer-situation-detail", request).path("dataBody");
			} catch (RuntimeException e) {
				failures.add(soldTo + " " + abbreviate(String.valueOf(e.getMessage())));
				continue;
			}
			String code = body.path("resCd").asText(""), message = body.path("resMsg").asText("");
			if ("EE120".equals(code) || "E1103".equals(code) || message.contains("존재하지 않습니다") || message.contains("조회 결과가 없습니다"))
				continue;
			if (!"S0000".equals(code)) {
				failures.add(soldTo + " " + code + " " + abbreviate(message));
				continue;
			}
			for (JsonNode row : body.path("data")) {
				Map<String, Object> incident = situation(account, site, row);
				if (incident == null)
					continue;
				saved += upsertSituation(incident);
				if (incident.get("purchase_order_item_id") != null)
					linked++;
				if ("MISSING".equals(incident.get("incident_type")))
					missing++;
			}
		}
		if (failures.isEmpty())
			lastSync.put(account, now);
		result.put("skipped", false);
		result.put("site_count", sites.size());
		result.put("saved_count", saved);
		result.put("linked_count", linked);
		result.put("missing_count", missing);
		result.put("failures", failures);
		result.put("last_situation_sync", now.toString());
		return result;
	}

	/** 웰스토리 상황 한 줄 → 사건. 우리 발주(주문번호+순번)와 연결되면 발주 품목 ID를 붙인다. */
	Map<String, Object> situation(String account, Map<String, Object> site, JsonNode row) {
		String csNo = row.path("csNo").asText("").trim(), csItem = row.path("csNoItem").asText("").trim();
		if (csNo.isBlank())
			return null;
		Map<String, Object> incident = new LinkedHashMap<>();
		incident.put("account_id", account);
		incident.put("supplier_id", site.get("supplier_id"));
		incident.put("provider_incident_key", "W|" + csNo + "|" + csItem);
		incident.put("incident_type", WELSTORY_TYPES.getOrDefault(row.path("csType").asText("").trim(), "OTHER"));
		incident.put("incident_qty", decimalOrNull(row.path("csQty").asText("")));
		incident.put("confirmed_qty", decimalOrNull(row.path("confirmQty").asText("")));
		String note = row.path("csNote").asText("").trim();
		incident.put("action_note", note.isBlank() ? null : note.length() > 500 ? note.substring(0, 500) : note);
		incident.put("new_delivery_date", basicDate(row.path("ndeliDate").asText("")));
		LocalDate created = basicDate(row.path("crDate").asText(""));
		String time = row.path("crDateTime").asText("").trim();
		incident.put("occurred_at", created == null ? null
				: created + " " + (time.matches("\\d{6}") ? time.substring(0, 2) + ":" + time.substring(2, 4) + ":" + time.substring(4) : "00:00:00"));
		incident.put("provider_attributes", row.toString());
		String clientOrd = row.path("clientOrd").asText("").trim();
		String sequence = row.path("clientOrdItem").asText("").trim().replaceFirst("^0+(?!$)", "");
		if (!clientOrd.isBlank() && !sequence.isBlank()) {
			List<Map<String, Object>> found = jdbc.queryForList("""
					SELECT oi.purchase_order_item_id,oi.purchase_order_id,oi.shortage_order_qty FROM tb_account_purchase_order po
					  JOIN tb_account_purchase_order_item oi ON oi.purchase_order_id=po.purchase_order_id
					 WHERE po.account_id=? AND po.supplier_id=? AND po.client_ord=? AND oi.client_ord_item=?
					""", account, site.get("supplier_id"), clientOrd, sequence);
			if (found.size() == 1) {
				incident.put("purchase_order_id", found.get(0).get("purchase_order_id"));
				incident.put("purchase_order_item_id", found.get(0).get("purchase_order_item_id"));
				// 이미 우리가 결품으로 닫은 품목이면 처음부터 반영됨
				if (number(found.get(0).get("shortage_order_qty")).signum() > 0)
					incident.put("status", "APPLIED");
			}
		}
		incident.putIfAbsent("status", "OPEN");
		return incident;
	}

	private int upsertSituation(Map<String, Object> i) {
		// 상태는 처음 저장할 때만 정한다(사람이 확인·반영한 상태를 다시 열지 않는다)
		return jdbc.update("""
				INSERT INTO tb_supplier_incident(account_id,supplier_id,purchase_order_id,purchase_order_item_id,provider_incident_key,
				  incident_type,incident_qty,confirmed_qty,status,action_note,new_delivery_date,occurred_at,provider_attributes)
				VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
				ON DUPLICATE KEY UPDATE purchase_order_id=COALESCE(VALUES(purchase_order_id),purchase_order_id),
				  purchase_order_item_id=COALESCE(VALUES(purchase_order_item_id),purchase_order_item_id),incident_type=VALUES(incident_type),
				  incident_qty=VALUES(incident_qty),confirmed_qty=VALUES(confirmed_qty),action_note=VALUES(action_note),
				  new_delivery_date=VALUES(new_delivery_date),occurred_at=COALESCE(VALUES(occurred_at),occurred_at),
				  provider_attributes=VALUES(provider_attributes)
				""", i.get("account_id"), i.get("supplier_id"), i.get("purchase_order_id"), i.get("purchase_order_item_id"),
				i.get("provider_incident_key"), i.get("incident_type"), i.get("incident_qty"), i.get("confirmed_qty"), i.get("status"),
				i.get("action_note"), i.get("new_delivery_date") == null ? null : i.get("new_delivery_date").toString(),
				i.get("occurred_at"), i.get("provider_attributes")) > 0 ? 1 : 0;
	}

	/**
	 * 결품 등록·취소 뒤 발주서 상태. 결품이 없으면 입고 처리와 같은 규칙(모두 들어오면 RECEIVED, 일부면 PARTIAL_RECEIVED).
	 * 아무것도 안 들어오고 모두 결품이면 NOT_RECEIVED. 결품 취소로 다시 열리면 접수 상태(CONFIRMED/ORDERED)로 돌린다.
	 */
	String refreshOrder(Map<String, Object> order) {
		Object id = order.get("purchase_order_id");
		Map<String, Object> sums = jdbc.queryForMap("""
				SELECT SUM(CASE WHEN received_order_qty+shortage_order_qty<order_qty THEN 1 ELSE 0 END) open_count,
				       SUM(CASE WHEN received_order_qty>0 THEN 1 ELSE 0 END) received_count
				  FROM tb_account_purchase_order_item WHERE purchase_order_id=?
				""", id);
		boolean open = number(sums.get("open_count")).signum() > 0, received = number(sums.get("received_count")).signum() > 0;
		String current = text(order.get("status"));
		String next;
		if (!open)
			next = received ? "RECEIVED" : "NOT_RECEIVED";
		else if (received)
			next = "PARTIAL_RECEIVED";
		else if ("NOT_RECEIVED".equals(current) && ALL_CLOSED_MESSAGE.equals(text(order.get("supplier_result_message"))))
			next = order.get("confirmed_at") != null ? "CONFIRMED" : "ORDERED";
		else
			next = current;
		if (next.equals(current))
			return current;
		if ("NOT_RECEIVED".equals(next))
			jdbc.update("UPDATE tb_account_purchase_order SET status=?,supplier_result_message=?,mod_at=NOW() WHERE purchase_order_id=?",
					next, ALL_CLOSED_MESSAGE, id);
		else if ("RECEIVED".equals(next))
			jdbc.update("UPDATE tb_account_purchase_order SET status=?,received_at=COALESCE(received_at,NOW()),mod_at=NOW() WHERE purchase_order_id=?",
					next, id);
		else
			jdbc.update("UPDATE tb_account_purchase_order SET status=?,supplier_result_message=CASE WHEN supplier_result_message=? THEN NULL"
					+ " ELSE supplier_result_message END,mod_at=NOW() WHERE purchase_order_id=?", next, ALL_CLOSED_MESSAGE, id);
		return next;
	}

	private Map<String, Object> lockOrder(String account, String orderId) {
		List<Map<String, Object>> found = jdbc.queryForList(
				"SELECT purchase_order_id,account_id,supplier_id,status,confirmed_at,supplier_result_message FROM tb_account_purchase_order"
						+ " WHERE account_id=? AND purchase_order_id=? FOR UPDATE", account, orderId);
		if (found.isEmpty())
			throw new IllegalArgumentException("발주서를 찾을 수 없습니다.");
		return found.get(0);
	}

	private Map<String, Object> lockItem(String orderId, String sequence) {
		List<Map<String, Object>> found = jdbc.queryForList("""
				SELECT oi.purchase_order_item_id,oi.client_ord_item,oi.order_qty,oi.received_order_qty,oi.shortage_order_qty,
				       oi.item_status,oi.order_unit,oi.unit_price_snapshot,COALESCE(oi.product_name_snapshot,oi.supplier_item_code) product_name
				  FROM tb_account_purchase_order_item oi WHERE oi.purchase_order_id=? AND oi.client_ord_item=? FOR UPDATE
				""", orderId, sequence);
		if (found.isEmpty())
			throw new IllegalArgumentException("발주서에 없는 품목입니다.");
		return found.get(0);
	}

	static String ourKey(Map<String, Object> line) {
		return "OUR|" + line.get("purchase_order_item_id");
	}

	static String required(JsonNode node, String field) {
		String value = node.path(field).asText("").trim();
		if (value.isEmpty())
			throw new IllegalArgumentException(field + " 값이 필요합니다.");
		return value;
	}

	static String text(Object value) {
		return value == null ? "" : String.valueOf(value).trim();
	}

	static BigDecimal number(Object value) {
		if (value == null || text(value).isEmpty())
			return BigDecimal.ZERO;
		if (value instanceof BigDecimal decimal)
			return decimal;
		try {
			return new BigDecimal(text(value).replace(",", ""));
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("숫자를 확인하세요: " + value);
		}
	}

	static BigDecimal decimalOrNull(String value) {
		String v = value == null ? "" : value.trim().replace(",", "");
		if (v.isEmpty())
			return null;
		try {
			return new BigDecimal(v);
		} catch (NumberFormatException e) {
			return null;
		}
	}

	static LocalDate basicDate(String value) {
		String v = value == null ? "" : value.trim().replace("-", "");
		if (!v.matches("\\d{8}") || v.startsWith("0000"))
			return null;
		try {
			return LocalDate.parse(v, BASIC);
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static String abbreviate(String value) {
		return value.length() > 150 ? value.substring(0, 150) + "…" : value;
	}
}

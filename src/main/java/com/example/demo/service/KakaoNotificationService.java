package com.example.demo.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.TextStyle;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 카카오 알림톡 알림. 업무 알림(tb_workspace_notification)과 같은 내용을 거래처가 등록한 번호로 보낸다.
 * - kakao.alimtalk.enabled=false(기본)거나 템플릿 ID·발송 설정이 없으면 아무것도 하지 않는다(DB 기록도 없음).
 * - 같은 알림 내용(fingerprint)은 수신자마다 한 번만 보낸다(tb_kakao_message_log 유일키). 실패도 재발송하지 않는다.
 * - 템플릿 문구는 카카오 승인 문구와 글자까지 같아야 하므로 변수 이름을 바꾸면 템플릿도 다시 승인받아야 한다(docs/KAKAO_ALIMTALK_SETUP.md).
 */
@Service
public class KakaoNotificationService {
	private static final Logger log = LoggerFactory.getLogger(KakaoNotificationService.class);
	static final int DEADLINE_WITHIN_HOURS = 3;
	private final JdbcTemplate jdbc;
	private final KakaoAlimtalkClient client;
	private final ObjectMapper json;
	private final boolean enabled;
	private final String supplierTemplate, deadlineTemplate;
	private DeadlineAlertService deadlines;

	@org.springframework.beans.factory.annotation.Autowired
	public void setDeadlines(DeadlineAlertService deadlines) { this.deadlines = deadlines; }

	public KakaoNotificationService(JdbcTemplate jdbc, KakaoAlimtalkClient client, ObjectMapper json,
			@Value("${kakao.alimtalk.enabled:false}") boolean enabled,
			@Value("${kakao.alimtalk.template.supplier-status:}") String supplierTemplate,
			@Value("${kakao.alimtalk.template.deadline:}") String deadlineTemplate) {
		this.jdbc = jdbc;
		this.client = client;
		this.json = json;
		this.enabled = enabled;
		this.supplierTemplate = supplierTemplate.trim();
		this.deadlineTemplate = deadlineTemplate.trim();
	}

	public boolean active() {
		return enabled && client.configured();
	}

	/** 공급사 출고·주문 상태 알림(SupplierOrderMonitorService가 업무 알림을 쓴 뒤 호출). 보낸 건수. */
	public int supplierStatus(String account, String orderId, String fingerprint, String supplier, String deliveryDate,
			String status) {
		if (!active() || supplierTemplate.isEmpty())
			return 0;
		Map<String, String> vars = new LinkedHashMap<>();
		vars.put("업장명", accountName(account));
		vars.put("공급사", supplier);
		vars.put("납품일", deliveryDate);
		vars.put("상태", status);
		return send(account, "SUPPLIER:" + orderId, fingerprint, "SUPPLIER_STATUS", supplierTemplate,
				"notify_supplier_status", vars);
	}

	/**
	 * 발주 마감 임박(3시간 이내) — 마감 알림 수신자가 있는 거래처만 계산한다.
	 * 같은 마감·납품일 묶음은 품목 구성이 바뀔 때만 다시 보낸다.
	 */
	@SuppressWarnings("unchecked")
	public Map<String, Object> deadlines() {
		int accounts = 0, sent = 0, failed = 0;
		if (!active() || deadlineTemplate.isEmpty() || deadlines == null)
			return Map.of("accounts", 0, "sent", 0, "failed", 0);
		for (var row : jdbc.queryForList("""
				SELECT DISTINCT account_id FROM the_full_order.tb_account_kakao_recipient
				WHERE active_yn='Y' AND notify_deadline='Y'
				""")) {
			String account = row.get("account_id").toString();
			accounts++;
			try {
				var result = deadlines.alerts(Map.of("account_id", account, "within_hours", String.valueOf(DEADLINE_WITHIN_HOURS)));
				for (var group : (List<Map<String, Object>>) result.getOrDefault("alerts", List.of())) {
					var items = (List<Map<String, Object>>) group.getOrDefault("items", List.of());
					if (items.isEmpty())
						continue;
					var ids = new TreeSet<String>();
					items.forEach(item -> ids.add(Objects.toString(item.get("ingredient_id"))));
					String scope = group.get("supplier_code") + "|" + group.get("external_site_code") + "|" + group.get("deadline")
							+ "|" + group.get("delivery_date");
					Map<String, String> vars = new LinkedHashMap<>();
					vars.put("업장명", accountName(account));
					vars.put("공급사", Objects.toString(group.get("supplier_name"), ""));
					vars.put("마감", deadlineLabel(Objects.toString(group.get("deadline"))));
					vars.put("납품일", Objects.toString(group.get("delivery_date")));
					String first = Objects.toString(items.get(0).get("ingredient_name"), "");
					vars.put("품목", items.size() == 1 ? first : first + " 외 " + (items.size() - 1) + "건");
					sent += send(account, "DEADLINE:" + hash(scope).substring(0, 40), hash(String.join(",", ids)), "DEADLINE",
							deadlineTemplate, "notify_deadline", vars);
				}
			} catch (RuntimeException e) {
				failed++;
				log.warn("마감 임박 알림톡 계산 실패 account={}: {}", account, e.getMessage());
			}
		}
		return Map.of("accounts", accounts, "sent", sent, "failed", failed);
	}

	private int send(String account, String eventKey, String fingerprint, String templateCode, String templateId,
			String column, Map<String, String> vars) {
		if (!active() || templateId.isEmpty())
			return 0;
		int sent = 0;
		String variables = toJson(vars);
		// column은 위 두 상수 중 하나만 들어온다(사용자 입력 아님).
		for (var recipient : jdbc.queryForList("SELECT kakao_recipient_id,phone FROM the_full_order.tb_account_kakao_recipient WHERE account_id=? AND active_yn='Y' AND "
				+ column + "='Y'", account)) {
			Object id = recipient.get("kakao_recipient_id");
			int claimed = jdbc.update("""
					INSERT IGNORE INTO the_full_order.tb_kakao_message_log(account_id,event_key,fingerprint,kakao_recipient_id,template_code,variables_json)
					VALUES(?,?,?,?,?,?)
					""", account, eventKey, fingerprint, id, templateCode, variables);
			if (claimed == 0)
				continue; // 같은 내용은 이미 보냈다
			var result = client.send(recipient.get("phone").toString(), templateId, vars);
			jdbc.update("""
					UPDATE the_full_order.tb_kakao_message_log SET status=?,provider_message_id=?,result_code=?,result_message=?,
					  sent_at=IF(?='SENT',NOW(),NULL)
					WHERE account_id=? AND event_key=? AND fingerprint=? AND kakao_recipient_id=?
					""", result.accepted() ? "SENT" : "FAILED", result.messageId(), result.code(), result.message(),
					result.accepted() ? "SENT" : "FAILED", account, eventKey, fingerprint, id);
			if (result.accepted())
				sent++;
		}
		return sent;
	}

	// ---- 수신자 관리 ----

	public List<Map<String, Object>> recipients(String account) {
		return jdbc.queryForList("""
				SELECT kakao_recipient_id,account_id,recipient_name,phone,notify_supplier_status,notify_deadline,active_yn,updated_at
				FROM the_full_order.tb_account_kakao_recipient WHERE account_id=? ORDER BY active_yn DESC,recipient_name
				""", account);
	}

	/** 등록·수정. kakao_recipient_id가 있으면 수정, 없으면 같은 번호를 다시 켜거나 새로 등록. */
	public List<Map<String, Object>> saveRecipient(String account, Map<String, Object> input, String actor) {
		String name = Objects.toString(input.get("recipient_name"), "").trim();
		String phone = phone(Objects.toString(input.get("phone"), ""));
		if (name.isEmpty() || name.length() > 50)
			throw new IllegalArgumentException("수신자 이름을 입력해주세요(50자 이내).");
		String supplier = yn(input.get("notify_supplier_status")), deadline = yn(input.get("notify_deadline")),
				active = input.containsKey("active_yn") ? yn(input.get("active_yn")) : "Y";
		Object id = input.get("kakao_recipient_id");
		if (id != null && !id.toString().isBlank()) {
			if (jdbc.update("""
					UPDATE the_full_order.tb_account_kakao_recipient SET recipient_name=?,phone=?,notify_supplier_status=?,notify_deadline=?,active_yn=?,user_id=?
					WHERE kakao_recipient_id=? AND account_id=?
					""", name, phone, supplier, deadline, active, actor, id, account) == 0)
				throw new IllegalArgumentException("수신자를 찾을 수 없습니다.");
		} else
			jdbc.update("""
					INSERT INTO the_full_order.tb_account_kakao_recipient(account_id,recipient_name,phone,notify_supplier_status,notify_deadline,active_yn,user_id)
					VALUES(?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE recipient_name=VALUES(recipient_name),notify_supplier_status=VALUES(notify_supplier_status),
					  notify_deadline=VALUES(notify_deadline),active_yn=VALUES(active_yn),user_id=VALUES(user_id)
					""", account, name, phone, supplier, deadline, active, actor);
		return recipients(account);
	}

	/** 발송 이력이 남아야 하므로 지우지 않고 끈다. */
	public List<Map<String, Object>> deactivateRecipient(String account, Object id, String actor) {
		if (jdbc.update("UPDATE the_full_order.tb_account_kakao_recipient SET active_yn='N',user_id=? WHERE kakao_recipient_id=? AND account_id=?",
				actor, id, account) == 0)
			throw new IllegalArgumentException("수신자를 찾을 수 없습니다.");
		return recipients(account);
	}

	/** 설정 확인용 시험 발송(공급사 상태 템플릿). 매번 새 이력으로 남는다. */
	public Map<String, Object> test(String account, Object id, String actor) {
		if (!enabled)
			throw new IllegalArgumentException("알림톡이 꺼져 있습니다(kakao.alimtalk.enabled).");
		if (!client.configured())
			throw new IllegalArgumentException("알림톡 발송 설정(API 키·채널·발신번호)이 없습니다.");
		if (supplierTemplate.isEmpty())
			throw new IllegalArgumentException("공급사 상태 알림 템플릿 ID가 없습니다(kakao.alimtalk.template.supplier-status).");
		var rows = jdbc.queryForList("SELECT phone FROM the_full_order.tb_account_kakao_recipient WHERE kakao_recipient_id=? AND account_id=? AND active_yn='Y'", id, account);
		if (rows.isEmpty())
			throw new IllegalArgumentException("사용 중인 수신자를 찾을 수 없습니다.");
		Map<String, String> vars = new LinkedHashMap<>();
		vars.put("업장명", accountName(account));
		vars.put("공급사", "시험 발송");
		vars.put("납품일", LocalDate.now(DeadlineAlertService.KST).toString());
		vars.put("상태", "알림톡 수신 확인용 시험 메시지입니다.");
		String key = "TEST:" + UUID.randomUUID();
		jdbc.update("""
				INSERT INTO the_full_order.tb_kakao_message_log(account_id,event_key,fingerprint,kakao_recipient_id,template_code,variables_json)
				VALUES(?,?,?,?,'TEST',?)
				""", account, key, hash(key), id, toJson(vars));
		var result = client.send(rows.get(0).get("phone").toString(), supplierTemplate, vars);
		jdbc.update("UPDATE the_full_order.tb_kakao_message_log SET status=?,provider_message_id=?,result_code=?,result_message=?,sent_at=IF(?,NOW(),NULL) WHERE account_id=? AND event_key=?",
				result.accepted() ? "SENT" : "FAILED", result.messageId(), result.code(), result.message(), result.accepted(), account, key);
		log.info("알림톡 시험 발송 account={} by={} accepted={}", account, actor, result.accepted());
		return Map.of("accepted", result.accepted(), "code", Objects.toString(result.code(), ""), "message", Objects.toString(result.message(), ""));
	}

	static String phone(String value) {
		String digits = value.replaceAll("\\D", "");
		if (!digits.matches("01[016789]\\d{7,8}"))
			throw new IllegalArgumentException("휴대폰 번호를 확인해주세요(010으로 시작하는 숫자).");
		return digits;
	}

	private static String yn(Object value) {
		return "N".equals(Objects.toString(value, "Y")) ? "N" : "Y";
	}

	static String deadlineLabel(String value) {
		try {
			var time = LocalDateTime.parse(value);
			return time.getMonthValue() + "/" + time.getDayOfMonth() + "("
					+ time.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.KOREAN) + ") "
					+ String.format("%02d:%02d", time.getHour(), time.getMinute());
		} catch (RuntimeException e) {
			return value;
		}
	}

	private String toJson(Map<String, String> vars) {
		try {
			return json.writeValueAsString(vars);
		} catch (Exception e) {
			return null;
		}
	}

	private String accountName(String account) {
		var rows = jdbc.queryForList("SELECT account_name FROM the_full.tb_account WHERE account_id=?", account);
		return rows.isEmpty() || rows.get(0).get("account_name") == null ? account : rows.get(0).get("account_name").toString();
	}

	private static String hash(String value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}
}

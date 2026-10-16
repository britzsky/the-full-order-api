package com.example.demo.service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 웰스토리 순기 자동 동기화.
 * 웰스토리는 '지금이 몇 순기'를 알려주는 API가 없다(사업장 조회의 nextPGCycle은 순기 번호가 아니라
 * '차순기 시작 D-N일부터 단가표 전송'의 N). 그래서 후보 순기를 1행만 조회해 웰스토리가 준
 * periodFromDt~periodToDt로 확인한다 — 달력으로 계산한 순기를 그대로 쓰지 않는다.
 * 전체 판가는 DB에 없는 순기만 받고(3주에 한 번 정도), 이미 받은 순기는 변경 판가만 받는다.
 */
@Service
public class WelstoryCycleSyncService {
	private static final Logger log = LoggerFactory.getLogger(WelstoryCycleSyncService.class);
	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
	private static final DateTimeFormatter BASIC_DATE = DateTimeFormatter.BASIC_ISO_DATE;
	/** 가이드: 순기는 3주 단위. 첫 조회 후보를 고를 때만 쓰고, 결과는 항상 웰스토리 날짜로 확인한다. */
	private static final int CYCLE_DAYS = 21;
	private static final int MAX_PROBES = 10;

	public record Cycle(int year, int group, LocalDate from, LocalDate to) {
		public String revision() { return year + "-" + String.format("%02d", group); }
		boolean covers(LocalDate date) { return !date.isBefore(from) && !date.isAfter(to); }
	}

	private final JdbcTemplate jdbc;
	private final WelstoryCatalogSyncService sync;

	public WelstoryCycleSyncService(JdbcTemplate jdbc, WelstoryCatalogSyncService sync) {
		this.jdbc = jdbc;
		this.sync = sync;
	}

	/** 새벽 배치: 웰스토리 사업장이 지정된 모든 거래처. 사업장 하나가 실패해도 나머지는 계속한다. */
	public List<Map<String, Object>> syncAll(String actor) {
		List<Map<String, Object>> results = new ArrayList<>();
		Map<String, Map<String, Cycle>> probes = new HashMap<>();
		Set<Object> done = new HashSet<>();
		for (Map<String, Object> site : sites(null)) {
			// 상품·판가는 사업장 단위로 저장되므로 같은 사업장을 쓰는 다른 거래처는 다시 받지 않는다
			if (!done.add(site.get("supplier_site_id")))
				continue;
			try {
				results.add(syncSite(site, today(), actor, probes));
			} catch (RuntimeException e) {
				log.warn("웰스토리 순기 동기화 실패 site={} : {}", site.get("external_site_code"), e.getMessage());
				results.add(Map.of("sold_to", text(site.get("external_site_code")), "error", Objects.toString(e.getMessage(), "실패")));
			}
		}
		return results;
	}

	/** 한 거래처의 웰스토리 사업장(본사 화면 '순기 자동 동기화', 사업장 최초 지정). */
	public List<Map<String, Object>> syncAccount(String accountId, String actor) {
		if (accountId == null || accountId.isBlank()) throw new IllegalArgumentException("거래처를 선택해주세요.");
		List<Map<String, Object>> sites = sites(accountId);
		if (sites.isEmpty()) throw new IllegalArgumentException("이 거래처에 지정된 웰스토리 사업장이 없습니다. 공급사 사업장 매핑에서 먼저 지정해 주세요.");
		List<Map<String, Object>> results = new ArrayList<>();
		Map<String, Map<String, Cycle>> probes = new HashMap<>();
		for (Map<String, Object> site : sites) results.add(syncSite(site, today(), actor, probes));
		return results;
	}

	/**
	 * 납품일 상품 준비용: 그 날짜의 순기를 찾아 아직 안 받았으면 전체 판가를 받는다.
	 * 찾지 못하면(아직 웰스토리가 열지 않은 순기 등) IllegalArgumentException.
	 */
	public Cycle ensureCycle(Map<String, Object> site, LocalDate date, String actor) {
		Cycle cycle = find(site, date, new HashMap<>());
		if (cycle == null)
			throw new IllegalArgumentException("웰스토리에서 " + date + " 납품일의 순기 판가를 아직 받을 수 없습니다. 차순기 판가 전송일 이후 다시 시도해주세요.");
		if (!fullySynced(site, cycle)) fullSync(site, cycle, actor);
		return cycle;
	}

	Map<String, Object> syncSite(Map<String, Object> site, LocalDate today, String actor, Map<String, Map<String, Cycle>> probes) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("sold_to", text(site.get("external_site_code")));
		result.put("account_id", text(site.get("account_id")));
		Cycle current = find(site, today, probes.computeIfAbsent(text(site.get("external_site_code")), k -> new HashMap<>()));
		if (current == null) {
			result.put("error", "오늘이 속한 웰스토리 순기를 찾지 못했습니다.");
			return result;
		}
		result.put("current", apply(site, current, today, actor));
		Cycle next = next(site, current, probes.get(text(site.get("external_site_code"))));
		// 차순기 단가표는 시작 D-N일부터 열린다(nextPGCycle). 아직 안 열렸으면 다음 배치에서 다시 본다.
		result.put("next", next == null ? Map.of("status", "NOT_OPEN") : apply(site, next, today, actor));
		return result;
	}

	private Map<String, Object> apply(Map<String, Object> site, Cycle cycle, LocalDate today, String actor) {
		Map<String, Object> r = new LinkedHashMap<>();
		r.put("cycle", cycle.revision());
		r.put("from", cycle.from().toString());
		r.put("to", cycle.to().toString());
		if (!fullySynced(site, cycle)) {
			r.put("status", "FULL");
			r.put("result", fullSync(site, cycle, actor));
		} else if (!changedSynced(site, cycle, today)) {
			r.put("status", "CHANGED");
			r.put("result", changedSync(site, cycle, today, actor));
		} else {
			r.put("status", "UP_TO_DATE");
		}
		return r;
	}

	/** date가 속한 순기. DB에 받아 둔 순기가 있으면 그대로, 없으면 웰스토리에 1행씩 물어 찾는다. */
	Cycle find(Map<String, Object> site, LocalDate date, Map<String, Cycle> probes) {
		List<Cycle> known = knownCycles(site);
		for (Cycle c : known) if (c.covers(date)) return c;
		for (Cycle c : probes.values()) if (c != null && c.covers(date)) return c;
		int year = date.getYear();
		int group = Math.max(1, date.getDayOfYear() / CYCLE_DAYS + 1);
		// 같은 해에 받아 둔 순기가 있으면 그 기준으로 첫 후보를 잡는다(조회 횟수 절약)
		Cycle anchor = known.stream().filter(c -> c.year() == date.getYear())
				.min((a, b) -> Long.compare(Math.abs(ChronoUnit.DAYS.between(a.from(), date)), Math.abs(ChronoUnit.DAYS.between(b.from(), date))))
				.orElse(null);
		if (anchor != null)
			group = Math.max(1, anchor.group() + (int) Math.floorDiv(ChronoUnit.DAYS.between(anchor.from(), date), CYCLE_DAYS));
		Set<String> tried = new HashSet<>();
		int direction = 0;
		for (int i = 0; i < MAX_PROBES; i++) {
			if (group < 1) { year--; group = 20; direction = -1; }
			if (!tried.add(year + "-" + group)) return null;
			Cycle c = probe(site, year, group, probes);
			if (c == null) {
				// 없는 순기: 위로 가던 중이면 다음 해 1순기, 아래로 가던 중(또는 처음)이면 한 칸 아래
				if (direction > 0) { year++; group = 1; }
				else { group--; direction = -1; }
				continue;
			}
			if (c.covers(date)) return c;
			if (c.to().isBefore(date)) { group++; direction = 1; }
			else { group--; direction = -1; }
		}
		return null;
	}

	/** current 다음 순기. 웰스토리가 아직 열지 않았으면 null. */
	Cycle next(Map<String, Object> site, Cycle current, Map<String, Cycle> probes) {
		LocalDate start = current.to().plusDays(1);
		for (Cycle c : knownCycles(site)) if (c.covers(start)) return c;
		Cycle c = probe(site, current.year(), current.group() + 1, probes);
		if (c == null) c = probe(site, current.year() + 1, 1, probes);
		return c != null && c.covers(start) ? c : null;
	}

	private Cycle probe(Map<String, Object> site, int year, int group, Map<String, Cycle> probes) {
		String key = year + "-" + String.format("%02d", group);
		if (probes.containsKey(key)) return probes.get(key);
		LocalDate[] dates = sync.periodDates(text(site.get("external_site_code")), String.valueOf(year), String.format("%02d", group));
		Cycle c = dates == null ? null : new Cycle(year, group, dates[0], dates[1]);
		probes.put(key, c);
		return c;
	}

	private List<Cycle> knownCycles(Map<String, Object> site) {
		List<Cycle> cycles = new ArrayList<>();
		for (Map<String, Object> row : jdbc.queryForList("""
				SELECT o.provider_revision,MIN(o.valid_from) valid_from,MAX(o.valid_to) valid_to
				  FROM the_full_order.tb_supplier_site_product_offer o
				 WHERE o.supplier_site_id=? AND o.provider_revision REGEXP '^[0-9]{4}-[0-9]{2}$'
				   AND o.valid_from IS NOT NULL AND o.valid_to IS NOT NULL
				 GROUP BY o.provider_revision
				""", site.get("supplier_site_id"))) {
			String[] rev = text(row.get("provider_revision")).split("-");
			cycles.add(new Cycle(Integer.parseInt(rev[0]), Integer.parseInt(rev[1]), localDate(row.get("valid_from")), localDate(row.get("valid_to"))));
		}
		return cycles;
	}

	private boolean fullySynced(Map<String, Object> site, Cycle cycle) {
		return "SUCCESS".equals(checkpointCode(site, "CYCLE_" + cycle.revision()));
	}

	private boolean changedSynced(Map<String, Object> site, Cycle cycle, LocalDate today) {
		List<String> watermark = jdbc.queryForList("""
				SELECT watermark_value FROM the_full_order.tb_supplier_sync_checkpoint
				 WHERE supplier_id=? AND supplier_site_id=? AND sync_type=? AND last_result_code='SUCCESS'
				""", String.class, site.get("supplier_id"), site.get("supplier_site_id"), "CHANGED_" + cycle.revision());
		return !watermark.isEmpty() && today.format(BASIC_DATE).equals(watermark.get(0));
	}

	private Map<String, Object> fullSync(Map<String, Object> site, Cycle cycle, String actor) {
		String type = "CYCLE_" + cycle.revision();
		started(site, type);
		try {
			Map<String, Object> command = command(site, cycle, actor);
			Map<String, Object> result = sync.syncCatalog(command);
			finished(site, type, "순기 " + cycle.revision() + " (" + cycle.from() + "~" + cycle.to() + ") 전체 판가 " + result.get("saved_count") + "건", null);
			return result;
		} catch (RuntimeException e) {
			failed(site, type, e.getMessage());
			throw e;
		}
	}

	private Map<String, Object> changedSync(Map<String, Object> site, Cycle cycle, LocalDate today, String actor) {
		String type = "CHANGED_" + cycle.revision();
		started(site, type);
		try {
			Map<String, Object> command = command(site, cycle, actor);
			command.put("req_date", today.format(BASIC_DATE));
			Map<String, Object> result = sync.syncChangedPrices(command);
			finished(site, type, "순기 " + cycle.revision() + " 변경 판가 " + result.get("saved_count") + "건 (" + today + ")", today.format(BASIC_DATE));
			return result;
		} catch (RuntimeException e) {
			failed(site, type, e.getMessage());
			throw e;
		}
	}

	private Map<String, Object> command(Map<String, Object> site, Cycle cycle, String actor) {
		Map<String, Object> command = new HashMap<>();
		command.put("account_id", text(site.get("account_id")));
		command.put("sold_to", text(site.get("external_site_code")));
		command.put("period_group_year", String.valueOf(cycle.year()));
		command.put("period_group", String.format("%02d", cycle.group()));
		command.put("user_id", actor == null || actor.isBlank() ? "SYSTEM" : actor);
		return command;
	}

	private List<Map<String, Object>> sites(String accountId) {
		String sql = """
				SELECT ss.supplier_account_site_id,ss.supplier_site_id,ss.supplier_id,ss.account_id,ss.external_site_code
				  FROM the_full_order.tb_supplier_account_site ss
				  JOIN the_full_order.tb_supplier s ON s.supplier_id=ss.supplier_id
				 WHERE s.supplier_code='WELSTORY' AND ss.active_yn='Y'
				""";
		return accountId == null ? jdbc.queryForList(sql + " ORDER BY ss.account_id")
				: jdbc.queryForList(sql + " AND ss.account_id=? ORDER BY ss.external_site_code", accountId);
	}

	private String checkpointCode(Map<String, Object> site, String type) {
		List<String> codes = jdbc.queryForList("""
				SELECT last_result_code FROM the_full_order.tb_supplier_sync_checkpoint
				 WHERE supplier_id=? AND supplier_site_id=? AND sync_type=?
				""", String.class, site.get("supplier_id"), site.get("supplier_site_id"), type);
		return codes.isEmpty() ? "" : Objects.toString(codes.get(0), "");
	}

	private void started(Map<String, Object> site, String type) {
		jdbc.update("""
				INSERT INTO the_full_order.tb_supplier_sync_checkpoint
				  (supplier_id,supplier_site_id,sync_type,last_started_at,last_result_code,last_result_message,consecutive_failures)
				VALUES(?,?,?,NOW(),'RUNNING','동기화 진행 중',0)
				ON DUPLICATE KEY UPDATE last_started_at=NOW(),last_result_code='RUNNING',last_result_message='동기화 진행 중'
				""", site.get("supplier_id"), site.get("supplier_site_id"), type);
	}

	private void finished(Map<String, Object> site, String type, String message, String watermark) {
		jdbc.update("""
				UPDATE the_full_order.tb_supplier_sync_checkpoint
				   SET last_succeeded_at=NOW(),last_result_code='SUCCESS',last_result_message=?,watermark_value=?,consecutive_failures=0
				 WHERE supplier_id=? AND supplier_site_id=? AND sync_type=?
				""", abbreviate(message), watermark, site.get("supplier_id"), site.get("supplier_site_id"), type);
	}

	private void failed(Map<String, Object> site, String type, String message) {
		jdbc.update("""
				UPDATE the_full_order.tb_supplier_sync_checkpoint
				   SET last_failed_at=NOW(),last_result_code='FAILED',last_result_message=?,consecutive_failures=consecutive_failures+1
				 WHERE supplier_id=? AND supplier_site_id=? AND sync_type=?
				""", abbreviate(message), site.get("supplier_id"), site.get("supplier_site_id"), type);
	}

	private static LocalDate today() { return LocalDate.now(SEOUL); }

	private static LocalDate localDate(Object value) {
		if (value instanceof java.time.LocalDateTime dt) return dt.toLocalDate();
		if (value instanceof java.sql.Timestamp ts) return ts.toLocalDateTime().toLocalDate();
		if (value instanceof java.sql.Date d) return d.toLocalDate();
		if (value instanceof LocalDate d) return d;
		return LocalDate.parse(text(value).substring(0, 10));
	}

	private static String text(Object value) { return value == null ? "" : value.toString().trim(); }

	private static String abbreviate(String value) {
		String v = value == null ? "동기화 실패" : value;
		return v.length() <= 500 ? v : v.substring(0, 500);
	}
}

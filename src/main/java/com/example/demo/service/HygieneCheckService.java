package com.example.demo.service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import com.example.demo.mapper.HygieneCheckMapper;

/**
 * 위생 점검 완료(이상 없음) 표시(2026-11-25 사용자 요청). ERP 위생 기록은 부적합이 있을 때만 남기므로,
 * 점검을 다녀왔는데 문제가 없던 카테고리는 화면에 '미점검'으로 남았다 → 카테고리·점검일을 발주 DB(tb_hygiene_check)에 적는다.
 * 기간(연·월)은 위생 기록 조회와 같은 규칙: 연·월 = 그 달, 연만 = 그 해, 둘 다 없음 = 전체.
 */
@Service
public class HygieneCheckService {
	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
	private final HygieneCheckMapper mapper;

	public HygieneCheckService(HygieneCheckMapper mapper) {
		this.mapper = mapper;
	}

	public List<Map<String, Object>> checks(String accountId, String year, String month) {
		require(accountId);
		String[] range = range(year, month);
		return mapper.list(accountId, range[0], range[1]);
	}

	/**
	 * done Y: 그 날(없으면 오늘) 점검 완료 표시. done N: 그 기간(연·월, 없으면 그 날)의 표시를 지운다.
	 * 결과로 그 기간의 표시 목록을 돌려준다.
	 */
	public Map<String, Object> mark(String accountId, String categoryKey, String checkDate, boolean done, String userId, String year, String month) {
		require(accountId);
		if (categoryKey == null || categoryKey.isBlank()) throw new IllegalArgumentException("점검 카테고리를 선택해주세요.");
		if (categoryKey.length() > 100) throw new IllegalArgumentException("카테고리 키가 너무 깁니다.");
		LocalDate today = LocalDate.now(SEOUL);
		LocalDate date;
		try {
			date = checkDate == null || checkDate.isBlank() ? today : LocalDate.parse(checkDate.trim());
		} catch (DateTimeParseException e) {
			throw new IllegalArgumentException("점검일 형식 확인 요망(YYYY-MM-DD)");
		}
		if (date.isAfter(today)) throw new IllegalArgumentException("앞으로의 날짜는 점검 완료로 표시할 수 없습니다.");
		String key = categoryKey.trim();
		if (done) mapper.mark(accountId, key, date.toString(), userId == null ? "" : userId);
		else {
			String[] range = range(year, month);
			mapper.unmark(accountId, key, range[0].isEmpty() ? date.toString() : range[0], range[1].isEmpty() ? date.toString() : range[1]);
		}
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("checks", checks(accountId, year, month));
		return result;
	}

	/** [from, to] — 빈 문자열이면 그 쪽 제한 없음 */
	static String[] range(String year, String month) {
		String y = year == null ? "" : year.trim(), m = month == null ? "" : month.trim();
		if (y.isEmpty()) return new String[] { "", "" };
		try {
			int yearValue = Integer.parseInt(y);
			if (m.isEmpty()) return new String[] { LocalDate.of(yearValue, 1, 1).toString(), LocalDate.of(yearValue, 12, 31).toString() };
			YearMonth ym = YearMonth.of(yearValue, Integer.parseInt(m));
			return new String[] { ym.atDay(1).toString(), ym.atEndOfMonth().toString() };
		} catch (RuntimeException e) {
			throw new IllegalArgumentException("조회 연·월 확인 요망");
		}
	}

	private static void require(String accountId) {
		if (accountId == null || accountId.isBlank()) throw new IllegalArgumentException("거래처를 선택해주세요.");
	}
}

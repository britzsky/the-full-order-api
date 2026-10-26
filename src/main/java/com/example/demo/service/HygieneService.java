package com.example.demo.service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 위생관리(2026-10-26): 데이터는 기존 ERP 위생관리 테이블(ErpHygieneClient). 여기서는 거래처 범위·작성자·삭제 권한을 지킨다.
 * 점검 기록 1건 = 부적합 사진·내용(problem) + 조치 사진·내용(clean) + 비고(note), 카테고리는 선택.
 */
@Service
public class HygieneService {
	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
	static final long MAX_PHOTO_BYTES = 15L * 1024 * 1024;
	private final ErpHygieneClient erp;

	public HygieneService(ErpHygieneClient erp) {
		this.erp = erp;
	}

	public Map<String, Object> categories(String accountId) {
		require(accountId);
		return Map.of("categories", erp.categories(accountId));
	}

	public Map<String, Object> records(String accountId, String year, String month) {
		require(accountId);
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("image_base", erp.baseUrl());
		result.put("records", erp.records(accountId, year, month));
		return result;
	}

	/** 거래처 카테고리 목록 전체 저장(목록에 없는 카테고리는 ERP가 삭제 처리). 새 카테고리는 키를 만든다 */
	@SuppressWarnings("unchecked")
	public Map<String, Object> saveCategories(String accountId, Object rawCategories) {
		require(accountId);
		if (!(rawCategories instanceof List<?> list) || list.isEmpty())
			throw new IllegalArgumentException("카테고리를 1개 이상 남겨주세요.");
		List<Map<String, Object>> categories = new ArrayList<>();
		Set<String> names = new HashSet<>(), keys = new HashSet<>();
		long seed = System.currentTimeMillis();
		for (int i = 0; i < list.size(); i++) {
			if (!(list.get(i) instanceof Map<?, ?>))
				throw new IllegalArgumentException("카테고리 형식을 확인해주세요.");
			Map<String, Object> source = (Map<String, Object>) list.get(i);
			String name = text(source.get("category_name"));
			if (name.isBlank()) throw new IllegalArgumentException("카테고리 이름을 입력해주세요.");
			if (!names.add(name)) throw new IllegalArgumentException("같은 이름의 카테고리가 있습니다: " + name);
			String key = text(source.get("category_key"));
			if (key.isBlank()) key = "C" + (seed + i);
			if (!keys.add(key)) throw new IllegalArgumentException("카테고리 키가 겹칩니다.");
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("category_key", key);
			row.put("category_name", name);
			row.put("description", text(source.get("description")));
			row.put("manage_point", text(source.get("manage_point")));
			row.put("sort_no", i + 1);
			row.put("use_yn", yn(source.get("use_yn"), "Y"));
			row.put("main_yn", yn(source.get("main_yn"), "N"));
			row.put("status_yn", yn(source.get("status_yn"), "Y"));
			categories.add(row);
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("account_id", accountId);
		body.put("categories", categories);
		erp.saveCategories(body);
		return Map.of("code", 200, "message", "카테고리를 저장했습니다.", "categories", erp.categories(accountId));
	}

	/**
	 * 점검 기록 저장(새 기록·수정·삭제). 거래처는 요청 거래처로 고정, 기존 기록(idx)은 그 거래처 것인지 확인(ERP는 idx로 덮어쓰므로).
	 * 삭제(del_yn=Y)는 본사만. 작성자·수정자는 로그인한 사용자.
	 */
	@SuppressWarnings("unchecked")
	public Map<String, Object> saveRecords(String accountId, Object rawRecords, String userId, boolean headquarters) {
		require(accountId);
		if (!(rawRecords instanceof List<?> list) || list.isEmpty())
			throw new IllegalArgumentException("저장할 점검 기록이 없습니다.");
		Set<String> owned = null;
		String today = LocalDate.now(SEOUL).toString();
		List<Map<String, Object>> rows = new ArrayList<>();
		for (Object item : list) {
			if (!(item instanceof Map<?, ?>))
				throw new IllegalArgumentException("점검 기록 형식을 확인해주세요.");
			Map<String, Object> source = (Map<String, Object>) item;
			String idx = text(source.get("idx"));
			boolean delete = "Y".equals(text(source.get("del_yn")));
			if (delete && !headquarters)
				throw new IllegalArgumentException("점검 기록 삭제는 본사 담당자만 할 수 있습니다.");
			if (!idx.isBlank()) {
				if (owned == null) {
					owned = new HashSet<>();
					for (Map<String, Object> row : erp.records(accountId, "", "")) owned.add(text(row.get("idx")));
				}
				if (!owned.contains(idx))
					throw new IllegalArgumentException("이 거래처의 점검 기록이 아닙니다(" + idx + "). 새로고침 후 다시 시도해주세요.");
			} else if (delete) {
				continue;
			} else if (text(source.get("problem_note")).isBlank() && text(source.get("problem_image")).isBlank()) {
				throw new IllegalArgumentException("부적합 내용이나 사진을 넣어주세요.");
			}
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("idx", idx.isBlank() ? null : Long.valueOf(idx));
			row.put("account_id", accountId);
			row.put("category_key", text(source.get("category_key")));
			for (String field : List.of("problem_image", "problem_note", "clean_image", "clean_note", "note"))
				row.put(field, text(source.get(field)));
			if (idx.isBlank()) {
				row.put("reg_dt", today);
				row.put("reg_user_id", userId);
			}
			row.put("mod_dt", today);
			row.put("mod_user_id", userId);
			row.put("del_yn", delete ? "Y" : "N");
			rows.add(row);
		}
		if (rows.isEmpty())
			return Map.of("code", 200, "message", "바뀐 점검 기록이 없습니다.", "saved", 0);
		erp.saveRecords(rows);
		return Map.of("code", 200, "message", "점검 기록을 저장했습니다.", "saved", rows.size());
	}

	/** 사진은 ERP에 올리고 저장 경로·보기 주소를 돌려준다(기록 저장 전에 올림) */
	public Map<String, Object> uploadPhoto(String accountId, MultipartFile file) {
		require(accountId);
		if (file == null || file.isEmpty()) throw new IllegalArgumentException("사진 파일이 없습니다.");
		if (file.getSize() > MAX_PHOTO_BYTES) throw new IllegalArgumentException("사진은 15MB까지 올릴 수 있습니다.");
		String type = Objects.toString(file.getContentType(), "");
		if (!type.startsWith("image/")) throw new IllegalArgumentException("사진 파일(이미지)만 올릴 수 있습니다.");
		try {
			String path = erp.uploadPhoto(file.getBytes(), Objects.toString(file.getOriginalFilename(), "photo.jpg"), type,
					LocalDate.now(SEOUL).toString(), accountId);
			return Map.of("image_path", path, "image_base", erp.baseUrl());
		} catch (java.io.IOException e) {
			throw new IllegalArgumentException("사진 파일을 읽지 못했습니다.");
		}
	}

	private static void require(String accountId) {
		if (accountId == null || accountId.isBlank()) throw new IllegalArgumentException("거래처를 선택해주세요.");
	}

	private static String yn(Object value, String fallback) {
		String text = text(value);
		return "Y".equals(text) || "N".equals(text) ? text : fallback;
	}

	private static String text(Object value) {
		return value == null ? "" : value.toString().trim();
	}
}

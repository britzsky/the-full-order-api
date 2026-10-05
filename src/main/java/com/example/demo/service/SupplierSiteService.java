package com.example.demo.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.demo.mapper.SupplierIntegrationMapper;
import com.example.demo.mapper.SupplierSiteMapper;

/**
 * 공급사 사업장 마스터(tb_supplier_site)와 거래처 매핑(tb_supplier_account_site).
 * 공급사 목록 동기화는 마스터만 갱신하고, 거래처 연결은 사람이 지정한 경우에만 만든다.
 * 거래처당 공급사별 사용 중 사업장은 1개(DB 제약). 여러 거래처가 같은 사업장을 쓰는 것은 확인 후 허용한다.
 */
@Service
public class SupplierSiteService {
	private org.springframework.context.ApplicationEventPublisher events;
	@org.springframework.beans.factory.annotation.Autowired
	public void setEvents(org.springframework.context.ApplicationEventPublisher events) { this.events=events; }
	private static final Map<String, String> SUPPLIER_NAMES = Map.of("WELSTORY", "삼성웰스토리", "OURHOME", "아워홈");

	/** 같은 공급사 사업장이 이미 다른 거래처에 지정되어 있어 사용자 확인이 필요한 경우. */
	public static class SharedSiteException extends IllegalArgumentException {
		private final List<String> accountIds;

		public SharedSiteException(List<String> accountIds) {
			super("이미 다른 거래처에 지정된 사업장입니다. 함께 사용하려면 확인 후 다시 저장하세요.");
			this.accountIds = List.copyOf(accountIds);
		}

		public List<String> accountIds() {
			return accountIds;
		}
	}

	private final SupplierSiteMapper mapper;
	private final SupplierIntegrationMapper suppliers;

	public SupplierSiteService(SupplierSiteMapper mapper, SupplierIntegrationMapper suppliers) {
		this.mapper = mapper;
		this.suppliers = suppliers;
	}

	public Long supplierId(String supplierCode, String userId) {
		String name = SUPPLIER_NAMES.get(supplierCode);
		if (name == null)
			throw new IllegalArgumentException("지원하지 않는 공급사입니다: " + supplierCode);
		Map<String, Object> supplier = new LinkedHashMap<>();
		supplier.put("supplier_code", supplierCode);
		supplier.put("supplier_name", name);
		supplier.put("user_id", userId);
		suppliers.ensureSupplier(supplier);
		return suppliers.supplierIdByCode(supplier);
	}

	/** 공급사 전체 사업장 목록 저장. 이번 목록에 없는 기존 사업장은 목록 제외(provider_listed_yn=N)로 표시한다. */
	@Transactional
	public int saveProviderSites(String supplierCode, List<Map<String, Object>> sites, String userId) {
		if (sites.isEmpty())
			throw new IllegalStateException("공급사 사업장 목록이 비어 있습니다. 기존 목록은 유지합니다.");
		Long supplierId = supplierId(supplierCode, userId);
		List<String> codes = new ArrayList<>();
		for (Map<String, Object> site : sites) {
			saveProviderSite(supplierId, site, userId);
			codes.add(site.get("external_site_code").toString());
		}
		mapper.markUnlisted(Map.of("supplier_id", supplierId, "codes", codes));
		return codes.size();
	}

	/** 사업장 한 곳만 마스터에 반영(다른 사업장의 목록 포함 여부는 건드리지 않는다). */
	public void saveProviderSite(Long supplierId, Map<String, Object> site, String userId) {
		Map<String, Object> row = new LinkedHashMap<>(site);
		row.put("supplier_id", supplierId);
		row.put("user_id", userId);
		mapper.upsertMasterSite(row);
	}

	public List<Map<String, Object>> masterSites(String supplierCode) {
		Map<String, Object> params = new LinkedHashMap<>();
		params.put("supplier_code", supplierCode);
		return mapper.masterSites(params);
	}

	public Map<String, Object> setUse(Object supplierSiteId, String useYn, String userId) {
		if (supplierSiteId == null || !List.of("Y", "N").contains(useYn))
			throw new IllegalArgumentException("supplier_site_id와 use_yn(Y/N)이 필요합니다.");
		Map<String, Object> params = new LinkedHashMap<>();
		params.put("supplier_site_id", supplierSiteId);
		params.put("use_yn", useYn);
		params.put("user_id", userId);
		if (mapper.updateMasterUse(params) == 0)
			throw new IllegalArgumentException("공급사 사업장을 찾을 수 없습니다.");
		return Map.of("code", 200, "message", "success");
	}

	public List<Map<String, Object>> mappings() {
		return mapper.activeMappings();
	}

	/**
	 * 거래처의 공급사 사업장 지정/변경/해제. siteCode가 비어 있으면 해제한다.
	 * 기존 연결은 삭제하지 않고 사용 안 함으로 바꾼다(판가·입고 이력이 참조한다).
	 */
	@Transactional
	public Map<String, Object> assign(String accountId, String supplierCode, String siteCode, boolean allowShared,
			String userId) {
		if (accountId == null || accountId.isBlank())
			throw new IllegalArgumentException("account_id는 필수입니다.");
		Long supplierId = supplierId(supplierCode, userId);
		Map<String, Object> key = new LinkedHashMap<>();
		key.put("account_id", accountId);
		key.put("supplier_id", supplierId);
		key.put("user_id", userId);
		List<Map<String, Object>> current = mapper.lockAccountMappings(key);
		String code = siteCode == null ? "" : siteCode.trim();
		if (!code.isEmpty()) {
			key.put("external_site_code", code);
			Map<String, Object> master = mapper.masterSite(key);
			if (master == null)
				throw new IllegalArgumentException("공급사 사업장 목록에 없는 코드입니다. 사업장 목록을 갱신해 주세요: " + code);
			if ("N".equals(master.get("use_yn")))
				throw new IllegalArgumentException("매핑 제외로 표시된 사업장입니다: " + master.get("external_site_name"));
			List<String> others = mapper.otherAccountsUsing(key);
			if (!others.isEmpty() && !allowShared)
				throw new SharedSiteException(others);
		}
		for (Map<String, Object> row : current) {
			if ("Y".equals(row.get("active_yn")) && !code.equals(row.get("external_site_code"))) {
				Map<String, Object> off = new LinkedHashMap<>();
				off.put("supplier_account_site_id", row.get("supplier_account_site_id"));
				off.put("user_id", userId);
				mapper.deactivateMapping(off);
			}
		}
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("code", 200);
		result.put("account_id", accountId);
		result.put("supplier_code", supplierCode);
		result.put("external_site_code", code);
		if (code.isEmpty()) {
			result.put("supplier_account_site_id", null);
			return result;
		}
		if (mapper.activateMapping(key) == 0)
			mapper.insertMapping(key);
		result.put("supplier_account_site_id", mapper.mappingId(key));
		if(events!=null && current.stream().noneMatch(row->"Y".equals(row.get("active_yn")) && code.equals(row.get("external_site_code"))))
			events.publishEvent(new WorkspaceMappingAssigned(accountId,userId));
		return result;
	}

	/**
	 * 상품 동기화용: 거래처에 이미 이 공급사의 다른 사업장이 지정되어 있으면 막고, 아무것도 없으면 새로 지정한다.
	 * 호출 전에 사업장이 마스터에 저장되어 있어야 한다.
	 */
	@Transactional
	public Long requireOrAssign(String accountId, String supplierCode, String siteCode, String userId) {
		Long supplierId = supplierId(supplierCode, userId);
		Map<String, Object> key = new LinkedHashMap<>();
		key.put("account_id", accountId);
		key.put("supplier_id", supplierId);
		boolean alreadyMapped = false;
		for (Map<String, Object> row : mapper.lockAccountMappings(key)) {
			if (!"Y".equals(row.get("active_yn")))
				continue;
			if (!siteCode.equals(row.get("external_site_code")))
				throw new IllegalArgumentException("이 거래처는 " + SUPPLIER_NAMES.get(supplierCode) + " '"
						+ row.get("external_site_name") + "(" + row.get("external_site_code")
						+ ")' 사업장에 지정되어 있습니다. 사업장 매핑에서 변경한 뒤 저장하세요.");
			alreadyMapped = true;
		}
		// 이미 지정된 매핑이면 공유 여부는 지정 당시 확인된 것이므로 다시 묻지 않는다.
		Object id = assign(accountId, supplierCode, siteCode, alreadyMapped, userId).get("supplier_account_site_id");
		return Long.valueOf(id.toString());
	}
}

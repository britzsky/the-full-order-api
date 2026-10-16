package com.example.demo.mapper;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SupplierSiteMapper {
	int upsertMasterSite(Map<String, Object> params);

	int markUnlisted(Map<String, Object> params);

	List<Map<String, Object>> masterSites(Map<String, Object> params);

	Map<String, Object> masterSite(Map<String, Object> params);

	int updateMasterUse(Map<String, Object> params);

	List<Map<String, Object>> activeMappings();

	List<Map<String, Object>> lockAccountMappings(Map<String, Object> params);

	List<String> otherAccountsUsing(Map<String, Object> params);

	int deactivateMapping(Map<String, Object> params);

	int activateMapping(Map<String, Object> params);

	int insertMapping(Map<String, Object> params);

	Long mappingId(Map<String, Object> params);

	/** 거래처 매핑이 가리키는 사업장 마스터 ID(상품·판가 저장 단위) */
	Long mappingSiteId(Map<String, Object> params);
}

package com.example.demo.service;

/**
 * 판가(tb_supplier_site_product_offer)를 읽을 사업장. SupplierIntegrationMapper.xml의 priceSiteJoin·priceSiteId와 같은 SQL.
 * 웰스토리 판가는 지급처 대표 사업장(repSoldtoYn=Y) 마스터에만 저장하므로 웰스토리 사업장은 대표 사업장 판가를 읽는다.
 * 아워홈은 대표 코드가 있어도 사업장 그대로(판가가 사업장별).
 */
public final class SupplierPriceSite {
	private SupplierPriceSite() {
	}

	/** alias = tb_supplier_account_site 별칭. 그 뒤에 붙인다. */
	public static String join(String alias) {
		return " LEFT JOIN tb_supplier_site " + alias + "_pm ON " + alias + "_pm.supplier_site_id=" + alias + ".supplier_site_id"
				+ " AND " + alias + "_pm.representative_site_code<>''"
				+ " AND " + alias + "_pm.supplier_id IN (SELECT ws.supplier_id FROM tb_supplier ws WHERE ws.supplier_code='WELSTORY')"
				+ " LEFT JOIN tb_supplier_site " + alias + "_pr ON " + alias + "_pr.supplier_id=" + alias + "_pm.supplier_id"
				+ " AND " + alias + "_pr.external_site_code=" + alias + "_pm.representative_site_code ";
	}

	/** offer.supplier_site_id와 비교할 판가 사업장 ID */
	public static String id(String alias) {
		return "COALESCE(" + alias + "_pr.supplier_site_id," + alias + ".supplier_site_id)";
	}
}

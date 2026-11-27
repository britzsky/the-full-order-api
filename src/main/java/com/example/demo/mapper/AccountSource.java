package com.example.demo.mapper;

/**
 * 거래처 목록 = ERP 거래처(the_full.tb_account, 읽기만) + 발주 앱 전용 거래처(tb_order_account, 2026-11-27 더채움 본사 등).
 * ERP 거래처를 목록·이름·존재 확인에 쓰는 곳은 the_full.tb_account 대신 이것을 쓴다(위생·예산처럼 ERP 기능 자체는 제외).
 * XML 매퍼: ${@com.example.demo.mapper.AccountSource@TABLE}
 */
public final class AccountSource {
	private AccountSource() {
	}

	/** FROM/JOIN에 별칭과 함께 쓴다. meal_plan_yn: ERP 거래처는 Y, 발주 앱 전용 거래처는 칸 값(식단표를 안 쓰면 N). */
	public static final String TABLE = "(SELECT account_id,account_name,account_address,account_address_detail,account_type,"
			+ "account_rqd_member,account_headcount,meal_type,del_yn,'Y' meal_plan_yn FROM the_full.tb_account"
			+ " UNION ALL SELECT account_id,account_name,account_address,account_address_detail,account_type,"
			+ "account_rqd_member,account_headcount,meal_type,del_yn,meal_plan_yn FROM tb_order_account)";

	/** 한 거래처 이름(상관 서브쿼리용 — 두 표를 각각 키로 찾는다). idExpr = 거래처 ID 식, 예: "po.account_id" */
	public static String name(String idExpr) {
		return "COALESCE((SELECT x_a.account_name FROM the_full.tb_account x_a WHERE x_a.account_id=" + idExpr + " LIMIT 1),"
				+ "(SELECT x_o.account_name FROM tb_order_account x_o WHERE x_o.account_id=" + idExpr + " LIMIT 1))";
	}
}

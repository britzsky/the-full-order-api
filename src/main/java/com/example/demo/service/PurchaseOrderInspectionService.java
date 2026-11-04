package com.example.demo.service;

import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 발주서(공급사·사업장·납품일 한 장) 단위 검수완료. 현장에서 검수서로 물건을 확인하고 누르면 입고 처리가 열린다.
 * 같은 납품일이라도 공급사마다 도착 시각이 달라 공급사 발주서별로 따로 기록한다(공급사 API에 도착 시각이 없다).
 */
@Service
public class PurchaseOrderInspectionService {
	/** 검수완료를 기록할 수 있는 상태: 공급사 접수가 확인됐고 아직 다 입고되지 않은 발주서 */
	static final Set<String> INSPECTABLE = Set.of("ORDERED", "CONFIRMED", "PARTIAL_RECEIVED", "NOT_RECEIVED");
	private final JdbcTemplate jdbc;

	public PurchaseOrderInspectionService(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** done=true 검수완료, false 검수 취소(전체 입고 완료된 발주서는 취소 불가) */
	public Map<String, Object> inspect(String account, String orderId, String user, boolean done) {
		if (account == null || account.isBlank() || orderId == null || orderId.isBlank())
			throw new IllegalArgumentException("거래처와 발주서를 확인하세요.");
		var found = jdbc.queryForList("SELECT status FROM the_full_order.tb_account_purchase_order WHERE account_id=? AND purchase_order_id=?",
				account, orderId);
		if (found.isEmpty())
			throw new IllegalArgumentException("발주서를 찾을 수 없습니다.");
		String status = String.valueOf(found.get(0).get("status"));
		if (!INSPECTABLE.contains(status))
			throw new IllegalArgumentException(done ? "공급사 접수 확인 후 검수 가능" : "입고 완료된 발주서 · 검수 취소 불가");
		if (done)
			jdbc.update("UPDATE the_full_order.tb_account_purchase_order SET inspected_at=COALESCE(inspected_at,NOW()),"
					+ "inspected_by=COALESCE(inspected_by,NULLIF(?,'')) WHERE account_id=? AND purchase_order_id=?", user == null ? "" : user, account, orderId);
		else
			jdbc.update("UPDATE the_full_order.tb_account_purchase_order SET inspected_at=NULL,inspected_by=NULL WHERE account_id=? AND purchase_order_id=?",
					account, orderId);
		return jdbc.queryForMap("SELECT purchase_order_id,status,inspected_at,inspected_by FROM the_full_order.tb_account_purchase_order WHERE account_id=? AND purchase_order_id=?",
				account, orderId);
	}
}

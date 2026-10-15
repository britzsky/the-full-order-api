package com.example.demo.service;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SupplierOrderMonitorServiceTest {
	final WorkspaceNotificationService notifications = mock(WorkspaceNotificationService.class);
	final SupplierOrderMonitorService service = new SupplierOrderMonitorService(mock(JdbcTemplate.class),
			mock(OrderWorkflowService.class), mock(OurhomeSiteService.class), notifications);
	final Map<String, Object> order = Map.of("account_id", "A", "purchase_order_id", "PO1", "client_ord", "C1",
			"delivery_date", "2026-10-16");

	static Map<String, Object> item(String no, String qty) {
		return Map.of("client_ord_item", no, "order_qty", qty);
	}

	static Map<String, String> line(String no, String shipped, String qty, String deleted) {
		return Map.of("extnOrderKey", "C1", "extnOrderNo", no, "prcsStatYn", shipped, "clhaqty", qty, "delYn", deleted);
	}

	@Test
	void allShippedAsksForReceiptAndFlagsQuantityDifference() {
		boolean notified = service.notify(order, List.of(item("1", "2.000"), item("2", "5")),
				List.of(line("1", "Y", "2", "N"), line("2", "Y", "4", "N")));
		assertThat(notified).isTrue();
		verify(notifications).supplierStatus(eq("A"), eq("PO1"),
				eq("아워홈 출고완료 · 2026-10-16 · 2품목 입고 처리 요망 · 출고 수량 다름 1품목"), contains("2=SHIPPED:4"));
	}

	@Test
	void deletedOrMissingLinesNeedConfirmationEvenIfOthersShipped() {
		service.notify(order, List.of(item("1", "1"), item("2", "1"), item("3", "1")),
				List.of(line("1", "Y", "1", "N"), line("2", "N", "1", "Y")));
		verify(notifications).supplierStatus(eq("A"), eq("PO1"),
				eq("아워홈 주문 확인 요망 · 2026-10-16 · 아워홈 삭제 1품목 · 주문 조회 안 됨 1품목"), anyString());
	}

	@Test
	void notShippedYetResolvesEarlierNoticeInsteadOfAlerting() {
		boolean notified = service.notify(order, List.of(item("1", "1")), List.of(line("1", "N", "0", "N")));
		assertThat(notified).isFalse();
		verify(notifications).resolveSupplierStatus("A", "PO1");
		verify(notifications, never()).supplierStatus(any(), any(), any(), any());
	}

	@Test
	void partialShipmentShowsProgress() {
		service.notify(order, List.of(item("1", "1"), item("2", "1")), List.of(line("1", "Y", "1", "N"), line("2", "N", "", "N")));
		verify(notifications).supplierStatus(eq("A"), eq("PO1"), eq("아워홈 일부 출고 · 2026-10-16 · 1/2품목"), anyString());
	}
}

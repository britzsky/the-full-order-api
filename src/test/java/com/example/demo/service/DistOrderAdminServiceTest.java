package com.example.demo.service;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;

@SuppressWarnings("unchecked")
class DistOrderAdminServiceTest {
	private static Map<String, Object> item(long partner, String type, String status, int qty, int shipped, int canceled) {
		Map<String, Object> row = new HashMap<>();
		row.put("partner_id", partner);
		row.put("fulfillment_type", type);
		row.put("item_status", status);
		row.put("qty", new BigDecimal(qty));
		row.put("shipped_qty", new BigDecimal(shipped));
		row.put("canceled_qty", new BigDecimal(canceled));
		row.put("remaining_qty", DistOrderAdminService.remaining(row));
		return row;
	}

	@Test void remainingExcludesShippedAndCanceled() {
		assertThat(DistOrderAdminService.remaining(item(1, "DROP_SHIP", "PO_SENT", 5, 2, 1))).isEqualByComparingTo("2");
		assertThat(DistOrderAdminService.remaining(item(1, "DROP_SHIP", "CANCELED", 5, 0, 5))).isEqualByComparingTo("0");
	}

	@Test void pendingOrderOnlyAllowsDepositAndCancel() {
		var actions = DistOrderAdminService.actions(Map.of("order_status", "PENDING_PAYMENT"), List.of(item(1, "DROP_SHIP", "ORDERED", 2, 0, 0)), List.of());
		assertThat(actions.get("confirm_deposit")).isEqualTo(true);
		assertThat((List<?>) actions.get("po_partner_ids")).isEmpty();
		assertThat(actions.get("add_shipment")).isEqualTo(false);
		assertThat(actions.get("cancel")).isEqualTo(true);
	}

	@Test void paidOrderListsPartnersNeedingPurchaseOrders() {
		var items = List.of(item(1, "DROP_SHIP", "ORDERED", 2, 0, 0), item(2, "DROP_SHIP", "PO_SENT", 1, 0, 0), item(3, "OWN_STOCK", "ORDERED", 1, 0, 0));
		var actions = DistOrderAdminService.actions(Map.of("order_status", "PAID"), items, List.of());
		assertThat((List<Object>) actions.get("po_partner_ids")).containsExactly(1L);
		assertThat(actions.get("prepare_own_stock")).isEqualTo(true);
		assertThat(actions.get("add_shipment")).isEqualTo(true);
	}

	@Test void shippedOrRequestedOrdersCannotBeCanceledByHq() {
		assertThat(DistOrderAdminService.actions(Map.of("order_status", "PARTIALLY_SHIPPED"), List.of(item(1, "DROP_SHIP", "SHIPPED", 2, 2, 0), item(1, "DROP_SHIP", "PO_SENT", 1, 0, 0)), List.of()).get("cancel")).isEqualTo(false);
		assertThat(DistOrderAdminService.actions(Map.of("order_status", "PAID"), List.of(item(1, "DROP_SHIP", "ORDERED", 2, 0, 0)), List.of(Map.of("status", "REQUESTED"))).get("cancel")).isEqualTo(false);
		assertThat(DistOrderAdminService.actions(Map.of("order_status", "CANCELED"), List.of(item(1, "DROP_SHIP", "CANCELED", 2, 0, 2)), List.of()).get("cancel")).isEqualTo(false);
	}

	@Test void truthyReadsMysqlFlags() {
		assertThat(DistOrderAdminService.truthy(1L)).isTrue();
		assertThat(DistOrderAdminService.truthy(0)).isFalse();
		assertThat(DistOrderAdminService.truthy(true)).isTrue();
		assertThat(DistOrderAdminService.truthy(null)).isFalse();
	}
}

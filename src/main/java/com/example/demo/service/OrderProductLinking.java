package com.example.demo.service;

import static com.example.demo.service.OrderWorkflowService.*;
import java.util.*;
import com.example.demo.mapper.OrderWorkflowMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Called inside the order/receipt transaction, with the account ingredient locked first. */
final class OrderProductLinking {
    private final OrderWorkflowMapper mapper;
    private final ObjectMapper json;

    OrderProductLinking(OrderWorkflowMapper mapper, ObjectMapper json) {
        this.mapper = mapper;
        this.json = json;
    }

    void apply(Map<String,Object> row, boolean acceptedOrder) {
        if (!"PENDING".equals(text(row.get("ingredient_link_status")))) return;
        row.put("ingredient_link_status", "CONFLICT");
        var source = mapper.lockSourceIngredient(row);
        if (source != null) {
            var product = mapper.lockAccountProduct(row);
            String target = product == null ? "" : text(product.get("mapped_ingredient_id"));
            String ingredient = text(row.get("source_ingredient_id"));
            boolean same = ingredient.equals(target) && "Y".equals(text(product.get("active_yn")));
            var context = mapper.linkContext(row);
            boolean unchanged = text(row.get("link_context_snapshot")).equals(linkContextFingerprint(context));
            // Other products may have been added meanwhile. Only a changed target blocks the added link.
            boolean targetUnchanged = targetContext(context, row).equals(targetContext(snapshotContext(row), row));
            if (same || (product != null && (target.isBlank() || target.equals(ingredient)) && (unchanged || targetUnchanged)
                    && compatible(row, source) && mapper.linkReceivedProduct(row) == 1)) {
                row.put("ingredient_link_status", "LINKED");
                if (acceptedOrder && unchanged && mapper.newerAcceptedSourceOrders(row) == 0) {
                    var previous = mapper.sourceLinks(row).stream()
                        .filter(link -> "Y".equals(text(link.get("preferred_yn"))))
                        .map(link -> text(link.get("supplier_product_id"))).toList();
                    if (!previous.equals(List.of(text(row.get("supplier_product_id"))))) {
                        mapper.clearSourcePreferred(row);
                        if (mapper.setSourcePreferred(row) != 1) throw new IllegalStateException("우선 상품을 변경하지 못했습니다.");
                        if (previous.isEmpty()) {
                            mapper.updateLinkStatus(row);
                            return;
                        }
                        ObjectNode snapshot = snapshot(row);
                        ObjectNode change = snapshot.putObject("automatic_preference");
                        change.set("previous_product_ids", json.valueToTree(previous));
                        change.put("applied_context", linkContextFingerprint(mapper.linkContext(row)));
                        change.put("undone", false);
                        row.put("product_snapshot", snapshot.toString());
                        mapper.updateProductSnapshot(row);
                    }
                }
            }
        }
        mapper.updateLinkStatus(row);
    }

    private boolean compatible(Map<String,Object> row, Map<String,Object> source) {
        try {
            QuantityUnits.convert(number(row.get("base_qty_per_order_unit")), row.get("base_unit"), source.get("base_unit"));
            return mapper.productStockUnits(row).stream().allMatch(unit -> QuantityUnits.unit(unit).equals(QuantityUnits.unit(row.get("base_unit"))));
        } catch (IllegalArgumentException ex) { return false; }
    }

    private String targetContext(List<Map<String,Object>> context, Map<String,Object> row) {
        return linkContextFingerprint(context.stream().filter(p -> text(p.get("supplier_product_id")).equals(text(row.get("supplier_product_id")))).toList());
    }

    @SuppressWarnings("unchecked")
    private List<Map<String,Object>> snapshotContext(Map<String,Object> row) {
        var context = snapshot(row).path("link_context");
        return context.isArray() ? json.convertValue(context, List.class) : List.of();
    }

    ObjectNode snapshot(Map<String,Object> row) {
        try {
            var value = json.readTree(text(row.get("product_snapshot")));
            return value instanceof ObjectNode object ? object : json.createObjectNode();
        } catch (Exception ex) { return json.createObjectNode(); }
    }

    void undo(Map<String,Object> row) {
        if (mapper.lockSourceIngredient(row) == null) throw new IllegalArgumentException("식재료를 찾을 수 없습니다.");
        ObjectNode snapshot = snapshot(row);
        var change = snapshot.path("automatic_preference");
        if (!change.isObject()) throw new IllegalArgumentException("되돌릴 자동 전환이 없습니다.");
        if (change.path("undone").asBoolean()) return;
        if (!change.path("applied_context").asText().equals(linkContextFingerprint(mapper.linkContext(row))))
            throw new IllegalArgumentException("이후 상품 연결이 변경되어 이전 우선 상품으로 되돌릴 수 없습니다.");
        mapper.clearSourcePreferred(row);
        for (var id : change.path("previous_product_ids")) {
            var p = new HashMap<>(row);
            p.put("supplier_product_id", id.asText());
            if (mapper.setSourcePreferred(p) != 1) throw new IllegalArgumentException("이전 상품 연결이 변경되었습니다.");
        }
        ((ObjectNode) change).put("undone", true);
        row.put("product_snapshot", snapshot.toString());
        mapper.updateProductSnapshot(row);
    }
}

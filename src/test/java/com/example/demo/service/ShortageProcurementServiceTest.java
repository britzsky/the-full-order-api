package com.example.demo.service;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.anyMap;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.example.demo.mapper.ShortageProcurementMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;

class ShortageProcurementServiceTest {
    private final ShortageProcurementMapper mapper=mock(ShortageProcurementMapper.class);
    private final SupplierIntegrationService suppliers=mock(SupplierIntegrationService.class);
    private final ObjectMapper json=new ObjectMapper();
    private final ShortageProcurementService service=new ShortageProcurementService(mapper,suppliers,json);
    private Map<String,Object> criteria;
    private Map<String,Object> offer;
    private Map<String,Object> raw(int quantity){return new HashMap<>(Map.of("ingredient_id","RICE","ingredient_name","쌀","master_unit","g","base_unit","g","quantity",quantity,"menu_name","메뉴","item_status","ORDERED","order_status","ORDERED","delivery_date",LocalDate.now().toString()));}
    @BeforeEach void setup() {
        criteria=new HashMap<>(Map.of("account_id","ACCOUNT","source","PLAN","date_from",LocalDate.now().toString(),"date_to",LocalDate.now().plusDays(7).toString(),"sold_to","A0000001"));
        when(mapper.demand(anyMap())).thenReturn(List.of(raw(100),raw(200)));
        when(mapper.stock(anyMap())).thenReturn(List.of(raw(50),raw(25)));
        when(mapper.targets(anyMap())).thenReturn(List.of(raw(10),raw(20)));
        when(mapper.incoming(anyMap())).thenReturn(List.of(raw(25)));
        offer=new HashMap<>(Map.of("ingredient_id","RICE","supplier_offer_id",1,"base_qty",100,"base_unit","g","purchase_price",1000,
                "minimum_order_qty",1,"order_increment_qty",1,"decimal_order_allowed","N","product_status","ACTIVE","orderable_yn","Y"));
        offer.put("orderability_status","AVAILABLE");
        offer.put("supplier_code","WELSTORY");offer.put("tax_type","No tax");when(suppliers.offers(anyMap())).thenReturn(List.of(offer));
    }
    private JsonNode result(){return json.valueToTree(service.shortages(criteria));}
    private JsonNode request(int qty){var p=json.createObjectNode();p.set("criteria",json.valueToTree(criteria));p.putArray("items").addObject().put("ingredient_id","RICE").put("supplier_offer_id",1).put("order_qty",qty);return p;}
    @Test void aggregatesMenusAndWarehousesBeforeDeductingStockAndIncomingOnce() {
        JsonNode rows=result().path("ingredients");assertThat(rows.size()).isEqualTo(1);
        var row=rows.get(0);assertThat(row.path("required_qty").decimalValue()).isEqualByComparingTo("300");
        assertThat(row.path("current_qty").decimalValue()).isEqualByComparingTo("75");
        assertThat(row.path("safe_stock_qty").decimalValue()).isEqualByComparingTo("20");
        assertThat(row.path("shortage_qty").decimalValue()).isEqualByComparingTo("220");
        assertThat(row.path("offers").get(0).path("recommended_qty").decimalValue()).isEqualByComparingTo("3");
        var preview=json.valueToTree(service.preview(request(3)));
        assertThat(preview.path("item_amount").decimalValue()).isEqualByComparingTo("3000");
        assertThat(preview.path("items").get(0).path("vat_amount").decimalValue()).isZero();
    }
    @Test void pastUnconfirmedMealUsageIsDeductedFromAvailableStock() {
        // 주 단위 확정 전: 월·화에 이미 쓴 60g은 재고 75g에 아직 남아 있으므로 사용 가능 재고는 15g이다.
        when(mapper.unconfirmedUsage(anyMap())).thenReturn(List.of(raw(60)));
        var row=result().path("ingredients").get(0);
        assertThat(row.path("current_qty").decimalValue()).as("기록상 현재고는 그대로").isEqualByComparingTo("75");
        assertThat(row.path("unconfirmed_usage_qty").decimalValue()).isEqualByComparingTo("60");
        assertThat(row.path("available_qty").decimalValue()).isEqualByComparingTo("15");
        assertThat(row.path("shortage_qty").decimalValue()).as("220 + 확정 대기 60").isEqualByComparingTo("280");
    }
    @Test void blockedOfferShowsRealReasonInsteadOfSupplierSaleStatus() {
        // 아워홈 판매상태 문구('발주가능')가 있어도 부족량이 없어 막힌 것이면 그 이유를 보여준다.
        when(mapper.demand(anyMap())).thenReturn(List.of());
        when(mapper.targets(anyMap())).thenReturn(List.of());
        when(mapper.incoming(anyMap())).thenReturn(List.of());
        criteria.put("source","STOCK"); criteria.put("include_sufficient",true);
        offer.put("availability_reason","발주가능");
        when(mapper.stock(anyMap())).thenReturn(List.of(raw(500)));
        var candidate=result().path("ingredients").get(0).path("offers").get(0);
        assertThat(candidate.path("orderable").asBoolean()).isFalse();
        assertThat(candidate.path("orderability_reason").asText()).isEqualTo("부족량 없음");
    }
    @Test void stockSourceOrdersEmptyStockWithoutHistoryAsManualQuantity() {
        criteria.put("source","STOCK");
        when(mapper.stock(anyMap())).thenReturn(List.of());
        when(mapper.incoming(anyMap())).thenReturn(List.of());
        when(mapper.targets(anyMap())).thenReturn(List.of(raw(0)));
        var row=result().path("ingredients").get(0);
        assertThat(row.path("quantity_basis").asText()).isEqualTo("MANUAL");
        assertThat(row.path("issues").isEmpty()).as("필요량 미산정으로 막지 않음").isTrue();
        var offer=row.path("offers").get(0);
        assertThat(offer.path("orderable").asBoolean()).isTrue();
        assertThat(offer.path("recommended_qty").decimalValue()).as("기본값은 최소 발주수량").isEqualByComparingTo("1");
        verify(mapper,never()).demand(anyMap());verify(mapper,never()).history(anyMap());
        assertThat(json.valueToTree(service.preview(request(5))).path("items").get(0).path("order_qty").decimalValue()).as("수량은 사용자가 정함").isEqualByComparingTo("5");
        assertThatThrownBy(()->service.preview(request(1000))).hasMessageContaining("발주수량 초과(최대 999)");
    }
    @Test void stockSourceUsesSafetyStockGapWhenConfigured() {
        criteria.put("source","STOCK");
        when(mapper.stock(anyMap())).thenReturn(List.of());
        when(mapper.incoming(anyMap())).thenReturn(List.of());
        var row=result().path("ingredients").get(0);
        assertThat(row.path("quantity_basis").asText()).isEqualTo("CALCULATED");
        assertThat(row.path("shortage_qty").decimalValue()).as("안전재고 20 − 재고 0").isEqualByComparingTo("20");
        assertThatThrownBy(()->service.preview(request(2))).as("계산된 행은 권장수량 상한 유지").hasMessageContaining("권장 발주수량");
        when(mapper.stock(anyMap())).thenReturn(List.of(raw(50)));
        assertThat(result().path("ingredients").isEmpty()).as("재고가 안전재고 이상이면 제외").isTrue();
    }
    @Test void stockStatusSeparatesUnmetDemandFromSafetyStockGap() {
        assertThat(result().path("ingredients").get(0).path("stock_status").asText()).as("필요량 300 > 재고 75 + 입고 25").isEqualTo("RED");
        when(mapper.demand(anyMap())).thenReturn(List.of(raw(40),raw(50)));
        var row=result().path("ingredients").get(0);
        assertThat(row.path("shortage_qty").decimalValue()).isEqualByComparingTo("10");
        assertThat(row.path("stock_status").asText()).as("필요량 90은 채우고 안전재고 20만 미달").isEqualTo("YELLOW");
        assertThat(ShortageProcurementService.stockStatus(java.math.BigDecimal.ZERO,java.math.BigDecimal.TEN)).isEqualTo("GREEN");
    }
    @Test void stoppedProductsCannotReachPreviewAndUnknownVatIsNotInvented() {
        offer.put("orderable_yn","N");offer.put("product_status","STOPPED");offer.put("availability_reason","QA STOP");
        assertThat(result().path("ingredients").get(0).path("offers").get(0).path("orderable").asBoolean()).isFalse();
        assertThatThrownBy(()->service.preview(request(3))).hasMessageContaining("QA STOP");
        offer.put("orderable_yn","Y");offer.put("product_status","ACTIVE");offer.put("tax_type","Full tax");
        JsonNode row=json.valueToTree(service.preview(request(3))).path("items").get(0);
        assertThat(row.path("vat_amount").isNull()).isTrue();assertThat(row.path("supply_amount").isNull()).isTrue();
    }
    @Test void duplicateSelectionAndChangedOutstandingOrdersAreRejected() {
        var duplicate=request(3).deepCopy();((com.fasterxml.jackson.databind.node.ArrayNode)duplicate.path("items")).add(duplicate.path("items").get(0).deepCopy());
        assertThatThrownBy(()->service.preview(duplicate)).hasMessageContaining("중복");
        when(mapper.incoming(anyMap())).thenReturn(List.of(raw(500)));
        assertThat(result().path("ingredients").isEmpty()).isTrue();
        assertThatThrownBy(()->service.preview(request(3))).hasMessageContaining("부족량 변경됨");
    }
    @Test void periodicDemandUsesDailyTotalsAndConversionErrorsBlockOrdering() {
        criteria.put("source","INVENTORY");criteria.put("coverage_days",2);
        var a=raw(100);a.put("meal_date","2026-09-01");var b=raw(200);b.put("meal_date","2026-09-01");var c=raw(100);c.put("meal_date","2026-09-02");
        when(mapper.history(anyMap())).thenReturn(List.of(a,b,c));
        assertThat(result().path("ingredients").get(0).path("shortage_qty").decimalValue()).isEqualByComparingTo("340");
        var stock=raw(10);stock.put("base_unit","ml");when(mapper.stock(anyMap())).thenReturn(List.of(stock));
        assertThat(result().path("ingredients").get(0).path("issues").isEmpty()).isFalse();
        assertThatThrownBy(()->service.preview(request(3))).hasMessageContaining("발주 불가");
    }
    @Test void directEntryDefaultsToInventoryIncludingTodaysHistory() {
        criteria.remove("source");
        var today=raw(300);today.put("meal_date",LocalDate.now().toString());
        when(mapper.history(anyMap())).thenReturn(List.of(today));
        var response=result();
        assertThat(response.path("criteria").path("source").asText()).isEqualTo("INVENTORY");
        assertThat(response.path("ingredients").get(0).path("shortage_qty").decimalValue()).isEqualByComparingTo("230");
        verify(mapper,never()).demand(anyMap());
    }
    @Test void respectsMinimumIncrementAndIntegerOnlyOrdering() {
        assertThat(ShortageProcurementService.recommended(new BigDecimal("21000"),new BigDecimal("20000"),BigDecimal.ONE,BigDecimal.ONE,"N")).isEqualByComparingTo("2");
        assertThat(ShortageProcurementService.recommended(BigDecimal.ONE,BigDecimal.TEN,new BigDecimal("4"),new BigDecimal("3"),"N")).isEqualByComparingTo("6");
        assertThat(ShortageProcurementService.recommended(BigDecimal.ONE,BigDecimal.TEN,BigDecimal.ONE,new BigDecimal("1.5"),"N")).isEqualByComparingTo("3");
    }
    @Test void unsavedPlanUsesRecipesWithoutHistoryOrSavedMealServices() {
        criteria.put("source","DRAFT");criteria.put("draft_meals",List.of(Map.of("menu_id","A","planned_servings",20),Map.of("menu_id","B","planned_servings",10)));
        when(mapper.draftDemand(anyMap())).thenReturn(List.of(raw(2000),raw(1000)));
        var row=result().path("ingredients").get(0);
        assertThat(row.path("required_qty").decimalValue()).isEqualByComparingTo("3000");
        assertThat(row.path("shortage_qty").decimalValue()).isEqualByComparingTo("2920");
        verify(mapper,never()).history(anyMap());verify(mapper,never()).demand(anyMap());
    }
    @Test void zeroStockWithoutDemandIsVisibleWithAnExplanationAndCannotBeOrdered() {
        criteria.put("source","INVENTORY");
        when(mapper.stock(anyMap())).thenReturn(List.of(raw(0)));when(mapper.targets(anyMap())).thenReturn(List.of(raw(0)));
        var row=result().path("ingredients").get(0);
        assertThat(row.path("issues").toString()).contains("필요량 미산정");
        assertThat(row.path("offers").get(0).path("orderable").asBoolean()).isFalse();
    }
    @Test void missingDraftRecipesAndInvalidServingsAreExplained() {
        criteria.put("source","DRAFT");criteria.put("draft_meals",List.of(Map.of("menu_id","M","planned_servings",0)));
        assertThatThrownBy(this::result).hasMessageContaining("예정 식수");
        criteria.put("draft_meals",List.of(Map.of("menu_id","M","planned_servings",10)));
        when(mapper.draftDemand(anyMap())).thenReturn(List.of(Map.of("menu_id","M")));
        assertThatThrownBy(this::result).hasMessageContaining("레시피").hasMessageContaining("M");
    }
    @Test void lateDeliveryDoesNotHideEarlierShortage() {
        var incoming=raw(500);incoming.put("delivery_date",LocalDate.now().plusDays(3).toString());
        when(mapper.incoming(anyMap())).thenReturn(List.of(incoming));
        var row=result().path("ingredients").get(0);
        assertThat(row.path("shortage_qty").decimalValue()).isEqualByComparingTo("245");
        assertThat(row.path("needed_by").asText()).isEqualTo(LocalDate.now().toString());
    }
    @Test void unknownOrdersAreNotSupplyAndBlockDuplicateOrders() {
        var incoming=raw(500);incoming.put("item_status","UNKNOWN");incoming.put("purchase_order_id","PENDING-1");
        when(mapper.incoming(anyMap())).thenReturn(List.of(incoming));
        var row=result().path("ingredients").get(0);
        assertThat(row.path("incoming_qty").decimalValue()).isZero();
        assertThat(row.path("pending_incoming_qty").decimalValue()).isEqualByComparingTo("500");
        assertThat(row.path("pending_order_ids").toString()).contains("PENDING-1");
        assertThatThrownBy(()->service.preview(request(3))).hasMessageContaining("발주 불가");
    }
    @Test void overdueOrdersAndDeliveryAfterDemandRequireReview() {
        var incoming=raw(25);incoming.put("delivery_date",LocalDate.now().minusDays(1).toString());
        when(mapper.incoming(anyMap())).thenReturn(List.of(incoming));
        assertThat(result().path("ingredients").get(0).path("overdue_incoming_qty").decimalValue()).isEqualByComparingTo("25");
        assertThatThrownBy(()->service.preview(request(3))).hasMessageContaining("발주 불가");
        when(mapper.incoming(anyMap())).thenReturn(List.of());
        criteria.put("delivery_date",LocalDate.now().plusDays(1).toString());
        assertThat(result().path("ingredients").get(0).path("issues").toString()).contains("필요일이 납품일보다 빠름 · 납품일자 확인 요망");
    }
    @Test void reviewProductIsNotRecommendedAndNeedsExplicitPreviewConfirmation() {
        offer.put("orderability_status","REVIEW_REQUIRED");
        assertThat(result().path("ingredients").get(0).path("recommended_offer_id").isNull()).isTrue();
        assertThatThrownBy(()->service.preview(request(3))).hasMessageContaining("납품일자·마감 조건 확인 요망");
        var body=request(3);((com.fasterxml.jackson.databind.node.ObjectNode)body.path("items").get(0)).put("delivery_terms_confirmed",true);
        assertThat(json.valueToTree(service.preview(body)).path("items").get(0).path("delivery_terms_confirmed").asBoolean()).isTrue();
    }
    @Test void preferredProductIsKeptInsteadOfCheaperReplacement() {
        offer.put("preferred_yn","Y");
        var cheaper=new HashMap<>(offer);cheaper.put("supplier_offer_id",2);cheaper.put("purchase_price",1);cheaper.put("preferred_yn","N");
        when(suppliers.offers(anyMap())).thenReturn(List.of(cheaper,offer));
        assertThat(result().path("ingredients").get(0).path("recommended_offer_id").asInt()).isEqualTo(1);
        offer.put("orderable_yn","N");
        assertThat(result().path("ingredients").get(0).path("recommended_offer_id").isNull()).isTrue();
    }
}

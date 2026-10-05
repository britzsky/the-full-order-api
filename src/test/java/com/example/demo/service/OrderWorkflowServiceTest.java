package com.example.demo.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import com.example.demo.mapper.OrderWorkflowMapper;
import com.example.demo.mapper.SupplierIntegrationMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

class OrderWorkflowServiceTest {
    private final OrderWorkflowMapper mapper=mock(OrderWorkflowMapper.class);
    private final SupplierIntegrationMapper catalog=mock(SupplierIntegrationMapper.class);
    private final WelstoryItemLookupService api=mock(WelstoryItemLookupService.class);
    private final PlatformTransactionManager manager=mock(PlatformTransactionManager.class);
    private final ObjectMapper json=new ObjectMapper();
    private final ShortageProcurementService shortages=mock(ShortageProcurementService.class);
    private OrderWorkflowService service;
    private ObjectNode payload;
    @BeforeEach void setup() throws Exception {
        when(manager.getTransaction(any())).thenAnswer(i->new SimpleTransactionStatus());
        when(mapper.order(anyMap())).thenReturn(null);
        service=new OrderWorkflowService(mapper,catalog,api,json,manager,shortages);
        payload=json.createObjectNode();
        String day=LocalDate.now().plusDays(2).format(DateTimeFormatter.BASIC_ISO_DATE);
        payload.putObject("order").put("account_id","ACCOUNT").put("sold_to","A0000001").put("request_key","12345678-1234-1234-1234-123456789000").put("req_delivery_date",day);
        payload.putArray("items").addObject().put("welstory_item_code","1000000001").put("order_qty",2);
        Map<String,Object> offer=new HashMap<>();
        offer.put("lead_time_days",1);offer.put("cutoff_time","11:00:00");
        offer.put("supplier_id",1L);offer.put("account_ingredient_product_id",1L);offer.put("product_status","ACTIVE");
        offer.put("purchase_price",1000);offer.put("base_qty",1000);offer.put("minimum_order_qty",1);offer.put("order_increment_qty",1);
        offer.put("decimal_order_allowed","N");offer.put("supplier_item_code","1000000001");offer.put("base_unit","g");offer.put("order_unit","EA");
        when(catalog.offers(anyMap())).thenReturn(List.of(offer));
        offer.put("supplier_product_id", 10L);
        offer.put("product_name", "양파 1kg");
        when(mapper.lockAccountProduct(anyMap())).thenReturn(Map.of("account_ingredient_product_id",1L));
        when(api.call(eq("/fdapi/service/payer-realtime-item"),any())).thenReturn(json.readTree("{\"dataBody\":{\"resCd\":\"S0000\",\"data\":{\"price\":\"1000\",\"stopType\":\"\"}}}"));
        when(api.call(eq("/fdapi/service/payer-soldto-order"),any())).thenReturn(json.readTree("{\"dataBody\":{\"resCd\":\"S0000\",\"data\":[{\"clientOrdItem\":\"1\",\"resCd\":\"S0000\"}]}}"));
    }
    @Test void commitsInternalOrderBeforeSendingExternalOrder() {
        assertThat(service.order(payload).get("status")).isEqualTo("ORDERED");
        var sequence=inOrder(mapper,manager,api);
        sequence.verify(mapper).insertOrder(anyMap());sequence.verify(mapper).insertItem(anyMap());
        sequence.verify(mapper).insertExchange(anyMap());sequence.verify(manager).commit(any());
        sequence.verify(api).call(eq("/fdapi/service/payer-soldto-order"),any());
    }
    @Test void missingDeadlineRequiresManualConfirmationBeforeAnyProviderCall() {
        var row=new HashMap<>(catalog.offers(Map.of()).get(0));row.remove("cutoff_time");when(catalog.offers(anyMap())).thenReturn(List.of(row));
        assertThatThrownBy(()->service.order(payload)).hasMessageContaining("확인 필요");verifyNoInteractions(api);
        ((ObjectNode)payload.path("items").get(0)).put("delivery_terms_confirmed",true);
        service.order(payload);
        verify(mapper).insertItem(argThat(item->Boolean.TRUE.equals(item.get("delivery_terms_confirmed"))&&item.get("product_snapshot").toString().contains("delivery_terms_confirmed")));
    }
    @Test void ourhomeUsesItsOwnAdapterAndFiveCharacterSiteWithStableOrderKey() {
        OurhomeOrderAdapter ourhome=mock(OurhomeOrderAdapter.class);service.setOurhome(ourhome);
        ((ObjectNode)payload.path("order")).put("supplier_code","OURHOME").put("sold_to","FU0UP");
        when(ourhome.validate(anyString(),anyString(),anyString(),anyMap(),any())).thenReturn(json.createObjectNode().put("itemCode","1000000001").put("price",1000));
        when(ourhome.request(anyMap(),anyList())).thenAnswer(i->json.createObjectNode().put("extnOrderKey",((Map<?,?>)i.getArgument(0)).get("client_ord").toString()));
        ObjectNode response=json.createObjectNode();response.putObject("dataBody").put("resCd","S0000").putArray("data").addObject().put("clientOrdItem","1").put("resCd","S0000");
        when(ourhome.submit(any(),anyMap(),anyList())).thenReturn(response);
        assertThat(service.order(payload).get("status")).isEqualTo("ORDERED");
        verifyNoInteractions(api);
        verify(catalog).offers(argThat(p->"OURHOME".equals(p.get("supplier_code"))&&"FU0UP".equals(p.get("external_site_code"))));
        verify(mapper).insertOrder(argThat(p->p.get("client_ord").equals(p.get("purchase_order_id"))));
        var sequence=inOrder(mapper,manager,ourhome);sequence.verify(mapper).insertOrder(anyMap());sequence.verify(manager).commit(any());sequence.verify(ourhome).submit(any(),anyMap(),anyList());
    }
    @Test void unlinkedIngredientIsRememberedUntilReceipt() {
        ((ObjectNode)payload.path("items").get(0)).put("source_ingredient_id","ONION");
        when(mapper.lockSourceIngredient(anyMap())).thenReturn(Map.of("base_unit","g"));
        service.order(payload);
        verify(mapper).insertItem(argThat(row -> "ONION".equals(row.get("source_ingredient_id"))
            && "PENDING".equals(row.get("ingredient_link_status"))
            && row.get("product_snapshot").toString().contains("provider_realtime_product")));
    }
    @Test void unrelatedSourceIsRejectedBeforeProviderOrder() {
        ((ObjectNode)payload.path("items").get(0)).put("source_ingredient_id","OTHER_ACCOUNT");
        assertThatThrownBy(() -> service.order(payload)).hasMessageContaining("원본 식자재");
        verify(api,never()).call(eq("/fdapi/service/payer-soldto-order"),any());
    }
    @Test void directNewProductPurchaseDoesNotRequireExistingInventory() {
        var offer=new HashMap<>(catalog.offers(Map.of()).get(0));
        offer.remove("account_ingredient_product_id");
        when(catalog.offers(anyMap())).thenReturn(List.of(offer));
        service.order(payload);
        verify(mapper).ensureAccountProduct(anyMap());
        verify(mapper).insertItem(argThat(row -> "NONE".equals(row.get("ingredient_link_status"))));
        verify(mapper,never()).ensureBalance(anyMap());
    }
    @Test void failedInternalSaveDoesNotSendOrder() {
        when(mapper.insertItem(anyMap())).thenThrow(new IllegalStateException("DB unavailable"));
        assertThatThrownBy(()->service.order(payload)).isInstanceOf(IllegalStateException.class);
        verify(api,never()).call(eq("/fdapi/service/payer-soldto-order"),any());
    }
    @Test void timeoutKeepsAnUnknownOrderForReconciliation() {
        when(api.call(eq("/fdapi/service/payer-soldto-order"),any())).thenThrow(new IllegalStateException("timeout"));
        assertThat(service.order(payload).get("status")).isEqualTo("UNKNOWN");
        verify(mapper).updateOrder(argThat(p->"UNKNOWN".equals(p.get("status"))));
    }
    @Test void repeatedRequestReturnsSavedResultWithoutCallingProvider() {
        Map<String,Object> saved=new HashMap<>();saved.put("account_id","ACCOUNT");saved.put("purchase_order_id","saved");saved.put("status","UNKNOWN");
        saved.put("requested_delivery_date",LocalDate.now().plusDays(2).toString());
        when(mapper.order(anyMap())).thenReturn(saved);
        when(mapper.items(anyMap())).thenReturn(List.of(Map.of("supplier_item_code","1000000001","order_qty",2)));
        assertThat(service.order(payload).get("replayed")).isEqualTo(true);
        verifyNoInteractions(api);
    }
    @Test void missingItemResponseIsNotReportedAsSuccess() throws Exception {
        when(api.call(eq("/fdapi/service/payer-soldto-order"),any())).thenReturn(json.readTree("{\"dataBody\":{\"resCd\":\"S0000\",\"data\":[]}}"));
        assertThat(service.order(payload).get("status")).isEqualTo("UNKNOWN");
    }
    @Test void validatesMinimumIncrementAndDecimalRules() {
        assertThatThrownBy(()->OrderWorkflowService.validateQuantity(new BigDecimal("1.5"),BigDecimal.ONE,new BigDecimal("0.5"),"N")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->OrderWorkflowService.validateQuantity(new BigDecimal("4"),new BigDecimal("3"),new BigDecimal("3"),"Y")).isInstanceOf(IllegalArgumentException.class);
        assertThatCode(()->OrderWorkflowService.validateQuantity(new BigDecimal("6"),new BigDecimal("3"),new BigDecimal("3"),"N")).doesNotThrowAnyException();
    }
    @Test void rechecksShortageUnderSupplierLockBeforeSavingOrSending() {
        payload.withObject("/order").putObject("shortage_criteria").put("source","PLAN");
        doThrow(new IllegalArgumentException("이미 발주된 부족량")).when(shortages).validateSubmission(anyMap(),anyList());
        assertThatThrownBy(()->service.order(payload)).hasMessageContaining("이미 발주된 부족량");
        var sequence=inOrder(mapper,shortages);sequence.verify(mapper).lockSupplier(anyMap());sequence.verify(shortages).validateSubmission(anyMap(),anyList());
        verify(mapper,never()).insertOrder(anyMap());verify(api,never()).call(eq("/fdapi/service/payer-soldto-order"),any());
    }
    @Test void emptyLookupDoesNotEraseKnownOrderFailureOrOriginalExchange() throws Exception {
        Map<String,Object> saved=new HashMap<>(Map.of("account_id","ACCOUNT","purchase_order_id","saved","supplier_code","WELSTORY",
            "status","FAILED","sold_to","A0000001","requested_delivery_date",LocalDate.now().toString(),"client_ord","CLIENT",
            "supplier_result_code","EE207","supplier_result_message","주문이 가능한 일자가 아닙니다."));
        when(mapper.order(anyMap())).thenReturn(saved);
        when(api.call(eq("/fdapi/service/payer-order-list"),any())).thenReturn(json.readTree("{\"dataBody\":{\"resCd\":\"EE120\",\"resMsg\":\"조회 결과가 없습니다.\",\"data\":[]}}"));
        var result=service.reconcile(Map.of("account_id","ACCOUNT","purchase_order_id","saved"));
        assertThat(result.get("status")).isEqualTo("FAILED");
        assertThat(result.get("query_result_code")).isEqualTo("EE120");
        assertThat(saved.get("supplier_result_code")).isEqualTo("EE207");
        verify(mapper,never()).updateOrder(anyMap());verify(mapper,never()).updateItem(anyMap());verify(mapper,never()).finishExchange(anyMap());
        saved.put("status","UNKNOWN");
        assertThat(service.reconcile(Map.of("account_id","ACCOUNT","purchase_order_id","saved")).get("status")).isEqualTo("UNKNOWN");
    }
}

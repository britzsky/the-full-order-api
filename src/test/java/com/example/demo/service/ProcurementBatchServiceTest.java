package com.example.demo.service;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import com.example.demo.mapper.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

class ProcurementBatchServiceTest {
    final ProcurementBatchMapper batches=mock(ProcurementBatchMapper.class);
    final SupplierIntegrationMapper catalog=mock(SupplierIntegrationMapper.class);
    final OrderWorkflowService orders=mock(OrderWorkflowService.class);
    final PlatformTransactionManager manager=mock(PlatformTransactionManager.class);
    final ObjectMapper json=new ObjectMapper();
    ProcurementBatchService service;
    Map<String,Object> saved;
    @BeforeEach void setup(){
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(batches.find(anyMap())).thenAnswer(i->saved);
        when(batches.reserve(anyMap())).thenAnswer(i->{ if(saved==null)saved=new HashMap<>(i.getArgument(0)); return 1; });
        when(catalog.offers(anyMap())).thenAnswer(i->{Map<String,Object> q=i.getArgument(0);String id=q.get("supplier_offer_id").toString();
            return List.of(Map.of("supplier_code",id.equals("1")?"WELSTORY":"OURHOME","supplier_name",id.equals("1")?"웰스토리":"아워홈",
                "external_site_code",id.equals("1")?"A0000001":"FU0UP","supplier_item_code","SAME-CODE"));});
        when(orders.order(any())).thenReturn(Map.of("purchase_order_id","saved-order","status","ORDERED"));
        service=new ProcurementBatchService(batches,catalog,orders,json,manager);
    }
    ObjectNode input(){var p=json.createObjectNode().put("account_id","A").put("request_key","12345678-1234-1234-1234-123456789000");
        var a=p.putArray("items");for(int i=1;i<=2;i++)a.addObject().put("supplier_offer_id",i).put("delivery_date","2099-10-01").put("order_qty",1).put("expected_price",100);return p;}
    @Test void separatesSameProductCodeBySupplierAndDeliveryDate(){
        var p=input();p.withArray("items").addObject().put("supplier_offer_id",2).put("delivery_date","2099-10-02").put("order_qty",1).put("expected_price",100);
        var groups=service.group(p);assertThat(groups).hasSize(3);
        assertThat(groups.stream().map(g->g.path("order").path("request_key").asText()).distinct().count()).isEqualTo(3);
        verify(catalog,times(3)).offers(argThat(q->"A".equals(q.get("account_id"))));
    }
    @Test void persistsGroupManifestBeforeAnyProviderOrderAndReusesIt(){
        var p=input();service.submit(p);clearInvocations(catalog);service.submit(p);
        verifyNoInteractions(catalog);verify(batches,times(1)).reserve(anyMap());
        var sequence=inOrder(batches,orders);sequence.verify(batches).reserve(anyMap());sequence.verify(orders,times(4)).order(any());
    }
    @Test void rejectsChangedBasketWithSameRequestKeyWithoutAnotherOrder(){
        var p=input();service.submit(p);clearInvocations(orders);
        ((ObjectNode)p.path("items").get(0)).put("order_qty",3);
        assertThatThrownBy(()->service.submit(p)).hasMessageContaining("동일 요청키");verifyNoInteractions(orders);
    }
    @Test void oneSupplierFailureDoesNotHideTheOtherResult(){
        when(orders.order(any())).thenAnswer(i->{var p=(ObjectNode)i.getArgument(0);if(p.path("order").path("supplier_code").asText().equals("OURHOME"))throw new IllegalArgumentException("가격 변경");return Map.of("status","ORDERED");});
        var result=json.valueToTree(service.submit(input())).path("orders");
        assertThat(result).hasSize(2);assertThat(result.toString()).contains("VALIDATION_FAILED","ORDERED");
    }
    @Test void duplicateSourceAcrossSuppliersRejectedBeforeAnyOrder(){
        var p=input();for(var row:p.path("items"))((ObjectNode)row).put("source_ingredient_id","ONION");
        assertThatThrownBy(()->service.submit(p)).hasMessageContaining("동일 식자재");verifyNoInteractions(orders);verify(batches,never()).reserve(anyMap());
    }
    @Test void foreignOfferCannotBeOrdered(){doReturn(List.of()).when(catalog).offers(anyMap());assertThatThrownBy(()->service.submit(input())).hasMessageContaining("거래처 상품");verifyNoInteractions(orders);}
}

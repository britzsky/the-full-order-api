package com.example.demo.service;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import com.example.demo.mapper.SupplierIntegrationMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
class OurhomeCatalogSyncServiceTest {
    final OurhomeSiteService api=mock(OurhomeSiteService.class);
    final SupplierIntegrationMapper mapper=mock(SupplierIntegrationMapper.class);
    final PlatformTransactionManager manager=mock(PlatformTransactionManager.class);
    final SupplierSiteService sites=mock(SupplierSiteService.class);
    OurhomeCatalogSyncService service;
    final Map<String,Object> input=Map.of("account_id","A","siteCode","FU0UP","deliveryDate","2099-10-01");
    @BeforeEach void setup(){
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(api.requireSite("FU0UP")).thenReturn(new OurhomeSiteService.Site("FU0UP","사업장","H","","","","","",List.of("2099-10-01")));
        when(mapper.supplierIdByCode(anyMap())).thenReturn(2L); when(mapper.supplierProductId(anyMap())).thenReturn(10L);
        when(sites.supplierId(eq("OURHOME"),any())).thenReturn(2L); when(sites.requireOrAssign(eq("A"),eq("OURHOME"),eq("FU0UP"),any())).thenReturn(7L);
        service=new OurhomeCatalogSyncService(api,mapper,new ObjectMapper(),manager,sites);
    }
    Map<String,String> product(String code,String unit){return Map.of("goodcd",code,"goodnm","상품","odrUnit",unit,"salsUcost","3880","goodStatus","Q","odrupYnDesc","0.5","decOdrupYn","Y");}
    @Test void savesChosenSiteOnlyAndExactDatePriceWithoutInventingPackMass(){
        when(api.catalogPage(any(),anyString(),anyString())).thenReturn(new OurhomeSiteService.ProductResult("","FU0UP","2099-10-01",1,false,"",List.of(product("1","PK"))));
        service.sync(input);
        verify(sites).saveProviderSite(eq(2L),argThat(p->"FU0UP".equals(p.get("external_site_code"))),any());
        verify(sites).requireOrAssign(eq("A"),eq("OURHOME"),eq("FU0UP"),any());
        verify(mapper).upsertOffer(argThat(p->Long.valueOf(7L).equals(p.get("supplier_account_site_id"))&&"Y".equals(p.get("active_yn"))));
        verify(mapper).upsertSupplierProduct(argThat(p->p.get("base_qty").toString().equals("0")&&p.get("ingredient_id")==null));
        verify(mapper).insertOfferPrices(argThat(p->{ var r=((List<Map<String,Object>>)p.get("rows")).get(0); return r.get("effective_from").toString().equals("2099-10-01T00:00")&&r.get("effective_to").toString().equals("2099-10-02T00:00"); }));
        verify(mapper).upsertAvailabilities(anyMap());
        verify(mapper,never()).closeOfferPrice(anyMap());
    }
    static Map<String,String> deadlineRow(String info,String ord,String time){return Map.of("orderdtInfo",info,"ordInfo",ord,"ordTime",time);}
    @Test void deadlineFollowsGuideOrderInfoAndTime(){
        assertThat(OurhomeCatalogSyncService.deadline(deadlineRow("D-1","1","1720"))).isEqualTo(new OurhomeCatalogSyncService.Deadline(1,java.time.LocalTime.of(17,20)));
        assertThat(OurhomeCatalogSyncService.deadline(deadlineRow("D-2 / 15:00","2","1500"))).isEqualTo(new OurhomeCatalogSyncService.Deadline(2,java.time.LocalTime.of(15,0)));
        assertThat(OurhomeCatalogSyncService.deadline(deadlineRow("D-5","3","1500")).days()).as("ordInfo 3=D-3이상, 정확한 일수는 orderdtInfo").isEqualTo(5);
        assertThat(OurhomeCatalogSyncService.deadline(deadlineRow("D-7","7","1500")).days()).isEqualTo(7);
        assertThat(OurhomeCatalogSyncService.deadline(deadlineRow("D-2","1","1630"))).as("일수 불일치").isNull();
        assertThat(OurhomeCatalogSyncService.deadline(deadlineRow("D-2 / 15:00","2","1630"))).as("시각 불일치").isNull();
        assertThat(OurhomeCatalogSyncService.deadline(deadlineRow("","2","1630"))).isNull();
        assertThat(OurhomeCatalogSyncService.deadline(deadlineRow("D-1","1",""))).isNull();
        assertThat(OurhomeCatalogSyncService.deadline(deadlineRow("D-1","1","2575"))).isNull();
    }
    @Test void syncStoresConfirmedDeadlineOnOffer(){
        var row=new HashMap<>(product("1","PK"));row.putAll(deadlineRow("D-2","2","1630"));
        when(api.catalogPage(any(),anyString(),anyString())).thenReturn(new OurhomeSiteService.ProductResult("","FU0UP","2099-10-01",1,false,"",List.of(row)));
        service.sync(input);
        verify(mapper).upsertOffer(argThat(p->Integer.valueOf(2).equals(p.get("lead_time_days"))&&"16:30".equals(p.get("cutoff_time"))&&"1630".equals(p.get("cutoff_code"))));
    }
    @Test void incompletePaginationDoesNotWriteAnything(){
        when(api.catalogPage(any(),anyString(),eq(""))).thenReturn(new OurhomeSiteService.ProductResult("","FU0UP","2099-10-01",2,true,"NEXT",List.of(product("1","KG"))));
        when(api.catalogPage(any(),anyString(),eq("NEXT"))).thenThrow(new IllegalStateException("timeout"));
        assertThatThrownBy(()->service.sync(input)).hasMessage("timeout");verifyNoInteractions(mapper,sites);
    }
    @Test void confirmedGramsAreStoredWithoutMatchingSpecificationAndKgPriceDoesNotReplaceOrderPrice(){
        var row = new HashMap<>(product("1","PK"));
        row.put("odrConvqty","1200"); row.put("goodsz","1L"); row.put("salsUcostKg","3233.33");
        when(api.catalogPage(any(),anyString(),anyString())).thenReturn(new OurhomeSiteService.ProductResult("","FU0UP","2099-10-01",1,false,"",List.of(row)));
        service.sync(input);
        verify(mapper).upsertSupplierProduct(argThat(p -> "g".equals(p.get("base_unit")) && new java.math.BigDecimal(p.get("base_qty").toString()).compareTo(new java.math.BigDecimal("1200"))==0));
        verify(mapper).insertOfferPrices(argThat(p -> new java.math.BigDecimal(((List<Map<String,Object>>)p.get("rows")).get(0).get("purchase_price").toString()).compareTo(new java.math.BigDecimal("3880"))==0));
        verify(mapper).refreshUnusedOurhomePack(anyMap());
        verify(mapper).preserveOurhomeMassOption(anyMap());
    }
    @Test void repeatedCursorAndWrongCountDoNotWriteAnything(){
        when(api.catalogPage(any(),anyString(),anyString())).thenReturn(new OurhomeSiteService.ProductResult("","FU0UP","2099-10-01",2,false,"",List.of(product("1","KG"))));
        assertThatThrownBy(()->service.sync(input)).hasMessageContaining("건수");verifyNoInteractions(mapper,sites);
    }
    @SuppressWarnings("unchecked")
    @Test void unchangedProductsSkipProductAndOfferWritesButStoreDailyPriceInBatches(){
        var row=new HashMap<>(product("1","PK"));row.putAll(deadlineRow("D-2","2","1630"));
        var stored=new HashMap<String,String>(row);stored.put("salsUcost","9999");stored.put("goodStatus","X");   // 납품일 값만 다름
        var previous=new HashMap<String,Object>(Map.of("supplier_item_code","1","supplier_product_id",10L,"supplier_offer_id",77L,"lead_time_days",2,"cutoff_time","16:30:00"));
        previous.put("provider_attributes",new ObjectMapper().valueToTree(stored).toString());
        when(mapper.ourhomeSiteOffers(anyMap())).thenReturn(List.of(previous));
        when(api.catalogPage(any(),anyString(),anyString())).thenReturn(new OurhomeSiteService.ProductResult("","FU0UP","2099-10-01",1,false,"",List.of(row)));
        var result=service.sync(input);
        assertThat(result).containsEntry("changed",0);
        verify(mapper,never()).upsertSupplierProduct(anyMap());verify(mapper,never()).upsertOffer(anyMap());verify(mapper,never()).refreshAccountProductLead(anyMap());
        verify(mapper).touchOffers(argThat(p->((List<Object>)p.get("offer_ids")).equals(List.of(77L))));
        verify(mapper).insertOfferPrices(argThat(p->{ var r=((List<Map<String,Object>>)p.get("rows")).get(0); return Long.valueOf(77L).equals(r.get("supplier_offer_id")) && !r.get("price_provider_attributes").toString().contains("goodnm"); }));
    }
    @Test void deadlineChangeForcesFullWriteEvenIfRawRowIsSame(){
        var row=new HashMap<>(product("1","PK"));row.putAll(deadlineRow("D-2","2","1630"));
        var previous=new HashMap<String,Object>(Map.of("supplier_item_code","1","supplier_product_id",10L,"supplier_offer_id",77L,"lead_time_days",0));
        previous.put("provider_attributes",new ObjectMapper().valueToTree(row).toString());   // 예전 동기화: 마감 미반영(0일)
        when(mapper.ourhomeSiteOffers(anyMap())).thenReturn(List.of(previous));
        when(api.catalogPage(any(),anyString(),anyString())).thenReturn(new OurhomeSiteService.ProductResult("","FU0UP","2099-10-01",1,false,"",List.of(row)));
        assertThat(service.sync(input)).containsEntry("changed",1);
        verify(mapper).upsertOffer(argThat(p->Integer.valueOf(2).equals(p.get("lead_time_days"))));
    }
    @Test void newlyParsedVolumePackForcesProductRewriteEvenIfRawRowIsSame(){
        // 예전 규칙으로 저장된 주스(보조 규격 없음) → 원문이 같아도 ml 규격을 채우려 다시 저장한다.
        var row=new HashMap<>(product("1","EA"));row.putAll(deadlineRow("D-2","2","1630"));
        row.put("goodsz","EA(1.5ℓ)");row.put("odrConvqty","1500");
        var previous=new HashMap<String,Object>(Map.of("supplier_item_code","1","supplier_product_id",10L,"supplier_offer_id",77L,"lead_time_days",2,"cutoff_time","16:30:00"));
        previous.put("provider_attributes",new ObjectMapper().valueToTree(row).toString());
        when(mapper.ourhomeSiteOffers(anyMap())).thenReturn(List.of(previous));
        when(api.catalogPage(any(),anyString(),anyString())).thenReturn(new OurhomeSiteService.ProductResult("","FU0UP","2099-10-01",1,false,"",List.of(row)));
        assertThat(service.sync(input)).containsEntry("changed",1);
        verify(mapper).upsertSupplierProduct(argThat(p->"ml".equals(p.get("alt_base_unit")) && new java.math.BigDecimal(p.get("alt_base_qty").toString()).compareTo(new java.math.BigDecimal("1500"))==0));
        // 같은 규격이 이미 저장돼 있으면 건너뛴다.
        previous.put("alt_base_qty","1500.000");previous.put("alt_base_unit","ml");
        assertThat(service.sync(input)).containsEntry("changed",0);
    }
}

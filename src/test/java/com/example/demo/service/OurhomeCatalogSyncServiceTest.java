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
        verify(mapper).insertOfferPrice(argThat(p->p.get("effective_from").toString().equals("2099-10-01T00:00")&&p.get("effective_to").toString().equals("2099-10-02T00:00")));
        verify(mapper,never()).closeOfferPrice(anyMap());
    }
    @Test void incompletePaginationDoesNotWriteAnything(){
        when(api.catalogPage(any(),anyString(),eq(""))).thenReturn(new OurhomeSiteService.ProductResult("","FU0UP","2099-10-01",2,true,"NEXT",List.of(product("1","KG"))));
        when(api.catalogPage(any(),anyString(),eq("NEXT"))).thenThrow(new IllegalStateException("timeout"));
        assertThatThrownBy(()->service.sync(input)).hasMessage("timeout");verifyNoInteractions(mapper,sites);
    }
    @Test void repeatedCursorAndWrongCountDoNotWriteAnything(){
        when(api.catalogPage(any(),anyString(),anyString())).thenReturn(new OurhomeSiteService.ProductResult("","FU0UP","2099-10-01",2,false,"",List.of(product("1","KG"))));
        assertThatThrownBy(()->service.sync(input)).hasMessageContaining("건수");verifyNoInteractions(mapper,sites);
    }
}

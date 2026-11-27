package com.example.demo.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.demo.mapper.SupplierIntegrationMapper;

/** 웰스토리가 첫 조회(contYn=Y)에 '조회 데이터가 존재하지 않습니다'를 주면 contYn=N으로 한 번 더 묻고, 그 결과를 받는다. */
class WelstoryPagedRetryTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void fullCatalogRetriesWithContYnNAfterNoDataOnFirstRequest() throws Exception {
        var mapper = mock(SupplierIntegrationMapper.class);
        var api = mock(WelstoryItemLookupService.class);
        when(mapper.supplierIdByCode(anyMap())).thenReturn(1L);
        when(mapper.sites(anyMap())).thenReturn(List.of(Map.of("external_site_code","SITE","supplier_account_site_id",2L,"supplier_site_id",4L)));
        when(mapper.supplierProductId(anyMap())).thenReturn(3L);
        when(api.call(anyString(),any())).thenReturn(
            json.readTree("""
                {"dataHeader":{"totalRow":0,"contYn":"N","nextKey":""},"dataBody":{"resCd":"E1103","resMsg":"조회 데이터가 존재하지 않습니다.","data":[]}}
                """),
            json.readTree("""
                {"dataHeader":{"contYn":"N","nextKey":""},"dataBody":{"resCd":"S0000","data":[
                {"itemCode":"T1","itemName":"상품1","unit":"EA","price":"1000"},
                {"itemCode":"T2","itemName":"상품2","unit":"EA","price":"2000"}]}}
                """));
        var service = new WelstoryCatalogSyncService(mapper,api,json,mock(ReceiptPostingService.class),mock(SupplierSiteService.class));

        var result = service.syncCatalog(Map.of("account_id","A","sold_to","SITE","period_group_year","2026","period_group","16"));

        assertThat(result).containsEntry("received_count",2).containsEntry("saved_count",2);
        var requests = ArgumentCaptor.forClass(JsonNode.class);
        verify(api,times(2)).call(eq("/fdapi/service/payer-allitem-price"),requests.capture());
        assertThat(requests.getAllValues()).extracting(r -> r.path("dataHeader").path("contYn").asText()).containsExactly("Y","N");
    }

    @Test void noDataAfterRetryStillEndsWithZeroRowsAndDoesNotLoop() throws Exception {
        var mapper = mock(SupplierIntegrationMapper.class);
        var api = mock(WelstoryItemLookupService.class);
        when(mapper.supplierIdByCode(anyMap())).thenReturn(1L);
        when(mapper.sites(anyMap())).thenReturn(List.of(Map.of("external_site_code","SITE","supplier_account_site_id",2L,"supplier_site_id",4L)));
        var noData = json.readTree("""
            {"dataHeader":{"contYn":"N","nextKey":""},"dataBody":{"resCd":"E1103","resMsg":"조회 데이터가 존재하지 않습니다.","data":[]}}
            """);
        when(api.call(anyString(),any())).thenReturn(noData);
        var service = new WelstoryCatalogSyncService(mapper,api,json,mock(ReceiptPostingService.class),mock(SupplierSiteService.class));

        var result = service.syncCatalog(Map.of("account_id","A","sold_to","SITE","period_group_year","2026","period_group","16"));

        assertThat(result).containsEntry("received_count",0).containsEntry("saved_count",0);
        verify(api,times(2)).call(anyString(),any());
    }
}

package com.example.demo.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.demo.mapper.SupplierIntegrationMapper;

class WelstoryProductImagesTest {
    private final ObjectMapper json = new ObjectMapper();
    @Test void joinsDomainAndKeepsOriginalAndThumbnailSeparate() throws Exception {
        var images = WelstoryProductImages.from(json.readTree("""
            {"imgDomain":"https://img.example.com","imgURLs":[{"img":"/a.jpg","thumb":"/a_thumb.jpg"}]}
            """));
        assertThat(images).containsEntry("image_url","https://img.example.com/a.jpg")
            .containsEntry("thumbnail_url","https://img.example.com/a_thumb.jpg").containsEntry("images_provided",true);
    }
    @Test void skipsInvalidEntriesAndAcceptsAbsoluteOriginalWithoutDomain() throws Exception {
        var images = WelstoryProductImages.from(json.readTree("""
            {"imgURLs":[null,{}, {"img":"javascript:alert(1)"}, {"img":"https://img.example.com/a.jpg"}]}
            """));
        assertThat(images).containsEntry("image_url","https://img.example.com/a.jpg").containsEntry("thumbnail_url",null);
    }
    @Test void missingAndEmptyArraysHaveDifferentUpdateSemantics() throws Exception {
        assertThat(WelstoryProductImages.from(json.readTree("{}"))).containsEntry("images_provided",false);
        assertThat(WelstoryProductImages.from(json.readTree("{\"imgURLs\":[]}")))
            .containsEntry("images_provided",true).containsEntry("image_url",null).containsEntry("thumbnail_url",null);
        assertThat(WelstoryProductImages.from(json.readTree("{\"imgURLs\":null}"))).containsEntry("images_provided",false);
    }
    @Test void thumbnailOnlyAndMalformedDomainsDoNotInventAnApiHost() throws Exception {
        assertThat(WelstoryProductImages.from(json.readTree("""
            {"imgDomain":"https://img.example.com","imgURLs":[{"thumb":"t.jpg"}]}
            """))).containsEntry("image_url",null).containsEntry("thumbnail_url","https://img.example.com/t.jpg");
        assertThat(WelstoryProductImages.from(json.readTree("""
            {"imgDomain":"invalid","imgURLs":[{"img":"/a.jpg"}]}
            """))).containsEntry("image_url",null);
    }
    @Test void fullCatalogSyncPassesImagesToProductPersistence() throws Exception {
        var mapper = mock(SupplierIntegrationMapper.class);
        var api = mock(WelstoryItemLookupService.class);
        when(mapper.supplierIdByCode(anyMap())).thenReturn(1L);
        when(mapper.sites(anyMap())).thenReturn(List.of(Map.of("external_site_code","SITE","supplier_account_site_id",2L)));
        when(mapper.supplierProductId(anyMap())).thenReturn(3L);
        when(api.call(anyString(),any())).thenReturn(json.readTree("""
            {"dataHeader":{"contYn":"N"},"dataBody":{"resCd":"S0000","data":[
            {"itemCode":"TEST","itemName":"상품","unit":"EA","price":"1000",
             "imgDomain":"https://img.example.com","imgURLs":[{"img":"/a.jpg","thumb":"/t.jpg"}]}]}}
            """));
        var service = new WelstoryCatalogSyncService(mapper,api,json,mock(ReceiptPostingService.class),mock(SupplierSiteService.class));
        var result = service.syncCatalog(Map.of("account_id","A","sold_to","SITE","period_group_year","2026","period_group","09"));
        assertThat(result).containsEntry("saved_count",1);
        verify(mapper).upsertSupplierProduct(argThat(row -> "https://img.example.com/a.jpg".equals(row.get("image_url"))
            && "https://img.example.com/t.jpg".equals(row.get("thumbnail_url"))));
    }
}

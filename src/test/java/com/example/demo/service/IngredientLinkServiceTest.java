package com.example.demo.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.anyMap;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;
import com.example.demo.mapper.IngredientLinkMapper;
import com.example.demo.mapper.SupplierCatalogMapper;

class IngredientLinkServiceTest {
    final IngredientLinkMapper links=mock(IngredientLinkMapper.class);
    final SupplierCatalogMapper catalog=mock(SupplierCatalogMapper.class);
    final IngredientLinkService service=new IngredientLinkService(links,catalog);
    Map<String,Object> body(String action) {
        var b=new HashMap<String,Object>();
        b.put("action",action);b.put("account_id","A");b.put("ingredient_id","I");b.put("account_ingredient_product_id",10L);
        b.put("supplier_product_id",2L);b.put("supplier_offer_id",20L);b.put("expected_ingredient_id","");
        b.put("base_qty",1);b.put("base_unit","kg");b.put("order_unit","봉");b.put("user_id","TEST");return b;
    }
    void setup() {
        when(links.lockAccountIngredientUnit(anyMap())).thenReturn("g");
        when(links.lockLink(anyMap())).thenReturn(Map.of("account_ingredient_product_id",10L,"supplier_product_id",1L,"mapped_ingredient_id","I","active_yn","Y","preferred_yn","Y"));
        when(links.offerProduct(anyMap())).thenReturn(2L);
        when(links.lockProductLink(anyMap())).thenReturn(null);
        when(catalog.productForUpdate(anyMap())).thenAnswer(call -> {
            Map<String,Object> p=call.getArgument(0);
            return p.get("supplier_product_id").toString().equals("1") ? Map.of("ingredient_id","I") : Map.of("ingredient_id","W2","active_yn","Y","base_qty",1,"base_unit","kg","order_unit","봉");
        });
    }
    @Test void newLinkBecomesPreferredForCurrentAccount() {
        setup();service.change(body("LINK"));
        var order=inOrder(catalog,links);
        order.verify(links).clearPreferred(argThat(p -> p.get("account_id").equals("A") && p.get("ingredient_id").equals("I")));
        order.verify(catalog).insertAccountProduct(argThat(p -> p.get("account_id").equals("A") && p.get("preferred_yn").equals("Y")));
    }
    @Test void replacingNonPreferredLinkSelectsNewProductAsPreferred() {
        setup();
        when(links.lockLink(anyMap())).thenReturn(Map.of("account_ingredient_product_id",10L,"supplier_product_id",1L,"mapped_ingredient_id","I","active_yn","Y","preferred_yn","N"));
        service.change(body("REPLACE"));
        verify(links).clearPreferred(anyMap());
        verify(catalog).insertAccountProduct(argThat(p -> p.get("preferred_yn").equals("Y")));
    }
    @Test void unlinkOnlyDisablesCurrentAccountWithoutDeletingHistory() {
        setup();service.change(body("UNLINK"));
        verify(catalog).updateAccountProduct(argThat(p -> p.get("account_id").equals("A") && p.get("account_ingredient_product_id").equals(10L) && p.get("active_yn").equals("N") && !p.containsKey("supplier_product_id")));
        verify(catalog,never()).updateProduct(anyMap());verify(catalog,never()).deleteAccountProduct(anyMap());
    }
    @Test void replacementActivatesNewLinkBeforeDisablingOldAndPreservesSettings() {
        setup();when(links.lockProductLink(anyMap())).thenReturn(Map.of("account_ingredient_product_id",11L));
        service.change(body("REPLACE"));
        var order=inOrder(catalog,links);
        verify(catalog,never()).updateProduct(anyMap());
        order.verify(links).clearPreferred(anyMap());
        order.verify(catalog).updateAccountProduct(argThat(p -> p.get("account_ingredient_product_id").equals(11L) && p.get("active_yn").equals("Y") && p.get("preferred_yn").equals("Y") && !p.containsKey("safe_stock_base_qty")));
        order.verify(catalog).updateAccountProduct(argThat(p -> p.get("account_ingredient_product_id").equals(10L) && p.get("active_yn").equals("N")));
    }
    // 계란 30EA,1560G이상/PAC: 대표 1560g, 두 번째 30EA
    Map<String,Object> eggBody(String qty,String unit) {
        var b=body("LINK");b.put("base_qty",qty);b.put("base_unit",unit);b.put("order_unit","PAC");return b;
    }
    void eggProduct(String ingredientUnit) {
        setup();when(links.lockAccountIngredientUnit(anyMap())).thenReturn(ingredientUnit);
        doReturn(Map.of("active_yn","Y","order_unit","PAC","base_qty",1560,"base_unit","g","alt_base_qty",30,"alt_base_unit","EA")).when(catalog).productForUpdate(anyMap());
    }
    @Test void eggLinkedToEaIngredientUsesCountPack() {
        eggProduct("EA");service.change(eggBody("30","EA"));
        verify(links).setIngredient(argThat(p -> p.get("link_base_unit").equals("EA") && new java.math.BigDecimal(p.get("link_base_qty").toString()).compareTo(new java.math.BigDecimal("30"))==0));
    }
    @Test void eggLinkedToGramIngredientUsesWeightPack() {
        eggProduct("g");service.change(eggBody("1560","g"));
        verify(links).setIngredient(argThat(p -> p.get("link_base_unit").equals("g")));
    }
    @Test void packShownByStaleClientIsRejected() {
        eggProduct("EA");
        assertThatThrownBy(() -> service.change(eggBody("1560","g"))).hasMessageContaining("규격이 변경");
        verify(links,never()).setIngredient(anyMap());
    }
    @Test void unitChangeWithRemainingStockIsRejected() {
        eggProduct("EA");when(links.stockUnits(anyMap())).thenReturn(java.util.List.of("g"));
        assertThatThrownBy(() -> service.change(eggBody("30","EA"))).hasMessageContaining("기존 재고");
        verify(links,never()).setIngredient(anyMap());
    }
    @Test void failedTargetInsertDoesNotDeactivateOldAndUsesTransaction() throws Exception {
        setup();doThrow(new IllegalStateException("insert failed")).when(catalog).insertAccountProduct(anyMap());
        assertThatThrownBy(() -> service.change(body("REPLACE"))).isInstanceOf(IllegalStateException.class);
        verify(catalog,never()).updateAccountProduct(anyMap());
        assertThat(IngredientLinkService.class.getMethod("change",Map.class).isAnnotationPresent(Transactional.class)).isTrue();
    }
    @Test void wrongAccountLinkAndWrongOfferAreRejectedBeforeWrites() {
        setup();when(links.lockLink(anyMap())).thenReturn(null);
        assertThatThrownBy(() -> service.change(body("UNLINK"))).isInstanceOf(IllegalArgumentException.class);
        when(links.offerProduct(anyMap())).thenReturn(null);
        assertThatThrownBy(() -> service.change(body("LINK"))).isInstanceOf(IllegalArgumentException.class);
        verify(catalog,never()).updateProduct(anyMap());verify(catalog,never()).updateAccountProduct(anyMap());
    }
    @Test void unitMismatchAndStaleMappingAreRejected() {
        setup();when(links.lockAccountIngredientUnit(anyMap())).thenReturn("ml");
        assertThatThrownBy(() -> service.change(body("LINK"))).isInstanceOf(IllegalArgumentException.class);
        when(links.lockAccountIngredientUnit(anyMap())).thenReturn("g");var stale=body("LINK");stale.put("expected_ingredient_id","OTHER");
        assertThatThrownBy(() -> service.change(stale)).isInstanceOf(IllegalArgumentException.class);
        verify(catalog,never()).updateProduct(anyMap());
    }
    @Test void headquartersClassificationDoesNotBlockAccountLink() {
        // 상품의 본사 식자재 분류는 W2지만 이 거래처에서는 미연결 → 거래처 식재료 I에 연결 가능
        setup();service.change(body("LINK"));
        verify(links).setIngredient(argThat(p -> p.get("ingredient_id").equals("I")));
    }
    @Test void inactiveOldAccountLinkDoesNotBlock() {
        setup();when(links.lockProductLink(anyMap())).thenReturn(Map.of("account_ingredient_product_id",11L,"active_yn","N","mapped_ingredient_id","Z"));
        service.change(body("LINK"));
        verify(links).setIngredient(argThat(p -> p.get("ingredient_id").equals("I")));
    }
    @Test void syncCreatedRowWithoutAccountMappingCountsAsUnlinked() {
        // 웰스토리 동기화가 만든 active 행(mapped 없음)은 본사 분류 W2를 따라가지 않고 미연결로 본다
        setup();when(links.lockProductLink(anyMap())).thenReturn(Map.of("account_ingredient_product_id",11L,"active_yn","Y"));
        service.change(body("LINK"));
        verify(links).setIngredient(argThat(p -> p.get("ingredient_id").equals("I")));
    }
    @Test void unlinkRejectsRowLinkedOnlyByHeadquartersClassification() {
        setup();when(links.lockLink(anyMap())).thenReturn(Map.of("account_ingredient_product_id",10L,"supplier_product_id",1L,"active_yn","Y"));
        assertThatThrownBy(() -> service.change(body("UNLINK"))).isInstanceOf(IllegalArgumentException.class);
        verify(catalog,never()).updateAccountProduct(anyMap());
    }
    @Test void concurrentActiveLinkInSameAccountIsRejected() {
        // 조회 때는 미연결이었는데 그사이 이 거래처에서 다른 식재료 Z에 연결됨 → 다시 조회 요구
        setup();when(links.lockProductLink(anyMap())).thenReturn(Map.of("account_ingredient_product_id",11L,"active_yn","Y","mapped_ingredient_id","Z"));
        assertThatThrownBy(() -> service.change(body("LINK"))).isInstanceOf(IllegalArgumentException.class);
        verify(links,never()).setIngredient(anyMap());
    }
    @Test void sameProductReplacementIsRejected() {
        setup();var b=body("REPLACE");b.put("supplier_product_id",1L);when(links.offerProduct(anyMap())).thenReturn(1L);
        assertThatThrownBy(() -> service.change(b)).isInstanceOf(IllegalArgumentException.class);
        verify(catalog,never()).updateAccountProduct(anyMap());
    }
    @Test void mappingOnlyChangesSelectedAccountAndCreatesEmptyInventory() {
        setup();service.change(body("LINK"));
        verify(links).setIngredient(argThat(p -> p.get("account_id").equals("A") && p.get("ingredient_id").equals("I") && p.get("supplier_product_id").equals(2L)));
        verify(links).ensureInventory(anyMap());
        verify(catalog,never()).updateProduct(anyMap());
    }
    @Test void unlinkUsesAccountMappingInsteadOfGlobalCatalogIngredient() {
        setup();
        when(links.lockLink(anyMap())).thenReturn(Map.of("account_ingredient_product_id",10L,"supplier_product_id",2L,"mapped_ingredient_id","I"));
        service.change(body("UNLINK"));
        verify(catalog).updateAccountProduct(argThat(p -> p.get("active_yn").equals("N")));
        verify(catalog,never()).updateProduct(anyMap());
    }
    @Test void catalogSyncPreservesManualDeactivation() throws Exception {
        try(var stream=getClass().getResourceAsStream("/mybatis-mapper/SupplierIntegrationMapper.xml")) {
            String xml=new String(stream.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            String block=xml.substring(xml.indexOf("<insert id=\"upsertAccountProduct\""));
            block=block.substring(0,block.indexOf("</insert>"));
            assertThat(block.substring(block.indexOf("ON DUPLICATE KEY UPDATE"))).doesNotContain("active_yn");
        }
    }
}

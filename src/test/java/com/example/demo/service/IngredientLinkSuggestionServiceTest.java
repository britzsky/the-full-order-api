package com.example.demo.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** 연결 추천: 다른 거래처 사용 > 발주 이력 > 이름, 환산 불가·이미 연결 제외, 다른 식재료 연결 경고 */
class IngredientLinkSuggestionServiceTest {
    final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    final SupplierIntegrationService suppliers=mock(SupplierIntegrationService.class);
    final IngredientLinkSuggestionService service=new IngredientLinkSuggestionService(jdbc,suppliers);

    static Map<String,Object> offer(long id,String name,Object qty,String unit,String linked) {
        var o=new HashMap<String,Object>(Map.of("supplier_offer_id",id,"supplier_product_id",id,"supplier_code","OURHOME","supplier_name","아워홈",
            "product_name",name,"supplier_item_code","C"+id,"product_base_qty",qty,"product_base_unit",unit,"purchase_price",10000,"product_status","ACTIVE"));
        o.put("orderable_yn","Y");o.put("linked_ingredient_id",linked);o.put("linked_ingredient_name",linked==null?null:"다른재료");
        return o;
    }
    void data(List<Map<String,Object>> offers,List<Map<String,Object>> others,List<Map<String,Object>> orders) {
        org.mockito.stubbing.Answer<List<Map<String,Object>>> answer=i->{
            String sql=i.getArgument(0);
            if(sql.contains("COUNT(DISTINCT rd.menu_id)")) return List.of(
                Map.of("ingredient_id","PORK","name","돼지고기","base_unit","g","category_name","육류","menu_usage_count",3),
                Map.of("ingredient_id","SALT","name","히말라야솔트","base_unit","g","category_name","조미료","menu_usage_count",1));
            if(sql.contains("tb_account_ingredient_master")) return List.of(Map.of("base_unit","g","name","돼지고기"));
            if(sql.contains("COUNT(DISTINCT account_id)")) return others;
            if(sql.contains("tb_account_purchase_order_item")) return orders;
            return List.of();
        };
        when(jdbc.queryForList(anyString(),(Object)any())).thenAnswer(answer);
        when(jdbc.queryForList(anyString(),(Object)any(),(Object)any())).thenAnswer(answer);
        when(suppliers.offers(anyMap())).thenReturn(offers);
    }
    @SuppressWarnings("unchecked")
    List<Map<String,Object>> run() { return (List<Map<String,Object>>)service.suggestions(new HashMap<>(Map.of("account_id","A","ingredient_id","PORK","delivery_date","2026-10-08"))).get("suggestions"); }

    @Test void ranksByUsageThenNameAndExcludesUnusable() {
        data(List.of(
                offer(1,"돈육 앞다리 1kg",1000,"g",null),
                offer(2,"돼지고기 목살 1kg",1000,"g",null),
                offer(3,"한돈 삼겹 (다른 거래처 사용)",1000,"g",null),
                offer(4,"돼지고기 10EA",10,"EA",null),           // g으로 환산 불가 → 제외
                offer(5,"돼지고기 기존연결",1000,"g","PORK"),      // 이미 이 식재료에 연결 → 제외
                offer(6,"양파 1kg",1000,"g",null),                 // 근거 없음 → 제외
                offer(7,"돼지고기 다짐육",1000,"g","BEEF")),       // 다른 식재료에 연결 → 경고
            List.of(Map.of("mapped_ingredient_id","PORK","supplier_product_id",3,"n",3)), List.of(Map.of("ingredient_id","PORK","supplier_product_id",1,"n",2)));
        var rows=run();
        // 이 거래처 발주 이력 30 + 동의어 25 = 55 > 다른 거래처 3곳 50 > 이름 포함 30 > 이름 30 - 다른 식재료 연결 10
        assertThat(rows).extracting(r->r.get("supplier_offer_id")).containsExactly(1L,3L,2L,7L);
        assertThat(rows.get(0).get("reasons").toString()).contains("발주 이력 2건").contains("돈육");
        assertThat(rows.get(1).get("reasons").toString()).contains("다른 거래처 3곳");
        assertThat(rows.get(3).get("warning").toString()).contains("다른재료");
        assertThat(rows.get(0)).containsEntry("base_unit","g");
    }
    @SuppressWarnings("unchecked")
    @Test void bulkReturnsEveryUnlinkedIngredientWithTopThree() {
        data(List.of(offer(1,"돈육 앞다리 1kg",1000,"g",null),offer(2,"돼지고기 목살 1kg",1000,"g",null),offer(3,"돼지 등뼈",1000,"g",null),offer(4,"돼지고기 다짐",1000,"g",null)),
            List.of(Map.of("mapped_ingredient_id","BEEF","supplier_product_id",2,"n",9)), List.of());
        var rows=(List<Map<String,Object>>)service.unlinked(new HashMap<>(Map.of("account_id","A"))).get("ingredients");
        assertThat(rows).extracting(r->r.get("ingredient_id")).containsExactly("PORK","SALT");
        var pork=(List<Map<String,Object>>)rows.get(0).get("suggestions");
        assertThat(pork).hasSize(3);
        assertThat(pork.toString()).as("다른 식재료(BEEF)에 쓰인 근거는 돼지고기 점수에 섞이지 않음").doesNotContain("다른 거래처");
        assertThat((List<?>)rows.get(1).get("suggestions")).as("근거 없는 식재료는 빈 추천 → 직접 검색").isEmpty();
        verify(suppliers,times(1)).offers(anyMap());
    }
    @Test void requiresAccountAndKnownIngredient() {
        assertThatThrownBy(()->service.suggestions(new HashMap<>(Map.of("account_id","A")))).hasMessageContaining("ingredient_id");
        when(jdbc.queryForList(anyString(),(Object)any(),(Object)any())).thenReturn(List.of());
        assertThatThrownBy(()->service.unlinked(new HashMap<>())).hasMessageContaining("account_id");
        assertThatThrownBy(()->service.suggestions(new HashMap<>(Map.of("account_id","A","ingredient_id","X")))).hasMessageContaining("찾을 수 없습니다");
    }
}

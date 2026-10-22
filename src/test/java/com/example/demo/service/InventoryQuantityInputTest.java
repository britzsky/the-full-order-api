package com.example.demo.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.example.demo.mapper.InventoryMapper;

/** 식재료 재고 조정 화면의 현재고 입력 단위 환산(재고는 식재료 단위라 상품 포장 단위로는 받지 않는다) */
class InventoryQuantityInputTest {
    final InventoryService service=new InventoryService(mock(InventoryMapper.class),mock(StockAvailabilityService.class));

    Map<String,Object> input(Object qty,String unit,String base) {
        var b=new HashMap<String,Object>();b.put("current_qty",qty);b.put("current_unit",unit);b.put("base_unit",base);return b;
    }
    @Test void weightUnitsConvertAndKeepDisplayUnit() {
        var b=input("1.5","kg","g");service.normalizeCurrentQuantity(b);
        assertThat((BigDecimal)b.get("current_base_qty")).isEqualByComparingTo("1500");
        assertThat(b.get("current_unit")).isEqualTo("kg");
        var ml=input(2,"L","ml");service.normalizeCurrentQuantity(ml);
        assertThat((BigDecimal)ml.get("current_base_qty")).isEqualByComparingTo("2000");
    }
    @Test void productPackUnitIsNotAcceptedAndAllowedUnitsAreListed() {
        assertThatThrownBy(()->service.normalizeCurrentQuantity(input(2,"PAC","EA")))
            .hasMessageContaining("'PAC' 단위는").hasMessageContaining("입력 가능한 단위: EA");
        assertThatThrownBy(()->service.normalizeCurrentQuantity(input(2,"BOX","g")))
            .hasMessageContaining("입력 가능한 단위: g, kg");
    }
    @Test void blankUnitMeansBaseUnit() {
        var b=input(5,"","EA");service.normalizeCurrentQuantity(b);
        assertThat((BigDecimal)b.get("current_base_qty")).isEqualByComparingTo("5");
    }
    @Test void negativeStockIsRejected() {
        assertThatThrownBy(()->service.normalizeCurrentQuantity(input(-1,"g","g"))).hasMessageContaining("음수");
    }
}

package com.example.demo.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.example.demo.mapper.InventoryMapper;

/** 상품별 재고 조정 화면의 현재고 입력 단위 환산 */
class InventoryQuantityInputTest {
    final InventoryService service=new InventoryService(mock(InventoryMapper.class),mock(StockAvailabilityService.class));
    // 계란: 발주단위 PAC, 이 거래처 연결 규격 1 PAC = 30 EA
    final Map<String,Object> eggPack=Map.of("order_unit","PAC","pack_qty",30,"pack_unit","EA");

    Map<String,Object> input(Object qty,String unit,String base) {
        var b=new HashMap<String,Object>();b.put("current_qty",qty);b.put("current_unit",unit);b.put("base_unit",base);return b;
    }
    @Test void orderUnitIsConvertedWithLinkPackAndShownInBaseUnit() {
        var b=input(2,"PAC","EA");service.normalizeCurrentQuantity(b,eggPack);
        assertThat((BigDecimal)b.get("current_base_qty")).isEqualByComparingTo("60");
        assertThat(b.get("current_unit")).isEqualTo("EA");
        var alias=input(1,"팩","EA");service.normalizeCurrentQuantity(alias,eggPack);
        assertThat((BigDecimal)alias.get("current_base_qty")).isEqualByComparingTo("30");
    }
    @Test void weightUnitsStillConvertAndKeepDisplayUnit() {
        var b=input("1.5","kg","g");service.normalizeCurrentQuantity(b,Map.of("order_unit","BOX","pack_qty",10000,"pack_unit","g"));
        assertThat((BigDecimal)b.get("current_base_qty")).isEqualByComparingTo("1500");
        assertThat(b.get("current_unit")).isEqualTo("kg");
        var box=input(2,"BOX","g");service.normalizeCurrentQuantity(box,Map.of("order_unit","BOX","pack_qty",10000,"pack_unit","g"));
        assertThat((BigDecimal)box.get("current_base_qty")).isEqualByComparingTo("20000");
    }
    @Test void unconvertibleUnitExplainsAllowedUnits() {
        assertThatThrownBy(()->service.normalizeCurrentQuantity(input(2,"판","EA"),eggPack))
            .hasMessageContaining("'판' 단위는").hasMessageContaining("EA, PAC");
        // 규격이 없는 상품(0)은 발주단위로 환산하지 않는다
        assertThatThrownBy(()->service.normalizeCurrentQuantity(input(2,"PAC","EA"),Map.of("order_unit","PAC","pack_qty",0,"pack_unit","PAC")))
            .hasMessageContaining("입력 가능한 단위: EA");
    }
    @Test void blankUnitMeansBaseUnit() {
        var b=input(5,"","EA");service.normalizeCurrentQuantity(b,eggPack);
        assertThat((BigDecimal)b.get("current_base_qty")).isEqualByComparingTo("5");
    }
}

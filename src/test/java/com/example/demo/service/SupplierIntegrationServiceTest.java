package com.example.demo.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.anyMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.example.demo.mapper.SupplierIntegrationMapper;

class SupplierIntegrationServiceTest {
    private Map<String,Object> offer() {
        return new HashMap<>(Map.of("product_status","ACTIVE","account_ingredient_product_id",318L,
                "purchase_price",105,"base_qty",20000));
    }
    private Map<String,Object> evaluate(Map<String,Object> row) {
        var mapper=mock(SupplierIntegrationMapper.class);
        when(mapper.offers(anyMap())).thenReturn(List.of(row));
        return new SupplierIntegrationService(mapper,mock(SupplierSiteService.class)).offers(new HashMap<>(Map.of("account_id","TEST"))).get(0);
    }
    @Test void qualityStopExplainsRiceBlockWithoutClaimingMissingUnitsOrPrice() {
        var row=offer();row.put("product_status","STOPPED");row.put("status_reason","QA STOP");
        var result=evaluate(row);
        assertThat(result.get("orderable_yn")).isEqualTo("N");
        assertThat(result.get("availability_reason")).isEqualTo("품질 이슈로 공급 중단(QA STOP)");
    }
    @Test void independentMissingConditionsAreReportedTogether() {
        var row=offer();row.remove("account_ingredient_product_id");row.put("purchase_price",0);row.put("base_qty",0);
        assertThat(evaluate(row).get("availability_reason").toString())
                .contains("공급사 가격 없음","거래처 공급상품 연결 없음","포장 기준용량 확인 필요");
    }
    @Test void providerAvailabilityReasonIsPreservedAndActiveProductsRemainOrderable() {
        var blocked=offer();blocked.put("orderable_yn","N");blocked.put("availability_reason","해당 납품일 배송 불가");
        assertThat(evaluate(blocked).get("availability_reason")).isEqualTo("해당 납품일 배송 불가");
        assertThat(evaluate(offer()).get("orderable_yn")).isEqualTo("Y");
    }
}

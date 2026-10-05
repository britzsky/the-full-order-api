package com.example.demo.service;
import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
class ProductOriginsTest {
    @Test void ingredientOriginsHavePriorityOverProductOrigin() {
        assertThat(ProductOrigins.describe("{\"origin\":\"South Korea\",\"rawMaterialOrigin1\":\"돼지고기: 국내산\",\"rawMaterialOrigin2\":\"고춧가루: 중국산\"}"))
            .isEqualTo("돼지고기: 국내산 / 고춧가루: 중국산");
    }
    @Test void missingOrMalformedDataIsNotAssumedDomestic() {
        assertThat(ProductOrigins.describe(null)).isEqualTo("원산지 미확인");
        assertThat(ProductOrigins.describe("bad")).isEqualTo("원산지 미확인");
        assertThat(ProductOrigins.describe("{}" )).isEqualTo("원산지 미확인");
    }
    @Test void ourhomeOriginNameIsSupported() {
        assertThat(ProductOrigins.describe("{\"originPlaceNm\":\"국내산\"}" )).isEqualTo("국내산");
    }
}

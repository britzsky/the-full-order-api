package com.example.demo.service;

import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class QuantityUnitsTest {
    @Test void explicitPackWeightAllowsPerPieceDescriptionWithoutMultiplyingApproximateCounts() {
        for (String spec : new String[]{
                "알찬손만두,DC,후레시스,1.3KG(28G*약46EA)/PAC",
                "1.3KG(28G×약46EA)/PAC",
                "1.3KG (28G * 약 46EA) / PAC"}) {
            var packs = QuantityUnits.packs("PAC", spec);
            assertThat(packs).hasSize(1);
            assertThat(packs.get(0).baseUnit()).isEqualTo("g");
            assertThat(packs.get(0).quantity()).isEqualByComparingTo("1300");
            assertThat(QuantityUnits.packFor(packs, "g")).isNotNull();
            assertThat(QuantityUnits.packFor(packs, "EA")).isNull();
        }
        assertThat(QuantityUnits.pack("BOX", "1L(100ML*10EA)/BOX").quantity()).isEqualByComparingTo("1000");
        for (String spec : new String[]{"28G*약46EA/PAC", "1.3KG(28G*약46EA)/BOX",
                "1.3KG(28ML*약46EA)/PAC", "1.3KG(28G*약46EA)/PAC,2KG/PAC"}) {
            assertThat(QuantityUnits.packs("PAC", spec)).isEmpty();
        }
    }
    @Test void explicitPackContentsProduceCorrectRecipeFractions() {
        for(String[] test:new String[][]{{"PAC","우강청결미,20KG/PAC,직송","300","0.015"},{"BOX","강동,10KG/BOX","100","0.01"},{"PAC","1KG/PAC,5MM슬라이스","50","0.05"}}) {
            var pack=QuantityUnits.pack(test[0],test[1]);
            assertThat(pack.baseUnit()).isEqualTo("g");
            assertThat(new BigDecimal(test[2]).divide(pack.quantity())).isEqualByComparingTo(test[3]);
        }
    }
    @Test void ambiguousSpecificationsNeverBecomeWeightConversions() {
        for(String spec:new String[]{"375~484G/EA","1KG(100ML*10EA)/BOX","","100G*10EA/BOX","100G/BOX,200G/BOX"})
            assertThat(QuantityUnits.pack("BOX",spec).quantity()).isZero();
        assertThat(QuantityUnits.pack("EA","250ML/EA").baseUnit()).isEqualTo("ml");
    }
    @Test void countAndMinimumWeightAreBothReadAndChosenByIngredientUnit() {
        var packs=QuantityUnits.packs("PAC","대란,국산,30EA,1560G이상/PAC,Y");
        assertThat(packs).extracting(QuantityUnits.Pack::baseUnit).containsExactly("g","EA");
        assertThat(packs.get(0).quantity()).isEqualByComparingTo("1560");
        assertThat(packs.get(1).quantity()).isEqualByComparingTo("30");
        assertThat(QuantityUnits.pack("PAC","대란,국산,30EA,1560G이상/PAC,Y").baseUnit()).isEqualTo("g");
        assertThat(QuantityUnits.packFor(packs,"EA").quantity()).isEqualByComparingTo("30");
        assertThat(QuantityUnits.packFor(packs,"kg").baseUnit()).isEqualTo("g");
        assertThat(QuantityUnits.packFor(packs,"ml")).isNull();
        // 개수만 있는 규격, "/주문단위"가 붙은 개수
        assertThat(QuantityUnits.pack("BOX","특란,10EA/BOX").quantity()).isEqualByComparingTo("10");
        // 개수 항목이 둘 이상이면 어느 쪽인지 모르므로 쓰지 않는다
        assertThat(QuantityUnits.packs("BOX","10EA,20EA")).isEmpty();
        // EA 주문은 1개 = 1EA
        assertThat(QuantityUnits.pack("EA","").quantity()).isEqualByComparingTo("1");
    }
    @Test void dimensionsAndAliasesAreValidated() {
        assertThat(QuantityUnits.convert(new BigDecimal("500"),"g","KG")).isEqualByComparingTo("0.5");
        assertThat(QuantityUnits.convert(BigDecimal.ONE,"l","ml")).isEqualByComparingTo("1000");
        assertThat(QuantityUnits.convert(BigDecimal.ONE,"팩","PAC")).isEqualByComparingTo("1");
        assertThatThrownBy(()->QuantityUnits.convert(BigDecimal.ONE,"g","ml")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void recipeIsRecomputedAndUnconvertibleRowsAreFlagged() {
        Map<String,Object> row=new HashMap<>(Map.of("qty_num",300,"qty_unit","g","recipe_yield_servings",3,"qty_base",999999));
        QuantityUnits.recipe(row,"g");
        assertThat((BigDecimal)row.get("qty_per_person")).isEqualByComparingTo("100");
        QuantityUnits.recipe(row,"PAC");
        assertThat(row.get("review_flag")).isEqualTo("1");
        assertThat((BigDecimal)row.get("qty_base")).isZero();
    }

    private static String first(String unit, String goodsz, String conv) {
        var packs = QuantityUnits.ourhomePacks(unit, goodsz, conv);
        return packs.stream().map(p -> p.quantity().stripTrailingZeros().toPlainString() + p.baseUnit()).reduce((a, b) -> a + "," + b).orElse("");
    }

    // 실제 아워홈 개발 응답의 goodsz/odrConvqty 조합
    @Test void ourhomeSpecIsAcceptedOnlyWhenWeightMatchesConversionQuantity() {
        assertThat(first("PK", "EA(450g)", "450")).isEqualTo("450g");
        assertThat(first("PK", "PK.(20kg)", "20000")).isEqualTo("20000g");
        assertThat(first("BOX", "BOX(4kg내외_국내산)", "4000")).isEqualTo("4000g");
        assertThat(first("PK", "PK.(12g4ea)", "48")).isEqualTo("48g");
        assertThat(first("PK", "PK.(개당20g*20)", "400")).isEqualTo("400g");
        assertThat(first("PK", "EA(450㎖)", "450")).isEqualTo("450ml");
        assertThat(first("EA", "EA(14kg)", "14000")).isEqualTo("1EA,14000g");
        assertThat(first("EA", "KG(원물/덩어리_호주)", "1000")).isEqualTo("1EA,1000g");
        assertThat(first("BOX", "PK.(77*24mm/30ea)", "30")).isEqualTo("30EA");
        assertThat(first("KG", "KG(국내산)", "1000")).isEqualTo("1000g");
    }

    @Test void ourhomeSpecIsNotGuessedWithoutMatchingEvidence() {
        assertThat(first("PK", "BOX", "600")).isEmpty();              // 규격에 중량 없음
        assertThat(first("PK", "EA(450g)", "10")).isEmpty();          // 환산수량과 불일치
        assertThat(first("PK", "EA(45cm*500m)", "1")).isEmpty();
        assertThat(first("PK", "EA(450g)", "")).isEmpty();
        assertThat(first("EA", "EA(8oz)", "1")).isEqualTo("1EA");
    }
}

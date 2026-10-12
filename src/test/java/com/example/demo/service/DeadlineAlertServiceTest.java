package com.example.demo.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** 발주 마감 알림: 가이드 마감 규칙, 우선 상품, 확인 필요 제외, 공급사·마감별 묶음 */
class DeadlineAlertServiceTest {
    final ShortageProcurementService shortages=mock(ShortageProcurementService.class);
    final DeadlineAlertService service=new DeadlineAlertService(shortages);
    // 2026-10-07 13:00 KST
    final Clock clock=Clock.fixed(Instant.parse("2026-10-07T04:00:00Z"),ZoneOffset.UTC);

    static Map<String,Object> offer(String supplier,String site,Object lead,String cutoffTime,String code,String preferred) {
        var o=new HashMap<String,Object>(Map.of("supplier_code",supplier,"supplier_name",supplier.equals("OURHOME")?"아워홈":"웰스토리",
            "external_site_code",site,"external_site_name",site+"사업장","product_status","ACTIVE","preferred_yn",preferred,"product_name",supplier+" 상품"));
        o.put("lead_time_days",lead);o.put("cutoff_time",cutoffTime);o.put("cutoff_code",code);
        return o;
    }
    static Map<String,Object> row(String id,String neededBy,List<String> issues,Map<String,Object>... offers) {
        var r=new HashMap<String,Object>(Map.of("ingredient_id",id,"ingredient_name",id+"명","shortage_qty",500,"base_unit","g","stock_status","RED"));
        r.put("needed_by",neededBy);r.put("issues",issues);r.put("offers",List.of(offers));
        return r;
    }
    void rows(Map<String,Object>... rows) { when(shortages.shortages(anyMapLike())).thenReturn(Map.of("ingredients",List.of(rows))); }
    static Map<String,Object> anyMapLike() { return argThat(p->"PLAN".equals(p.get("source"))&&"2026-10-07".equals(p.get("date_from"))); }

    @SuppressWarnings("unchecked")
    List<Map<String,Object>> alerts(Map<String,Object> input) { return (List<Map<String,Object>>)service.alerts(input,clock).get("alerts"); }

    @Test void groupsBySupplierSiteDeadlineAndUsesGuideRules() {
        rows(
            row("ONION","2026-10-09",List.of(),offer("OURHOME","FU0UP",2,"16:30",null,"Y")),          // 10/9 납품 → 10/7 16:30
            row("POTATO","2026-10-09",List.of(),offer("OURHOME","FU0UP",2,"16:30",null,"Y")),
            row("CARROT","2026-10-12",List.of(),offer("OURHOME","FU0UP",2,"16:30",null,"Y")),         // 10/12 필요 → 마지막 기회 10/10 16:30: 아직 알릴 때 아님
            row("BEEF","2026-10-08",List.of(),offer("WELSTORY","A001",1,null,"M01","Y")));            // 10/8 필요, 육류 11시 마감 10/7 11:00 지남 → 다음 마감 10/8 11:00(10/9 납품), 늦음
        var result=alerts(new HashMap<>(Map.of("account_id","A")));
        assertThat(result).hasSize(2);
        var ourhome=result.get(0);
        assertThat(ourhome).containsEntry("deadline","2026-10-07T16:30").containsEntry("delivery_date","2026-10-09").containsEntry("minutes_left",210L);
        assertThat((List<Map<String,Object>>)ourhome.get("items")).extracting(i->i.get("ingredient_id")).containsExactly("ONION","POTATO");
        assertThat(((List<Map<String,Object>>)ourhome.get("items")).get(0)).containsEntry("late",false);
        var welstory=result.get(1);
        assertThat(welstory).containsEntry("deadline","2026-10-08T11:00").containsEntry("delivery_date","2026-10-09");
        assertThat(welstory.get("rule").toString()).contains("육류 11시 마감");
        assertThat(((List<Map<String,Object>>)welstory.get("items")).get(0)).containsEntry("late",true);
    }

    @Test void skipsUnknownRulesIssuesAmbiguousOffersAndFarDeadlines() {
        rows(
            row("NORULE","2026-10-09",List.of(),offer("OURHOME","FU0UP",0,null,"1630","Y")),
            row("BLANK","2026-10-09",List.of(),offer("WELSTORY","A001",1,null," ","Y")),
            row("PENDING","2026-10-09",List.of("기존 주문 접수 확인 필요"),offer("OURHOME","FU0UP",2,"16:30",null,"Y")),
            row("TWO","2026-10-09",List.of(),offer("OURHOME","FU0UP",2,"16:30",null,"N"),offer("WELSTORY","A001",1,"17:00","F01","N")),
            row("FAR","2026-10-15",List.of(),offer("OURHOME","FU0UP",7,"15:00",null,"Y")));   // 10/15 필요 → 마지막 기회 10/8 15:00(26시간 뒤)
        assertThat(alerts(new HashMap<>(Map.of("account_id","A")))).isEmpty();
        assertThat(alerts(new HashMap<>(Map.of("account_id","A","within_hours","48")))).singleElement()
            .satisfies(g->assertThat(g).containsEntry("deadline","2026-10-08T15:00"));
    }

    @Test void onlyPreferredWhenSeveralAndValidatesInput() {
        rows(row("TWO","2026-10-08",List.of(),offer("OURHOME","FU0UP",2,"16:30",null,"N"),offer("WELSTORY","A001",1,"17:00","F01","Y")));
        assertThat(alerts(new HashMap<>(Map.of("account_id","A")))).singleElement().satisfies(g->assertThat(g).containsEntry("supplier_code","WELSTORY").containsEntry("deadline","2026-10-07T17:00"));
        assertThatThrownBy(()->service.alerts(new HashMap<>(),clock)).hasMessageContaining("거래처");
        assertThatThrownBy(()->service.alerts(new HashMap<>(Map.of("account_id","A","within_hours","100")),clock)).hasMessageContaining("1~72");
    }
}

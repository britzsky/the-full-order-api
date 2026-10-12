package com.example.demo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import com.example.demo.service.OurhomeSiteService;

/** 아워홈 주문 조회(getOrderList)만 호출해 특정 사업장·입고일의 주문 존재 여부를 확인한다. 주문 전송·DB 쓰기 없음. */
@EnabledIfEnvironmentVariable(named="RUN_OURHOME_ORDER_LOOKUP",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE)
class LocalOurhomeOrderLookupTest {
    @Autowired OurhomeSiteService api;
    @Test void lookup() {
        String site=System.getenv().getOrDefault("OURHOME_SITE","FU0UR"), date=System.getenv().getOrDefault("OURHOME_DATE","2026-10-09");
        try {
            var site0=api.requireSite(site);
            System.out.println("OH_SITE "+site0.siteCode()+" "+site0.siteName()+" deliveryDates="+site0.deliveryDates());
            var result=api.orders(site,date);
            System.out.println("OH_ORDERS count="+result.orders().size());
            result.orders().forEach(o->System.out.println("OH_ORDER "+o));
        } catch(Exception e) { System.out.println("OH_ERROR "+e); }
    }
}

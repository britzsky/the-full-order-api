package com.example.demo.service;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** 실제 아워홈 카탈로그 동기화를 연속 2회 실행해 소요 시간을 잰다(2회째는 변경 없음 빠른 경로). 로컬 DB에 쓴다. */
@EnabledIfEnvironmentVariable(named="RUN_OURHOME_SYNC_TIMING",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE)
class LocalOurhomeCatalogSyncTimingTest {
    @Autowired OurhomeCatalogSyncService sync;
    @Autowired OurhomeSiteService api;
    @Test void timing() {
        var env=System.getenv();
        Map<String,Object> input=Map.of(
                "account_id",env.getOrDefault("OURHOME_ACCOUNT","20250819193526"),
                "siteCode",env.getOrDefault("OURHOME_SITE","FU0UR"),
                "deliveryDate",env.getOrDefault("OURHOME_DATE","2026-10-09"),
                "user_id",env.getOrDefault("OURHOME_USER","admin"));
        // 아워홈 API 수집만 따로 잰다(DB 쓰기 없음).
        long f=System.nanoTime(); int pages=0, rows=0; String cursor="";
        var site=api.requireSite((String)input.get("siteCode"));
        while(true) {
            var page=api.catalogPage(site,(String)input.get("deliveryDate"),cursor); pages++; rows+=page.products().size();
            if(!page.hasNext()) break;
            cursor=page.nextKey();
        }
        System.out.println("OH_FETCH ms="+(System.nanoTime()-f)/1_000_000+" pages="+pages+" rows="+rows);
        for(int i=1;i<=2;i++) {
            Runtime.getRuntime().gc();
            long used=Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory(), t=System.nanoTime();
            var result=sync.sync(input);
            long ms=(System.nanoTime()-t)/1_000_000, after=Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory();
            System.out.println("OH_SYNC run="+i+" ms="+ms+" heapDeltaMB="+(after-used)/1048576+" result="+result);
        }
    }
}

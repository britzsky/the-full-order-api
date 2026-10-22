package com.example.demo.service;

import java.lang.reflect.Method;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** 아워홈 상품 조회 방식별 소요 시간 측정(조회 전용, DB·아워홈 쓰기 없음). 실시간 검색·연결 상품만 갱신 설계 판단용. */
@EnabledIfEnvironmentVariable(named="RUN_OURHOME_LOOKUP_TIMING",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE)
class LocalOurhomeLookupTimingTest {
    @Autowired OurhomeSiteService api;
    @Autowired JdbcTemplate jdbc;

    @Test void timing() throws Exception {
        var env=System.getenv();
        String account=env.getOrDefault("OURHOME_ACCOUNT","20250819193526"), siteCode=env.getOrDefault("OURHOME_SITE","FU0UR"),
                date=env.getOrDefault("OURHOME_DATE","2026-10-09");
        var site=api.requireSite(siteCode);
        // 1) 키워드 검색(화면 검색 1회, 100건 페이지)
        for(String keyword:List.of("양파","돼지고기","두부","우유","계란")) {
            long t=System.nanoTime();
            var r=api.products(siteCode,date,keyword,"N","");
            System.out.printf("OH_KEYWORD %s ms=%d rows=%d total=%d next=%s%n",keyword,ms(t),r.products().size(),r.totalRow(),r.hasNext());
        }
        // 2) 상품코드 조회(이 거래처가 발주했던 아워홈 상품을 코드별로 갱신할 경우)
        List<String> codes=jdbc.queryForList("""
                SELECT DISTINCT p.supplier_item_code FROM the_full_order.tb_account_purchase_order_item i
                JOIN the_full_order.tb_account_purchase_order po ON po.purchase_order_id=i.purchase_order_id
                JOIN the_full_order.tb_supplier_ingredient_product p ON p.supplier_product_id=i.supplier_product_id
                JOIN the_full_order.tb_supplier s ON s.supplier_id=p.supplier_id AND s.supplier_code='OURHOME'
                WHERE po.account_id=? LIMIT 20""",String.class,account);
        long all=System.nanoTime(); int found=0;
        for(String code:codes) {
            long t=System.nanoTime();
            var r=api.products(siteCode,date,code,"G","");
            found+=r.products().stream().anyMatch(p->code.equals(p.get("goodcd")))?1:0;
            System.out.printf("OH_CODE %s ms=%d rows=%d%n",code,ms(t),r.products().size());
        }
        System.out.printf("OH_CODE_TOTAL codes=%d found=%d ms=%d avg=%d%n",codes.size(),found,ms(all),codes.isEmpty()?0:ms(all)/codes.size());
        // 3) 전체 목록 페이지 크기별(현재 1000건 → 문서상 최대 10000건)
        Method page=OurhomeSiteService.class.getDeclaredMethod("products",OurhomeSiteService.Site.class,String.class,String.class,String.class,String.class,int.class);
        page.setAccessible(true);
        for(int size:List.of(1000,5000,10000)) {
            long t=System.nanoTime(); int pages=0, rows=0; String cursor="";
            try {
                while(true) {
                    var r=(OurhomeSiteService.ProductResult)page.invoke(api,site,date,"","A",cursor,size);
                    pages++; rows+=r.products().size();
                    if(!r.hasNext()) break;
                    cursor=r.nextKey();
                }
                System.out.printf("OH_FULL pageSize=%d ms=%d pages=%d rows=%d%n",size,ms(t),pages,rows);
            } catch(Exception e) {
                System.out.printf("OH_FULL pageSize=%d error=%s%n",size,e.getCause()==null?e:e.getCause());
            }
        }
    }
    private static long ms(long start) { return (System.nanoTime()-start)/1_000_000; }
}

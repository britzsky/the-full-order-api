package com.example.demo;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import com.example.demo.controller.SupplierSiteController;

/** 사업장 목록 갱신 500 진단. 공급사 사업장 조회 API만 호출하고 DB 저장은 테스트 트랜잭션으로 롤백한다. */
@EnabledIfEnvironmentVariable(named="RUN_SITE_SYNC_DIAGNOSIS",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE)
@Transactional
class LocalSiteSyncDiagnosisTest {
    @Autowired SupplierSiteController controller;

    @Test void readEndpointsUsedByMappingScreen() {
        for(var entry:java.util.List.<java.util.Map.Entry<String,java.util.function.Supplier<Object>>>of(
                java.util.Map.entry("sites",()->controller.sites("")),java.util.Map.entry("mappings",()->controller.mappings()))) {
            try {
                var response=(org.springframework.http.ResponseEntity<?>)entry.getValue().get();
                String body=String.valueOf(response.getBody());
                System.out.println("SITE_READ "+entry.getKey()+" status="+response.getStatusCode()+" body="+body.substring(0,Math.min(300,body.length())));
            } catch(Throwable t) {
                System.out.println("SITE_READ "+entry.getKey()+" EXCEPTION "+t);
                for(Throwable c=t.getCause();c!=null;c=c.getCause()) System.out.println("SITE_READ_CAUSE "+c);
            }
        }
    }

    @Test void syncSitesForBothSuppliers() {
        for(String code:new String[]{"WELSTORY","OURHOME"}) {
            try {
                var response=controller.syncSites(new java.util.HashMap<>(Map.of("supplier_code",code,"user_id","DIAG")));
                System.out.println("SITE_SYNC "+code+" status="+response.getStatusCode()+" body="+response.getBody());
            } catch(Throwable t) {
                System.out.println("SITE_SYNC "+code+" EXCEPTION "+t);
                for(Throwable c=t.getCause();c!=null;c=c.getCause()) System.out.println("SITE_SYNC_CAUSE "+c);
                for(var e:t.getStackTrace()) if(e.getClassName().startsWith("com.example")) System.out.println("SITE_SYNC_AT "+e);
            }
        }
    }
}

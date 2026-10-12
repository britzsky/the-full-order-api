package com.example.demo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import com.example.demo.service.WelstoryItemLookupService;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 웰스토리 사업장 조회(payer-rep-soldto) 500 진단. 조회 API만 호출, DB 쓰기 없음. 요청 변형별 응답 상태·본문을 출력한다. */
@EnabledIfEnvironmentVariable(named="RUN_WELSTORY_SOLDTO_DIAGNOSIS",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE)
class LocalWelstorySoldToDiagnosisTest {
    @Autowired WelstoryItemLookupService api;
    final ObjectMapper json=new ObjectMapper();

    void call(String label,String request) {
        try {
            var response=api.call("/fdapi/service/payer-rep-soldto",json.readTree(request));
            String text=response.toString();
            System.out.println("SOLDTO "+label+" OK header="+response.path("dataHeader")+" resCd="+response.path("dataBody").path("resCd")
                +" resMsg="+response.path("dataBody").path("resMsg")+" rows="+response.path("dataBody").path("data").size()+" len="+text.length());
        } catch(WebClientResponseException e) {
            System.out.println("SOLDTO "+label+" HTTP "+e.getStatusCode().value()+" body="+e.getResponseBodyAsString().replaceAll("\\s+"," ").substring(0,Math.min(800,e.getResponseBodyAsString().length())));
        } catch(Exception e) {
            System.out.println("SOLDTO "+label+" EXCEPTION "+e);
        }
    }

    @Test void variants() {
        call("A_sync_default","{\"dataHeader\":{\"pageRow\":100,\"contYn\":\"Y\",\"nextKey\":\"\"},\"dataBody\":{}}");
        call("B_contYn_N","{\"dataHeader\":{\"pageRow\":100,\"contYn\":\"N\",\"nextKey\":\"\"},\"dataBody\":{}}");
        call("C_page_10","{\"dataHeader\":{\"pageRow\":10,\"contYn\":\"N\",\"nextKey\":\"\"},\"dataBody\":{}}");
        call("D_no_header","{\"dataBody\":{}}");
    }
}

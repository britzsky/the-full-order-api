package com.example.demo.service;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
class OurhomeOrderAdapterTest {
    final OurhomeSiteService api=mock(OurhomeSiteService.class);
    final ObjectMapper json=new ObjectMapper();
    final OurhomeOrderAdapter adapter=new OurhomeOrderAdapter(api,json);
    final Map<String,Object> order=Map.of("sold_to","FU0UP","requested_delivery_date","2099-10-01","client_ord","ORDER-1");
    final Map<String,Object> row=Map.of("supplier_item_code","25000689","client_ord_item","1","order_qty",new BigDecimal("0.5"),"purchase_price",3880);
    ObjectNode raw(){var raw=json.createObjectNode().put("rtnCd",0);raw.putObject("result").putArray("data").addObject()
        .put("extnOrderKey","ORDER-1").put("extnOrderNo","1").put("goodcd","25000689").put("rtnCd",0)
        .put("ordQty","0.5").put("salsUcost","3880").put("crud","C").put("depositDt","20991001");return raw;}
    @Test void requiresLineIdentityQuantityPriceAndDateForAcceptance(){
        var raw=raw();assertThat(adapter.normalize(raw,order,List.of(row),false).path("dataBody").path("data").get(0).path("provider_verified").asBoolean()).isTrue();
        ((ObjectNode)raw.path("result").path("data").get(0)).put("ordQty","1");
        assertThat(adapter.normalize(raw,order,List.of(row),false).path("dataBody").path("data").get(0).path("provider_verified").asBoolean()).isFalse();
    }
    @Test void distinguishesEachRejectionFromUnknownAndDoesNotMatchOtherOrders(){
        var raw=raw();var line=(ObjectNode)raw.path("result").path("data").get(0);line.put("rtnCd",-1);
        assertThat(adapter.normalize(raw,order,List.of(row),false).toString()).contains("REJECTED");
        line.put("extnOrderKey","OTHER");assertThat(adapter.normalize(raw,order,List.of(row),false).path("dataBody").path("data")).isEmpty();
    }
    @Test void duplicateResultIdentityIsNotAccepted(){var raw=raw();((ObjectNode)raw.path("result")).withArray("data").add(raw.path("result").path("data").get(0).deepCopy());assertThat(adapter.normalize(raw,order,List.of(row),false).path("dataBody").path("data")).isEmpty();}
    @Test void requestUsesStableExternalKeysAndDecimalStringsWithoutToken(){
        for(String code:List.of("FU0UP","FU0TT")) {
            when(api.requireSite(code)).thenReturn(new OurhomeSiteService.Site(code,"사업장","HEAD","","","","","",List.of("2099-10-01")));
            var input=new HashMap<>(order); input.put("sold_to",code); input.put("user_id","LOCAL-OPERATOR");
            var request=adapter.request(input,List.of(row));
            assertThat(request.path("ordList").get(0).path("ordQty").asText()).isEqualTo("0.5");
            assertThat(request.path("ordList").get(0).path("extnOrderKey").asText()).isEqualTo("ORDER-1");
            assertThat(request.path("userId").asText()).isEqualTo(code);
            assertThat(request.path("busiplcd").asText()).isEqualTo(code);
            assertThat(request.has("tokenInfo")).isFalse();
        }
        verify(api,never()).submitOrder(any());
    }
}

package com.example.demo.service;

import java.util.*;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import static com.example.demo.service.OrderWorkflowService.*;

@Service
public class OurhomeOrderAdapter {
    private final OurhomeSiteService api;
    private final ObjectMapper json;
    public OurhomeOrderAdapter(OurhomeSiteService api,ObjectMapper json) {
        this.api=api; this.json=json;
    }
    public JsonNode validate(String site,String date,String code,Map<String,Object> offer,java.math.BigDecimal qty) {
        var matches=api.products(site,date,code,"G","").products().stream().filter(r->code.equals(r.get("goodcd"))).toList();
        if(matches.size()!=1) throw new IllegalArgumentException("아워홈 실시간 상품을 확인할 수 없습니다: "+code);
        var row=matches.get(0);
        if(!OurhomeCatalogSyncService.orderable(row)) throw new IllegalArgumentException("아워홈 발주 불가 상품입니다: "+code);
        if(number(row.get("salsUcost")).compareTo(number(offer.get("purchase_price")))!=0)
            throw new IllegalArgumentException("아워홈 가격이 변경되었습니다. 해당 납품일 상품을 다시 동기화하세요: "+code);
        if(!text(row.get("odrUnit")).equals(text(offer.get("order_unit"))))
            throw new IllegalArgumentException("아워홈 발주단위가 변경되었습니다: "+code);
        var step=OurhomeCatalogSyncService.step(row);
        validateQuantity(qty,step,step,row.get("decOdrupYn"));
        // 기존 공통 검증에서도 사용할 수 있도록 검증한 값만 표준화한다.
        ObjectNode result=json.valueToTree(row);
        result.put("itemCode",code).put("price",row.get("salsUcost"));
        return result;
    }
    public ObjectNode request(Map<String,Object> p,List<Map<String,Object>> rows) {
        var site=api.requireSite(text(p.get("sold_to")));
        if(!site.deliveryDates().contains(text(p.get("requested_delivery_date")))) throw new IllegalArgumentException("아워홈 발주 가능일이 변경되었습니다.");
        ObjectNode request=json.createObjectNode();
        request.put("busiplcd",site.siteCode()).put("headGrpCd",site.headCode())
            .put("depositDt",text(p.get("requested_delivery_date")).replace("-",""))
            // 아워홈 발주 사용자ID는 해당 주문의 검증된 사업장코드다.
            .put("userId",site.siteCode()).put("txnMode","EACH");
        var items=request.putArray("ordList");
        for(var row:rows) items.addObject().put("crud","C").put("extnOrderKey",text(p.get("client_ord")))
            .put("extnOrderNo",text(row.get("client_ord_item"))).put("goodcd",text(row.get("supplier_item_code")))
            .put("ordQty",text(row.get("order_qty"))).put("reqSalsUcost",text(row.get("purchase_price")))
            .put("remark",text(p.get("client_note"))).put("ordKeyVal","").put("ordSeq","");
        return request;
    }
    public JsonNode submit(ObjectNode request,Map<String,Object> p,List<Map<String,Object>> rows) {
        return normalize(api.submitOrder(request),p,rows,false);
    }
    public JsonNode reconcile(Map<String,Object> p,List<Map<String,Object>> rows) {
        var result=api.orders(text(p.get("sold_to")),text(p.get("requested_delivery_date")));
        ObjectNode raw=json.createObjectNode().put("rtnCd",0);
        raw.putObject("result").set("data",json.valueToTree(result.orders()));
        return normalize(raw,p,rows,true);
    }
    JsonNode normalize(JsonNode raw,Map<String,Object> p,List<Map<String,Object>> rows,boolean lookup) {
        ObjectNode response=json.createObjectNode();
        // 공급사 원문이 인증 요청을 echo하더라도 인증값을 교환 이력에 저장하지 않는다.
        var audit=response.putObject("provider_response");
        audit.set("rtnCd",raw.path("rtnCd"));
        var auditRows=audit.putArray("data");
        if(raw.path("result").path("data").isArray()) for(JsonNode item:raw.path("result").path("data")) {
            var saved=auditRows.addObject();
            for(String field:List.of("rtnCd","rtnMsg","extnOrderKey","extnOrderNo","retSeq","ordKeyVal","ordSeq",
                    "goodcd","goodnm","reqOrdQty","ordQty","clhaqty","crud","depositDt","reqSalsUcost","salsUcost",
                    "taxYn","txnSplamt","dfSplamt","txtnTxamt","salsAmt","ordDt","delYn","prcsStatYn","ordStatYn"))
                if(item.has(field)) saved.set(field,item.get(field));
        }
        var body=response.putObject("dataBody");
        boolean topOk=raw.path("rtnCd").isIntegralNumber() && raw.path("rtnCd").asInt()==0;
        body.put("resCd",topOk?"S0000":"UNKNOWN").put("resMsg","아워홈 품목별 접수 결과를 확인하세요.");
        var data=body.putArray("data"); JsonNode source=raw.path("result").path("data");
        for(var row:rows) {
            List<JsonNode> matches=new ArrayList<>();
            if(source.isArray()) for(JsonNode item:source)
                if(text(p.get("client_ord")).equals(item.path("extnOrderKey").asText())
                    && text(row.get("client_ord_item")).equals(item.path("extnOrderNo").asText())) matches.add(item);
            if(matches.size()!=1) continue;
            var match=matches.get(0);
            boolean identity=text(row.get("supplier_item_code")).equals(match.path("goodcd").asText());
            String qty=match.path(lookup?"clhaqty":"ordQty").asText("");
            String price=match.path("salsUcost").asText("");
            Object expected=row.containsKey("purchase_price")?row.get("purchase_price"):row.get("unit_price_snapshot");
            boolean exact=false;
            try { exact=!qty.isBlank() && !price.isBlank() && number(qty).compareTo(number(row.get("order_qty")))==0
                    && number(price).compareTo(number(expected))==0; } catch(NumberFormatException ignored) { }
            boolean accepted=topOk && identity && exact && !"Y".equals(match.path("delYn").asText())
                    && (lookup || (match.path("rtnCd").isIntegralNumber() && match.path("rtnCd").asInt()==0
                    && "C".equals(match.path("crud").asText())
                    && text(p.get("requested_delivery_date")).replace("-","").equals(match.path("depositDt").asText())));
            boolean rejected=topOk && identity && !lookup && match.path("rtnCd").isIntegralNumber() && match.path("rtnCd").asInt()<0;
            data.addObject().put("clientOrd",text(p.get("client_ord"))).put("clientOrdItem",text(row.get("client_ord_item")))
                .put("itemCode",text(row.get("supplier_item_code"))).put("resCd",accepted?"S0000":rejected?"REJECTED":"")
                .put("provider_verified",accepted).put("errorMsg",accepted?"":rejected?match.path("rtnMsg").asText("아워홈 접수 거절"):"아워홈 실제 수량·단가 또는 접수 상태 확인 필요");
        }
        return response;
    }
}

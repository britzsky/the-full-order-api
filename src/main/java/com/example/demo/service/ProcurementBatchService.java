package com.example.demo.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.example.demo.mapper.ProcurementBatchMapper;
import com.example.demo.mapper.SupplierIntegrationMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import static com.example.demo.service.OrderWorkflowService.*;

/** 화면의 공급사 식별값 대신 거래처 소유 offer로 주문을 분리하고 분리 결과를 영속화한다. */
@Service
public class ProcurementBatchService {
    public static class NotSubmittedException extends IllegalArgumentException {
        public NotSubmittedException(String message) { super(message); }
    }
    private final ProcurementBatchMapper batches;
    private final SupplierIntegrationMapper catalog;
    private final OrderWorkflowService orders;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    public ProcurementBatchService(ProcurementBatchMapper batches,SupplierIntegrationMapper catalog,
            OrderWorkflowService orders,ObjectMapper json,PlatformTransactionManager manager) {
        this.batches=batches;this.catalog=catalog;this.orders=orders;this.json=json;this.tx=new TransactionTemplate(manager);
    }
    public Map<String,Object> submit(JsonNode input) {
        String account=required(input,"account_id"), key=required(input,"request_key");
        if(!key.matches("[A-Za-z0-9-]{16,64}")) throw new IllegalArgumentException("주문 요청키가 올바르지 않습니다.");
        JsonNode items=input.path("items");
        if(!items.isArray() || items.isEmpty() || items.size()>500) throw new IllegalArgumentException("1~500개 발주 품목을 선택하세요.");
        Map<String,Object> p=new LinkedHashMap<>();p.put("account_id",account);p.put("request_key",key);
        String fingerprint=hash(input.toString());
        var batch=batches.find(p);
        if(batch==null) {
            List<ObjectNode> groups;
            try { groups=group(input); }
            catch(IllegalArgumentException e) { throw new NotSubmittedException(e.getMessage()); }
            p.put("request_fingerprint",fingerprint);p.put("group_payload",json.valueToTree(groups).toString());
            batch=tx.execute(s->{batches.reserve(p);return batches.find(p);});
        }
        if(batch==null || !fingerprint.equals(text(batch.get("request_fingerprint"))))
            throw new IllegalArgumentException("동일 요청키의 발주 내용이 다릅니다. 기존 발주 결과를 먼저 확인하세요.");
        JsonNode groups;
        try { groups=json.readTree(text(batch.get("group_payload"))); }
        catch(Exception e) { throw new IllegalStateException("저장된 발주 묶음을 확인할 수 없습니다."); }
        List<Map<String,Object>> results=new ArrayList<>();
        for(JsonNode group:groups) {
            Map<String,Object> result=new LinkedHashMap<>();
            var header=group.path("order");
            result.put("supplier_code",header.path("supplier_code").asText());
            result.put("supplier_name",header.path("supplier_name").asText());
            result.put("external_site_code",header.path("sold_to").asText());
            result.put("delivery_date",header.path("req_delivery_date").asText());
            try { result.putAll(orders.order(group)); }
            catch(IllegalArgumentException e) {result.put("status","VALIDATION_FAILED");result.put("message",e.getMessage());}
            catch(RuntimeException e) {result.put("status","UNKNOWN");result.put("message","처리 결과 확인 필요. 같은 묶음으로 결과를 다시 확인하세요.");}
            results.add(result);
        }
        return Map.of("request_key",key,"orders",results);
    }
    List<ObjectNode> group(JsonNode input) {
        Map<String,ObjectNode> groups=new TreeMap<>(); Set<String> seen=new HashSet<>(), sources=new HashSet<>();
        for(JsonNode item:input.path("items")) {
            String date=LocalDate.parse(required(item,"delivery_date")).toString();
            String offerId=required(item,"supplier_offer_id");
            if(!seen.add(offerId+"|"+date)) throw new IllegalArgumentException("같은 상품·납품일 수량은 합산하세요.");
            String source=item.path("source_ingredient_id").asText("");
            if(!source.isBlank()&&!sources.add(source)) throw new IllegalArgumentException("동일 식자재를 중복 발주할 수 없습니다.");
            Map<String,Object> q=new LinkedHashMap<>(); q.put("account_id",required(input,"account_id"));
            q.put("supplier_offer_id",offerId);q.put("delivery_date",date);q.put("price_at",date);
            var offers=catalog.offers(q);
            if(offers.size()!=1) throw new IllegalArgumentException("거래처 상품·납품일을 다시 확인하세요: "+offerId);
            var offer=offers.get(0); String supplier=text(offer.get("supplier_code")),site=text(offer.get("external_site_code"));
            if(!Set.of("WELSTORY","OURHOME").contains(supplier)) throw new IllegalArgumentException("지원하지 않는 공급사입니다.");
            String groupKey=supplier+"|"+site+"|"+date;
            ObjectNode group=groups.computeIfAbsent(groupKey,k->{
                ObjectNode g=json.createObjectNode();var h=g.putObject("order");
                h.put("account_id",required(input,"account_id")).put("supplier_code",supplier).put("supplier_name",text(offer.get("supplier_name")))
                    .put("sold_to",site).put("req_delivery_date",date.replace("-",""))
                    .put("request_key",UUID.nameUUIDFromBytes((required(input,"request_key")+"|"+k).getBytes(StandardCharsets.UTF_8)).toString())
                    .put("user_id",input.path("user_id").asText("")).put("client_note","공급사 통합 발주");
                if(input.hasNonNull("shortage_criteria")) h.set("shortage_criteria",input.path("shortage_criteria").deepCopy());
                g.putArray("items");return g;
            });
            var line=group.withArray("items").addObject();
            line.put("supplier_item_code",text(offer.get("supplier_item_code"))).put("source_ingredient_id",source)
                .put("ingredient_id",source).put("menu_id","").put("order_qty",required(item,"order_qty"));
            line.put("expected_price",required(item,"expected_price"));
            line.put("delivery_terms_confirmed",item.path("delivery_terms_confirmed").asBoolean(false));
        }
        return new ArrayList<>(groups.values());
    }
    private static String hash(String value) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(Exception e){throw new IllegalStateException(e);}
    }
}
